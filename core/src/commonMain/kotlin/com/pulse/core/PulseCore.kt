package com.pulse.core

import com.pulse.core.api.ApiClient
import com.pulse.core.api.Backend
import com.pulse.core.api.RemoteAuthService
import com.pulse.core.api.TokenStore
import com.pulse.core.auth.AuthService
import com.pulse.core.auth.LocalAuthService
import com.pulse.core.auth.SessionStore
import com.pulse.core.demo.PlayerSurveyState
import com.pulse.core.feature.ActivityStreak
import com.pulse.core.feature.NotificationInbox
import com.pulse.core.model.AboutYou
import com.pulse.core.model.AboutYouForm
import com.pulse.core.model.BodyProfile
import com.pulse.core.model.FitnessGoals
import com.pulse.core.model.WeeklyInputs
import com.pulse.core.store.BodyProfileStore
import com.pulse.core.util.addingDays
import com.pulse.core.util.isSameDay
import com.pulse.core.util.startOfDay
import kotlin.time.Clock
import kotlin.time.Instant
import com.pulse.core.model.CalendarEvent
import com.pulse.core.model.GameGoals
import com.pulse.core.model.Lineup
import com.pulse.core.model.User
import com.pulse.core.platform.FileSystem
import com.pulse.core.platform.SecretStore
import com.pulse.core.storage.DataScope
import com.pulse.core.storage.JsonFileStorage
import com.pulse.core.store.CalendarStore
import com.pulse.core.store.CognitiveStore
import com.pulse.core.store.FaceitClient
import com.pulse.core.store.FitnessStore
import com.pulse.core.store.FoodAnalyzer
import com.pulse.core.store.GameGoalsStore
import com.pulse.core.store.GameStore
import com.pulse.core.store.HealthDataProvider
import com.pulse.core.store.LineupStore
import com.pulse.core.store.MockFoodAnalyzer
import com.pulse.core.store.NutritionStore
import com.pulse.core.store.PlayerSurveyStore
import com.pulse.core.store.ScreenTimeStore
import com.pulse.core.store.SleepStore
import com.pulse.core.store.WellbeingStore
import com.pulse.core.sync.SyncEngine
import com.pulse.core.sync.SyncResult
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Duration.Companion.seconds

/** What the app shows about data sync: only the download right after sign-in has its own screen. */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    /** «Загружаем ваши данные…» after a sign-in, until the download is done (at most 20 s). */
    data object FirstLoad : SyncStatus
    /** The download after sign-in failed: local data is shown, the message waits for «Понятно». */
    data object FirstLoadFailed : SyncStatus
    data object Running : SyncStatus
}

/**
 * Composition root shared by the platforms (Kotlin twin of iOS `AppDependencies`): owns every store, points them
 * at the active account's folder and reloads them on login, logout and demo switches.
 */
class PulseCore(
    files: FileSystem,
    secrets: SecretStore,
    http: HttpClient,
    private val background: CoroutineScope,
    health: HealthDataProvider?,
    foodAnalyzer: FoodAnalyzer = MockFoodAnalyzer(),
    /** The app's Faceit Data API key from the build (players may still enter their own). */
    faceitKey: String? = null,
    /** Chosen per build: on-device accounts (first release) or the Pulse server. */
    backend: Backend = Backend.Local,
    /** Where screens change the stores; files merged in by a sync are written and reloaded there too. */
    private val ui: CoroutineDispatcher = Dispatchers.Main
) {
    val scope = DataScope(files)
    private val tokens = TokenStore(secrets)
    private val api = ApiClient(http, (backend as? Backend.Remote)?.baseUrl, tokens)

    /** True in a build that signs in on the Pulse server (demo accounts still work locally). */
    val isRemote: Boolean = backend is Backend.Remote

    private fun <T> file(name: String, serializer: KSerializer<T>, global: Boolean = false) = JsonFileStorage(name, serializer, scope, global)

    /** Local accounts in a local build; the Pulse API in a remote one. */
    val auth: AuthService = if (isRemote) {
        RemoteAuthService(api, background)
    } else {
        LocalAuthService(file("accounts.json", ListSerializer(LocalAuthService.Account.serializer()), global = true), secrets)
    }
    val sync = SyncEngine(api, scope)
    val session = SessionStore(auth, file("session.json", User.serializer(), global = true), scope, requireBetaTerms = isRemote)

    val sleep = SleepStore(file(SleepStore.FILE, SleepStore.Saved.serializer()))
    val nutrition = NutritionStore(file(NutritionStore.FILE, NutritionStore.Saved.serializer()), foodAnalyzer)
    val wellbeing = WellbeingStore(file(WellbeingStore.FILE, WellbeingStore.Saved.serializer()))
    val cognitive = CognitiveStore(file(CognitiveStore.FILE, CognitiveStore.Saved.serializer()))
    val calendar = CalendarStore(file(CalendarStore.FILE, ListSerializer(CalendarEvent.serializer())))
    val fitness = FitnessStore(file(FitnessStore.FILE, FitnessStore.Saved.serializer()), health)
    val screenTime = ScreenTimeStore(file(ScreenTimeStore.FILE, ScreenTimeStore.Saved.serializer()))
    val lineups = LineupStore(file(LineupStore.FILE, ListSerializer(Lineup.serializer())))
    val gameGoals = GameGoalsStore(file(GameGoalsStore.FILE, GameGoals.serializer()))
    val game = GameStore(file(GameStore.FILE, GameStore.Saved.serializer()), secrets, faceitKey) { key -> FaceitClient(key, http) }
    val survey = PlayerSurveyStore(file(PlayerSurveyStore.FILE, PlayerSurveyState.serializer()))
    val body = BodyProfileStore(file(BodyProfileStore.FILE, BodyProfile.serializer()))

    /** Platform hook: reschedule reminders, stop the sleep notification of the previous account, etc. */
    var onAccountActivated: ((User?, Boolean) -> Unit)? = null

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _firstPullPending = MutableStateFlow(false)
    /**
     * True from the start of a first load (sign-in, verify or reset of a syncable account) until its download half has
     * finished — successfully or not — and the downloaded documents are in the stores, or the load was stopped by an
     * account switch. Unlike [SyncStatus.FirstLoad] it outlasts the 20 s loader: what asks the player to fill in stores
     * (the «О тебе» step) waits for it, or its defaults would overwrite the server's copy.
     */
    val firstPullPending: StateFlow<Boolean> = _firstPullPending.asStateFlow()

    /** A real account signed in on the Pulse server (demo accounts and local builds never sync). */
    val isSyncable: Boolean get() = isRemote && session.user.value?.isDemo == false

    /**
     * Platform hook for sync diagnostics: each failed collection, photo or round, with the error's kind, code and HTTP
     * status — never document contents, tokens or emails.
     */
    var log: (String) -> Unit = {}

    /** Platform hook, called after every save of a syncable account's file: debounce and schedule [syncNow]. */
    var onLocalChange: (() -> Unit)? = null

    /** One sync round at a time; a round never outlives its account (see [stopRound]). */
    private val syncLock = Mutex()
    private val runningRound = MutableStateFlow<Job?>(null)
    /** The first load after the latest sign-in, while it runs; an older one no longer changes the status. */
    private val firstLoad = MutableStateFlow<Any?>(null)

    init {
        // A session saved by a local build has no server tokens: sign it out before any store loads its folder.
        // Demo accounts stay local in every build.
        val stored = session.user.value
        if (isRemote && stored != null && !stored.isDemo && tokens.tokens == null) session.signOutNow()
        fitness.onHealthSnapshot = { sleep.applyHealth(it) }
        scope.onChange = { if (isSyncable) onLocalChange?.invoke() }
        session.beforeLogout = { pushBeforeLogout() }
        sync.applyFile = { folder, file, write -> applyMergedFile(folder, file, write) }
        sync.log = { message -> log(message) }
        session.onUserChange = { user ->
            stopRound()
            activate(user, accountChanged = true)
            firstLoad.value = null
            _firstPullPending.value = false
            _syncStatus.value = SyncStatus.Idle
            if (isSyncable) startFirstLoad()
        }
        activate(session.user.value, accountChanged = false)
    }

    /**
     * Points every store at the account's own folder: demo accounts get seeded data, real accounts start empty.
     */
    fun activate(user: User?, accountChanged: Boolean) {
        scope.current = user?.let(DataScope::id) ?: "guest"
        reloadStores(user)
        onAccountActivated?.invoke(user, accountChanged)
    }

    /** Every store re-reads the active account's folder. */
    private fun reloadStores(user: User?) {
        for (file in storeFiles) reloadStore(file, user)
    }

    /** The store kept in [file] re-reads it from the active account's folder. */
    private fun reloadStore(file: String, user: User?) {
        val isDemo = user?.isDemo ?: false
        when (file) {
            SleepStore.FILE -> sleep.reload(isDemo)
            NutritionStore.FILE -> nutrition.reload(isDemo)
            WellbeingStore.FILE -> wellbeing.reload(isDemo)
            CognitiveStore.FILE -> cognitive.reload(isDemo)
            CalendarStore.FILE -> calendar.reload(isDemo)
            FitnessStore.FILE -> fitness.reload(isDemo)
            ScreenTimeStore.FILE -> screenTime.reload(isDemo)
            LineupStore.FILE -> lineups.reload(isDemo)
            GameGoalsStore.FILE -> gameGoals.reload(isDemo)
            GameStore.FILE -> game.reload(isDemo)
            PlayerSurveyStore.FILE -> survey.reload(user)
            BodyProfileStore.FILE -> {
                body.reload(user)
                fitness.bodyProfile = body.profile
            }
        }
    }

    /**
     * A sync brings a server copy into [file]: written on the screens' dispatcher, and the store re-reads it in the
     * same step, so no save from a screen lands in between with the store's older copy.
     */
    private suspend fun applyMergedFile(folder: String, file: String, write: () -> Unit) = withContext(ui) {
        write()
        // After an account switch the stores read another folder.
        if (scope.folder() == folder) reloadSyncedStore(file)
    }

    /** Like [reloadStore], but what a store holds only in memory (Health Connect, Faceit) stays. */
    private fun reloadSyncedStore(file: String) = when (file) {
        SleepStore.FILE -> sleep.reloadSaved()
        FitnessStore.FILE -> fitness.reloadSaved()
        GameStore.FILE -> game.reloadSaved()
        else -> reloadStore(file, session.user.value)
    }

    /**
     * Downloads what changed on the server, then uploads unsent changes (a conflict merges in another phone's records
     * too). Every file the round rewrites is reloaded into its store as it is written ([applyMergedFile]). Never throws
     * for a sync failure: it is reported as a failed result and the changes stay pending for the next round.
     */
    suspend fun syncNow(): SyncResult = round(afterPull = {})

    /** [afterPull] runs once the download half is done, before the upload half starts. */
    private suspend fun round(afterPull: (SyncResult) -> Unit): SyncResult = exclusiveRound { folder ->
        val running = _syncStatus.compareAndSet(SyncStatus.Idle, SyncStatus.Running)
        try {
            val pulled = step { sync.pullChanged(folder) }
            afterPull(pulled)
            val pushed = step { sync.pushPending(folder) }
            SyncResult((pulled.uploaded + pushed.uploaded).distinct(), (pulled.downloaded + pushed.downloaded).distinct(), pulled.failed || pushed.failed)
        } finally {
            if (running) _syncStatus.compareAndSet(SyncStatus.Running, SyncStatus.Idle)
        }
    }

    /** «Понятно» on the first-load failure message. */
    fun acknowledgeFirstLoadFailure() {
        _syncStatus.compareAndSet(SyncStatus.FirstLoadFailed, SyncStatus.Idle)
    }

    /** «Выйти» on the app's scope, which no closing screen cancels: unsent changes get their few seconds first. */
    fun logout() {
        background.launch { session.logout() }
    }

    /**
     * «Удалить аккаунт» on the app's scope: leaving the screen does not abandon a deletion the server may already have
     * done. Returns the error text (the account stays), or null once the account is gone.
     */
    suspend fun deleteAccount(): String? = background.async { session.deleteAccount() }.await()

    /**
     * After a sign-in: the account's server data is downloaded behind «Загружаем ваши данные…». The loader ends when the
     * download half is done or after [FIRST_LOAD_LIMIT], whichever comes first; uploads and the rest of the round go on
     * as an ordinary [SyncStatus.Running] round. The failure message is shown only when the download itself failed.
     */
    private fun startFirstLoad() {
        val load = Any()
        firstLoad.value = load
        _syncStatus.value = SyncStatus.FirstLoad
        _firstPullPending.value = true
        // Another account may be active by now: its status is not this load's to change.
        fun isCurrent() = firstLoad.value === load
        background.launch {
            val limit = launch {
                delay(FIRST_LOAD_LIMIT)
                if (isCurrent()) _syncStatus.compareAndSet(SyncStatus.FirstLoad, SyncStatus.Running)
            }
            val result = try {
                round { pulled -> if (isCurrent()) endFirstPull(pulled.failed) }
            } finally {
                limit.cancel()
            }
            if (isCurrent()) {
                // The round ended before its download half (nothing to sync for): the result decides.
                if (_syncStatus.value == SyncStatus.FirstLoad) endLoader(result.failed)
                _firstPullPending.value = false
                _syncStatus.compareAndSet(SyncStatus.Running, SyncStatus.Idle)
                firstLoad.value = null
            }
        }
    }

    /** The first load's download half is over and its documents are applied. The status moves on before the flag drops, so a reader never sees "not pending" next to the loader. */
    private fun endFirstPull(downloadFailed: Boolean) {
        endLoader(downloadFailed)
        _firstPullPending.value = false
    }

    /** The first load's download is done: the rest of its round runs as [SyncStatus.Running], or the failure is shown. */
    private fun endLoader(downloadFailed: Boolean) {
        if (downloadFailed) {
            // Running here is the first load itself (after the time limit): no other round runs meanwhile.
            _syncStatus.update { if (it == SyncStatus.FirstLoad || it == SyncStatus.Running) SyncStatus.FirstLoadFailed else it }
        } else {
            _syncStatus.compareAndSet(SyncStatus.FirstLoad, SyncStatus.Running)
        }
    }

    private suspend fun pushBeforeLogout() {
        if (isSyncable) exclusiveRound { folder -> step { sync.pushPending(folder) } }
    }

    /**
     * Runs [work] for the active account's folder once no other round is running. The round is stopped when the
     * account changes ([stopRound]): later requests would carry the next account's tokens.
     */
    private suspend fun exclusiveRound(work: suspend (folder: String) -> SyncResult): SyncResult {
        if (!isSyncable) return NOTHING
        return syncLock.withLock {
            if (!isSyncable) return@withLock NOTHING
            val folder = scope.folder()
            try {
                coroutineScope {
                    val round = async { work(folder) }
                    runningRound.value = round
                    round.await()
                }
            } catch (stopped: CancellationException) {
                // Only the round was stopped (account change): the caller goes on with a failed result.
                currentCoroutineContext().ensureActive()
                FAILED
            } finally {
                runningRound.value = null
            }
        }
    }

    private fun stopRound() {
        runningRound.value?.cancel()
    }

    /** One half of a round. An unexpected error (a file that cannot be written…) fails the round instead of reaching the caller. */
    private suspend fun step(half: suspend () -> SyncResult): SyncResult = try {
        half()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // Only the type: an exception's message may quote file contents.
        log("sync: round failed (${error::class.simpleName})")
        FAILED
    }

    /** A real player without «О тебе» answers: the step is shown once, on its own, at the next launch. */
    val needsAboutYou: Boolean
        get() {
            val user = session.user.value ?: return false
            return !user.role.isStaff && !user.isDemo && body.profile == null
        }

    /**
     * Saves «О тебе» and spreads it over the metrics: body profile, sleep window (reminders), fitness and nutrition
     * goals, screen-time limit. False, and nothing changes, while any answer is missing or out of range.
     */
    fun applyAboutYou(form: AboutYouForm, now: Instant = Clock.System.now()): Boolean {
        val profile = AboutYou.profile(form, now) ?: return false
        body.save(profile)
        fitness.bodyProfile = profile
        sleep.updateWindow(sleep.window.copy(bedtimeHour = form.bedtimeHour, bedtimeMinute = form.bedtimeMinute,
            wakeHour = form.wakeHour, wakeMinute = form.wakeMinute))
        fitness.updateGoals(FitnessGoals(form.activeMinutes, form.steps, form.activeKcal))
        nutrition.updateGoals(nutrition.goals.copy(kcal = form.foodKcal, waterMilliliters = form.waterMl))
        screenTime.setLimit(form.screenLimitHours * 60)
        refreshHealthForBody()
        return true
    }

    /** Профиль → «О тебе»: only birth date, height and weight change; goals and the sleep window stay as they are. */
    fun updateBody(form: AboutYouForm, now: Instant = Clock.System.now()): Boolean {
        val profile = AboutYou.profile(form, now) ?: return false
        body.save(profile)
        fitness.bodyProfile = profile
        refreshHealthForBody()
        return true
    }

    /** Zones and the kcal estimate are built with age and weight: rebuilt on the app's scope, which no closing screen cancels. */
    private fun refreshHealthForBody() {
        background.launch { fitness.refreshHealth() }
    }

    /** Personal weekly report inputs from every store (iOS `WeeklySource`); 70 days cover two compared weeks. */
    fun weeklyInputs(now: Instant = Clock.System.now(), days: Int = 70): WeeklyInputs {
        val user = session.user.value
        return WeeklyInputs(
            sleep = sleep.sessions,
            sleepGoalMinutes = sleep.window.durationMinutes,
            workouts = fitness.loadWorkouts(now),
            dailyLoad = fitness.dailyLoad(days, now),
            screenMinutes = screenTime.daily(days, now),
            screenLimitMinutes = screenTime.state.value.dailyLimitMinutes,
            kcal = nutrition.dailyCalories(days, now),
            kcalGoal = nutrition.goals.kcal,
            mood = wellbeing.moodEntries,
            cognitive = cognitive.results,
            matches = game.matches(user, days, now),
            elo = game.eloHistory(user, days, now),
            focus = survey.survey?.goals ?: emptyList()
        )
    }

    /** «Восстановление» inputs: personal norm = average of the previous days (today excluded). */
    fun recoveryInput(now: Instant = Clock.System.now()): com.pulse.core.model.RecoveryMath.Input {
        val night = sleep.displayNight(now)
        val energy = (wellbeing.averageMood(now) ?: wellbeing.averageMood(now.addingDays(-1)))?.energy
        val health = fitness.state.value.health
        if (health == null) {
            if (!sleep.state.value.isDemo) return com.pulse.core.model.RecoveryMath.Input(sleepScore = night?.score, energy = energy)
            return com.pulse.core.model.RecoveryMath.Input(
                hrv = (night?.hrv ?: 58).toDouble(), hrvBaseline = 55.0,
                restingHeartRate = (night?.restingHeartRate ?: 56).toDouble(), restingBaseline = 58.0,
                sleepScore = night?.score, energy = energy
            )
        }
        val previousHrv = health.hrvByDay.filter { !it.date.isSameDay(now) }.map { it.value }
        val previousRhr = health.restingHeartRateByDay.filter { !it.date.isSameDay(now) }.map { it.value }
        return com.pulse.core.model.RecoveryMath.Input(
            hrv = night?.hrv?.toDouble() ?: health.hrvByDay.lastOrNull()?.value,
            hrvBaseline = com.pulse.core.util.StatisticsMath.mean(previousHrv),
            restingHeartRate = night?.restingHeartRate?.toDouble() ?: health.restingHeartRate?.toDouble(),
            restingBaseline = com.pulse.core.util.StatisticsMath.mean(previousRhr),
            sleepScore = night?.score,
            energy = energy
        )
    }

    /** Advice under the recovery ring, combined with the training load. */
    fun recoveryAdvice(score: Int, now: Instant = Clock.System.now()): String {
        val load = fitness.loadRatio(now).status
        return when {
            score < 34 -> "Сегодня лучше лёгкая тренировка или отдых и ранний отбой."
            load == com.pulse.core.model.LoadStatus.HIGH -> "Восстановление в норме, но нагрузка резко выросла — не увеличивай объём."
            score >= 67 -> "Можно тренироваться в полную силу."
            else -> "Подойдёт тренировка средней интенсивности."
        }
    }

    /** The bell's inbox for the signed-in account. */
    fun inboxItems(now: Instant = Clock.System.now()): List<NotificationInbox.Item> = NotificationInbox.items(
        NotificationInbox.Context(
            isDemo = session.user.value?.isDemo ?: false,
            now = now,
            events = calendar.events,
            batteryDone = cognitive.completedToday(now),
            moodSlotsFilled = wellbeing.filledSlots(now).size,
            // Sleep is attributed to the wake-up day, so last night = a session ending today.
            sleptLastNight = sleep.minutes(now) != null,
            isSleeping = sleep.isSleeping,
            healthName = fitness.healthName
        )
    )

    /** «Дней подряд» shown next to the flame. */
    fun streakDays(now: Instant = Clock.System.now()): Int {
        val user = session.user.value
        if (user != null && user.isDemo) return user.streakDays
        val dates = wellbeing.moodEntries.map { it.day } + cognitive.results.map { it.date } + nutrition.meals.map { it.date } +
            sleep.sessions.map { it.end } + fitness.workouts.map { it.date }
        return ActivityStreak.days(dates.map { it.startOfDay() }.toSet(), now)
    }

    /** Профиль → Демо-режим → «Сбросить демо-данные» (demo accounts only): the demo folder is recreated. */
    fun resetDemoData() {
        val user = session.user.value ?: return
        if (!user.isDemo) return
        scope.erase(DataScope.id(user))
        activate(user, accountChanged = false)
    }

    private companion object {
        val NOTHING = SyncResult(emptyList(), emptyList(), failed = false)
        val FAILED = SyncResult(emptyList(), emptyList(), failed = true)
        /** Longest wait behind the first-load screen; a slower download goes on in the background. */
        val FIRST_LOAD_LIMIT = 20.seconds
        /** Every store's file, in reload order. */
        val storeFiles = listOf(
            SleepStore.FILE, NutritionStore.FILE, WellbeingStore.FILE, CognitiveStore.FILE, CalendarStore.FILE, FitnessStore.FILE,
            ScreenTimeStore.FILE, LineupStore.FILE, GameGoalsStore.FILE, GameStore.FILE, PlayerSurveyStore.FILE, BodyProfileStore.FILE
        )
    }
}

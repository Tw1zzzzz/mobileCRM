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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/** What the app shows about data sync: only the download right after sign-in has its own screen. */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    /** «Загружаем ваши данные…» after a sign-in. */
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
    backend: Backend = Backend.Local
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

    /** A real account signed in on the Pulse server (demo accounts and local builds never sync). */
    val isSyncable: Boolean get() = isRemote && session.user.value?.isDemo == false

    /** Platform hook, called after every save of a syncable account's file: debounce and schedule [syncNow]. */
    var onLocalChange: (() -> Unit)? = null

    /** One sync round at a time; a round never outlives its account (see [stopRound]). */
    private val syncLock = Mutex()
    private val runningRound = MutableStateFlow<Job?>(null)
    /** Stores are reloaded where the app's own code changes them (the main thread on Android). */
    private val storeContext = background.coroutineContext.minusKey(Job)

    init {
        // A session saved by a local build has no server tokens: sign it out before any store loads its folder.
        // Demo accounts stay local in every build.
        val stored = session.user.value
        if (isRemote && stored != null && !stored.isDemo && tokens.tokens == null) session.signOutNow()
        fitness.onHealthSnapshot = { sleep.applyHealth(it) }
        scope.onChange = { if (isSyncable) onLocalChange?.invoke() }
        session.beforeLogout = { pushBeforeLogout() }
        session.onUserChange = { user ->
            stopRound()
            activate(user, accountChanged = true)
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
        val isDemo = user?.isDemo ?: false
        sleep.reload(isDemo)
        nutrition.reload(isDemo)
        wellbeing.reload(isDemo)
        cognitive.reload(isDemo)
        calendar.reload(isDemo)
        fitness.reload(isDemo)
        screenTime.reload(isDemo)
        lineups.reload(isDemo)
        gameGoals.reload(isDemo)
        game.reload(isDemo)
        survey.reload(user)
        body.reload(user)
        fitness.bodyProfile = body.profile
    }

    /**
     * Downloads what changed on the server, then uploads unsent changes. The stores re-read every file the round
     * rewrote right after each half, so a stale in-memory copy never overwrites a merged file. Never throws for a
     * sync failure: it is reported as a failed result and the changes stay pending for the next round.
     */
    suspend fun syncNow(): SyncResult = exclusiveRound { folder ->
        val running = _syncStatus.compareAndSet(SyncStatus.Idle, SyncStatus.Running)
        try {
            val pulled = step(folder) { sync.pullChanged(folder) }
            val pushed = step(folder) { sync.pushPending(folder) }
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

    /** After a sign-in: the account's server data is downloaded behind «Загружаем ваши данные…». */
    private fun startFirstLoad() {
        val folder = scope.folder()
        _syncStatus.value = SyncStatus.FirstLoad
        background.launch {
            val result = syncNow()
            // Another account may be active by now: its status is not this round's to change.
            if (scope.folder() == folder) {
                _syncStatus.compareAndSet(SyncStatus.FirstLoad, if (result.failed) SyncStatus.FirstLoadFailed else SyncStatus.Idle)
            }
        }
    }

    private suspend fun pushBeforeLogout() {
        if (isSyncable) exclusiveRound { folder -> step(folder) { sync.pushPending(folder) } }
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

    /**
     * One half of a round. An unexpected error (a file that cannot be written…) fails the round instead of reaching
     * the caller; after it, or a cancellation, the files may have changed too, so the stores re-read them.
     */
    private suspend fun step(folder: String, half: suspend () -> SyncResult): SyncResult {
        var result: SyncResult? = null
        try {
            result = half()
            return result
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return FAILED
        } finally {
            if (result == null || result.downloaded.isNotEmpty()) reloadAfterSync(folder)
        }
    }

    private suspend fun reloadAfterSync(folder: String) = withContext(storeContext + NonCancellable) {
        // After an account switch the stores already read another folder.
        if (scope.folder() == folder) reloadStores(session.user.value)
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
    }
}

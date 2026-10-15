package com.pulse.core

import com.pulse.core.api.ApiClient
import com.pulse.core.api.AppConfig
import com.pulse.core.api.RemoteAuthService
import com.pulse.core.api.SyncEngine
import com.pulse.core.api.TokenStore
import com.pulse.core.auth.AuthService
import com.pulse.core.auth.LocalAuthService
import com.pulse.core.auth.SessionStore
import com.pulse.core.demo.PlayerSurveyState
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
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/**
 * Composition root shared by the platforms (Kotlin twin of iOS `AppDependencies`): owns every store, points them
 * at the active account's folder and reloads them on login, logout and demo switches.
 */
class PulseCore(
    files: FileSystem,
    secrets: SecretStore,
    http: HttpClient,
    background: CoroutineScope,
    health: HealthDataProvider?,
    foodAnalyzer: FoodAnalyzer = MockFoodAnalyzer()
) {
    val scope = DataScope(files)
    private val api = ApiClient(http, AppConfig.apiBaseUrl, TokenStore(secrets))

    private fun <T> file(name: String, serializer: KSerializer<T>, global: Boolean = false) = JsonFileStorage(name, serializer, scope, global)

    /** Local accounts in the first release; the Pulse API once `AppConfig.backend` points to the server. */
    val auth: AuthService = if (AppConfig.apiBaseUrl == null) {
        LocalAuthService(file("accounts.json", ListSerializer(LocalAuthService.Account.serializer()), global = true), secrets)
    } else {
        RemoteAuthService(api, background)
    }
    val sync = SyncEngine(api, scope)
    val session = SessionStore(auth, file("session.json", User.serializer(), global = true), scope)

    val sleep = SleepStore(file(SleepStore.FILE, SleepStore.Saved.serializer()))
    val nutrition = NutritionStore(file(NutritionStore.FILE, NutritionStore.Saved.serializer()), foodAnalyzer)
    val wellbeing = WellbeingStore(file(WellbeingStore.FILE, WellbeingStore.Saved.serializer()))
    val cognitive = CognitiveStore(file(CognitiveStore.FILE, CognitiveStore.Saved.serializer()))
    val calendar = CalendarStore(file(CalendarStore.FILE, ListSerializer(CalendarEvent.serializer())))
    val fitness = FitnessStore(file(FitnessStore.FILE, FitnessStore.Saved.serializer()), health)
    val screenTime = ScreenTimeStore(file(ScreenTimeStore.FILE, ScreenTimeStore.Saved.serializer()))
    val lineups = LineupStore(file(LineupStore.FILE, ListSerializer(Lineup.serializer())))
    val gameGoals = GameGoalsStore(file(GameGoalsStore.FILE, GameGoals.serializer()))
    val game = GameStore(file(GameStore.FILE, GameStore.Saved.serializer()), secrets) { key -> FaceitClient(key, http) }
    val survey = PlayerSurveyStore(file(PlayerSurveyStore.FILE, PlayerSurveyState.serializer()))

    /** Platform hook: reschedule reminders, stop the sleep notification of the previous account, etc. */
    var onAccountActivated: ((User?, Boolean) -> Unit)? = null

    init {
        fitness.onHealthSnapshot = { sleep.applyHealth(it) }
        session.onUserChange = { activate(it, accountChanged = true) }
        activate(session.user.value, accountChanged = false)
    }

    /**
     * Points every store at the account's own folder: demo accounts get seeded data, real accounts start empty.
     */
    fun activate(user: User?, accountChanged: Boolean) {
        scope.current = user?.let(DataScope::id) ?: "guest"
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
        onAccountActivated?.invoke(user, accountChanged)
    }

    /** Профиль → Демо-режим → «Сбросить демо-данные» (demo accounts only): the demo folder is recreated. */
    fun resetDemoData() {
        val user = session.user.value ?: return
        if (!user.isDemo) return
        scope.erase(DataScope.id(user))
        activate(user, accountChanged = false)
    }
}

import SwiftUI
import UserNotifications

/// Composition root: owns all stores so the app and App Intents share the same instances.
@MainActor
final class AppDependencies {
    static let shared = AppDependencies()

    /// Local accounts in the first release; the Pulse API once `AppConfig.backend` points to the server.
    let auth: AuthService = AppConfig.apiBaseURL == nil ? LocalAuthService() : RemoteAuthService()
    let sync = SyncEngine()
    let session: SessionStore
    let sleep = SleepStore()
    let nutrition = NutritionStore()
    let wellbeing = WellbeingStore()
    let cognitive = CognitiveStore()
    let calendar = CalendarStore()
    let fitness = FitnessStore()
    let screenTime = ScreenTimeStore()
    let lineups = LineupStore()
    let gameGoals = GameGoalsStore()
    let game = GameStore()
    let survey = PlayerSurveyStore()
    let body = BodyProfileStore()
    let deepLinks = DeepLinkCenter()

    private init() {
        session = SessionStore(authService: auth)
        sleep.onTrackingChange = { start, window in
            SleepTrackingCoordinator.trackingChanged(activeStart: start, window: window)
        }
        fitness.onHealthSnapshot = { [sleep] snapshot in
            sleep.applyHealth(snapshot)
        }
        sleep.onWindowChange = { window in
            Task { await SleepNotifications.scheduleBedtimeReminder(window: window) }
        }
        session.onUserChange = { [weak self] user in
            self?.activate(user, accountChanged: true)
        }
        // Files written before accounts existed belong to whoever is signed in now.
        if let user = session.user { DataScope.migrateLegacyFiles(into: DataScope.id(for: user)) }
        activate(session.user, accountChanged: false)
    }

    /// A real player without «О тебе» answers: the step is shown once, on its own, at the next launch.
    var needsAboutYou: Bool { AboutYou.needsStep(user: session.user, profile: body.profile) }

    /// Saves «О тебе» and spreads it over the metrics (the sleep window change reschedules «Скоро отбой»).
    @discardableResult
    func applyAboutYou(_ form: AboutYouForm) -> Bool {
        AboutYou.apply(form, body: body, sleep: sleep, fitness: fitness, nutrition: nutrition, screenTime: screenTime)
    }

    /// Профиль → «О тебе»: body only.
    @discardableResult
    func updateBody(_ form: AboutYouForm) -> Bool {
        AboutYou.updateBody(form, body: body, fitness: fitness)
    }

    /// Points every store at the account's own folder: demo accounts get seeded data, real accounts
    /// start empty. On a switch, reminders and the lock-screen timer of the previous account are cleared.
    func activate(_ user: User?, accountChanged: Bool) {
        DataScope.current = user.map(DataScope.id(for:)) ?? "guest"
        let isDemo = user?.isDemo ?? false
        sleep.reload(isDemo: isDemo)
        nutrition.reload(isDemo: isDemo)
        wellbeing.reload(isDemo: isDemo)
        cognitive.reload(isDemo: isDemo)
        calendar.reload(isDemo: isDemo)
        fitness.reload(isDemo: isDemo)
        screenTime.reload(isDemo: isDemo)
        lineups.reload(isDemo: isDemo)
        gameGoals.reload(isDemo: isDemo)
        game.reload(isDemo: isDemo)
        survey.reload(user: user)
        body.reload(user: user)
        fitness.bodyProfile = body.profile
        guard accountChanged else { return }
        UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
        SleepTrackingCoordinator.sync(store: sleep)
        guard user != nil, user?.role.isStaff == false else { return }
        let events = calendar.events
        let window = sleep.window
        Task {
            await NotificationScheduler.scheduleGameTests(for: events)
            await SleepNotifications.scheduleBedtimeReminder(window: window)
            await WeeklyNotifications.schedule()
            for event in events where event.start > .now { await NotificationScheduler.scheduleReminder(for: event) }
        }
    }

    /// Профиль → Демо-режим → «Сбросить демо-данные» (demo accounts only): the demo folder is recreated.
    func resetDemoData() {
        guard let user = session.user, user.isDemo else { return }
        DataScope.erase(DataScope.id(for: user))
        activate(user, accountChanged: false)
    }
}

extension View {
    func injectDependencies(_ deps: AppDependencies) -> some View {
        environment(deps.session)
            .environment(deps.sleep)
            .environment(deps.nutrition)
            .environment(deps.wellbeing)
            .environment(deps.cognitive)
            .environment(deps.calendar)
            .environment(deps.fitness)
            .environment(deps.screenTime)
            .environment(deps.lineups)
            .environment(deps.gameGoals)
            .environment(deps.game)
            .environment(deps.survey)
            .environment(deps.body)
            .environment(deps.deepLinks)
    }
}

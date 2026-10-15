import SwiftUI
import Observation

enum AppTab: String, CaseIterable, Identifiable {
    case today, state, diary, profile
    case players, analytics, lineups

    var id: String { rawValue }

    var title: String {
        switch self {
        case .today: "Сегодня"
        case .state: "Состояние"
        case .diary: "Дневник"
        case .profile: "Профиль"
        case .players: "Игроки"
        case .analytics: "Аналитика"
        case .lineups: "База знаний"
        }
    }

    var icon: String {
        switch self {
        case .today: "house"
        case .state: "chart.bar.fill"
        case .diary: "book.closed"
        case .profile: "person"
        case .players: "person.3"
        case .analytics: "chart.xyaxis.line"
        case .lineups: "scope"
        }
    }

    /// Player and staff get different app versions (ТЗ: «Определение роли»).
    /// Staff have nothing to log, so instead of the central «+» they get five equal tabs.
    static func tabs(for role: UserRole?) -> [AppTab] {
        role?.isStaff == true
            ? [.today, .players, .analytics, .lineups, .profile]
            : [.today, .state, .diary, .profile]
    }

    /// Whether the central «+» (quick add) is shown — only players log data.
    static func showsQuickAdd(for role: UserRole?) -> Bool {
        role?.isStaff != true
    }

    /// Tab hosting section screens (Сон, Тесты, Календарь…) for the role.
    static func hub(for role: UserRole?) -> AppTab {
        role?.isStaff == true ? .today : .state
    }
}

enum Route: Hashable {
    case sleep, sleepRecovery
    case fitness, fitnessWeek
    case screenTime, screenTimeApps
    case tests, cognitive, mood, balance, gameTests
    case statistics
    case calendar(createNew: Bool)
    case event(UUID)
    case players, player(UUID)
    case nutrition, nutritionHistory
    case lineups, lineup(UUID)
    case teamAnalytics, teamCorrelations
    case gameStats, teamFood
    case weeklyReport
    case playerPortrait
}

@Observable
final class AppRouter {
    var selectedTab: AppTab = .today
    private(set) var paths: [AppTab: [Route]] = [:]
    var pendingFoodCapture = false
    /// Re-opens the first-login questionnaire (reminder card, profile).
    var showsSurvey = false

    func path(for tab: AppTab) -> [Route] {
        paths[tab] ?? []
    }

    func setPath(_ routes: [Route], for tab: AppTab) {
        paths = paths.merging([tab: routes]) { _, new in new }
    }

    func open(_ route: Route, in tab: AppTab = .state) {
        selectedTab = tab
        setPath(path(for: tab) + [route], for: tab)
    }

    /// Back to the tab's first screen (second tap on an open tab).
    func popToRoot(_ tab: AppTab) {
        guard !path(for: tab).isEmpty else { return }
        setPath([], for: tab)
    }

    /// Opens a section of «Состояние» (e.g. «Питание») at the hub root.
    func openStateSection(_ section: StateSection) {
        UserDefaults.standard.set(section.rawValue, forKey: StateSection.storageKey)
        show([], in: .state)
    }

    /// Resets a tab to its root and pushes the given stack.
    func show(_ routes: [Route], in tab: AppTab) {
        selectedTab = tab
        setPath(routes, for: tab)
    }
}

/// Bridges notification taps (outside SwiftUI) into navigation.
@Observable
final class DeepLinkCenter {
    var pendingEventID: UUID?
    var pendingGameTest: GameTestPhase?
    /// Sleep notifications open «Сон».
    var pendingSleep = false
    /// «Итоги недели» notification opens the weekly report.
    var pendingWeekly = false
}

/// Single place mapping routes to screens, shared by all tab stacks.
struct RouteDestination: View {
    let route: Route

    var body: some View {
        switch route {
        case .sleep: SleepView()
        case .sleepRecovery: SleepRecoveryView()
        case .fitness: FitnessView()
        case .fitnessWeek: FitnessWeekView()
        case .screenTime: ScreenTimeView()
        case .screenTimeApps: ScreenTimeAppsView()
        case .tests: TestsView()
        case .cognitive: CognitiveHubView()
        case .mood: MoodEnergyView()
        case .balance: BalanceWheelView()
        case .gameTests: GameTestsView()
        case .statistics: StatisticsView()
        case .calendar(let createNew): CalendarScreen(startsWithCreate: createNew)
        case .event(let id): EventDetailView(eventID: id)
        case .players: PlayersView()
        case .player(let id): PlayerDetailView(playerID: id)
        case .nutrition: NutritionView()
        case .nutritionHistory: NutritionHistoryView()
        case .lineups: LineupsView()
        case .lineup(let id): LineupDetailView(lineupID: id)
        case .teamAnalytics: TeamAnalyticsView()
        case .teamCorrelations: TeamCorrelationsView()
        case .gameStats: GameStatsView()
        case .teamFood: TeamFoodView()
        case .weeklyReport: WeeklyReportView()
        case .playerPortrait: PlayerPortraitView()
        }
    }
}

import SwiftUI

/// Вкладка «Игроки» стаффа: состав команды и лента питания.
struct StaffPlayersTab: View {
    enum Section: String, CaseIterable, Identifiable {
        case roster, food

        var id: String { rawValue }
        var title: String { self == .roster ? "Состав" : "Питание" }
    }

    @Environment(SessionStore.self) private var session
    @AppStorage("staff.players.section") private var stored = Section.roster.rawValue

    var body: some View {
        let section = Binding(get: { Section(rawValue: stored) ?? .roster }, set: { stored = $0.rawValue })
        ScreenScaffold(spacing: 18) {
            VStack(spacing: 14) {
                NavHeader(title: "Игроки", subtitle: "\(session.user?.team ?? "Команда") · \(DemoData.players.count) в составе",
                          showsBack: false)
                PulseSegmented(options: Section.allCases, selection: section, title: { $0.title }, height: 38)
            }
        } content: {
            switch section.wrappedValue {
            case .roster: PlayersListContent()
            case .food: TeamFoodContent()
            }
        }
        .animation(.easeInOut(duration: 0.2), value: stored)
    }
}

/// Вкладка «Аналитика» стаффа: дашборд команды и корреляционный анализ.
struct StaffAnalyticsTab: View {
    enum Section: String, CaseIterable, Identifiable {
        case team, correlations

        var id: String { rawValue }
        var title: String { self == .team ? "Команда" : "Корреляции" }
    }

    @AppStorage("staff.analytics.section") private var stored = Section.team.rawValue

    var body: some View {
        let section = Binding(get: { Section(rawValue: stored) ?? .team }, set: { stored = $0.rawValue })
        ScreenScaffold(spacing: 22) {
            VStack(spacing: 14) {
                NavHeader(title: "Аналитика", subtitle: "Состояние и игра команды", showsBack: false)
                PulseSegmented(options: Section.allCases, selection: section, title: { $0.title }, height: 38)
            }
        } content: {
            switch section.wrappedValue {
            case .team: TeamAnalyticsContent()
            case .correlations: TeamCorrelationsContent()
            }
        }
        .animation(.easeInOut(duration: 0.2), value: stored)
    }
}

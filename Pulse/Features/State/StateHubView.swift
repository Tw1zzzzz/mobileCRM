import SwiftUI

/// «Состояние»: четыре раздела вместо длинного списка — на экране только один.
/// Здоровье (сон, фитнес, экран) · Тесты (ментальное) · Игра (CS2, раскидки) · Аналитика (статистика, корреляции).
enum StateSection: String, CaseIterable, Identifiable {
    case body, statistics, tests, game, analytics

    static let storageKey = "state.section"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .body: "Здоровье"
        case .tests: "Тесты"
        case .game: "Игра"
        case .statistics: "Статистика"
        case .analytics: "Аналитика"
        }
    }
}

struct StateHubView: View {
    @Environment(SessionStore.self) private var session
    @AppStorage(StateSection.storageKey) private var storedSection = StateSection.body.rawValue

    private var section: Binding<StateSection> {
        Binding(
            get: { StateSection(rawValue: storedSection) ?? .body },
            set: { storedSection = $0.rawValue }
        )
    }

    var body: some View {
        ScreenScaffold(spacing: 20) {
            VStack(spacing: 14) {
                NavHeader(title: "Состояние", subtitle: hubSubtitle, showsBack: false)
                PulseScrollTabs(options: StateSection.allCases, selection: section, title: { $0.title })
            }
        } content: {
            switch section.wrappedValue {
            case .body: BodySectionView()
            case .tests: TestsSectionView()
            case .game: GameSectionView()
            case .statistics: StatisticsContent()
            case .analytics: AnalyticsSectionView()
            }
        }
        .animation(.easeInOut(duration: 0.2), value: storedSection)
    }

    private var hubSubtitle: String {
        guard let user = session.user else { return "" }
        return [user.role.title, user.team].compactMap { $0 }.joined(separator: " · ")
    }
}

/// Card with a key value and a 7-day sparkline; opens the detailed screen.
struct StateMetricCard<Trailing: View>: View {
    let icon: String
    let tint: Color
    let title: String
    let value: String
    let caption: String
    let route: Route
    @ViewBuilder var trailing: Trailing
    @Environment(AppRouter.self) private var router

    var body: some View {
        Button {
            router.open(route, in: router.selectedTab)
        } label: {
            HStack(spacing: 14) {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 10) {
                        IconBadge(systemName: icon, tint: tint, size: 34, iconSize: 15)
                        Text(title).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                            .lineLimit(2).minimumScaleFactor(0.85)
                    }
                    Text(value)
                        .font(.system(size: 24, weight: .bold))
                        .foregroundStyle(Theme.Palette.textPrimary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    Text(caption).font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
                        .lineLimit(2).minimumScaleFactor(0.85)
                }
                Spacer(minLength: 8)
                trailing
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Theme.Palette.textTertiary)
            }
            .pulseCard(padding: 16)
        }
        .buttonStyle(.pressable)
    }
}

// MARK: - Здоровье

private struct BodySectionView: View {
    @Environment(SleepStore.self) private var sleep
    @Environment(FitnessStore.self) private var fitness
    @Environment(ScreenTimeStore.self) private var screenTime
    @Environment(NutritionStore.self) private var nutrition

    var body: some View {
        let night = sleep.displayNight
        let totals = nutrition.totals(on: .now)
        let sleepWeek = (0..<7).reversed().compactMap { offset -> DailyValue? in
            let day = Date.now.startOfDay.adding(days: -offset)
            return sleep.minutes(on: day).map { DailyValue(date: day, value: Double($0) / 60) }
        }
        VStack(spacing: 12) {
            StateMetricCard(icon: "moon", tint: Theme.Palette.purple, title: "Сон",
                            value: sleep.isSleeping ? "Сон активен" : night.map { Formatters.duration(minutes: $0.totalMinutes) } ?? "Нет данных",
                            caption: "Среднее за неделю \(Formatters.duration(minutes: sleep.averageMinutes(lastDays: 7) ?? 0))",
                            route: .sleep) {
                Sparkline(values: sleepWeek, color: Theme.Palette.purple).frame(width: 76, height: 40)
            }
            StateMetricCard(icon: "figure.run", tint: Theme.Palette.orange, title: "Фитнес",
                            value: "\(fitness.activeMinutesToday) / \(fitness.activeMinutesGoal) мин",
                            caption: "Активные минуты · \(Formatters.grouped(fitness.steps)) шагов", route: .fitness) {
                Sparkline(values: fitness.weeklyActiveMinutes, color: Theme.Palette.orange).frame(width: 76, height: 40)
            }
            StateMetricCard(icon: "iphone", tint: Theme.Palette.cyan, title: "Экранное время",
                            value: Formatters.duration(minutes: screenTime.todayMinutes),
                            caption: "Лимит \(screenTime.dailyLimitMinutes / 60) ч · \(Int((screenTime.limitProgress * 100).rounded()))%",
                            route: .screenTime) {
                Sparkline(values: screenTime.week.map { DailyValue(date: $0.date, value: Double($0.minutes) / 60) },
                          color: Theme.Palette.cyan)
                    .frame(width: 76, height: 40)
            }
            StateMetricCard(icon: "fork.knife", tint: Theme.Palette.green, title: "Питание",
                            value: "\(Formatters.grouped(totals.kcal)) / \(Formatters.grouped(nutrition.goals.kcal)) ккал",
                            caption: "Б \(totals.protein) · Ж \(totals.fat) · У \(totals.carbs) г · вода \(Formatters.liters(milliliters: nutrition.water(on: .now))) л",
                            route: .nutrition) {
                Sparkline(values: nutrition.dailyCalories(lastDays: 7), color: Theme.Palette.green).frame(width: 76, height: 40)
            }
        }
    }
}

// MARK: - Тесты

private struct TestsSectionView: View {
    @Environment(CognitiveStore.self) private var cognitive
    @Environment(WellbeingStore.self) private var wellbeing

    var body: some View {
        let scores = cognitive.dailyScores(lastDays: 7)
        let filled = wellbeing.filledSlots(on: .now)
        VStack(alignment: .leading, spacing: 20) {
            PulseSection(title: "Ежедневно") {
                VStack(spacing: 12) {
                    StateMetricCard(icon: "brain.head.profile", tint: Theme.Palette.green, title: "Когнитивные тесты",
                                    value: scores.last.map { "\(Int($0.value)) баллов" } ?? "Нет данных",
                                    caption: cognitive.completedToday ? "Батарея пройдена сегодня" : "Пройди батарею сегодня",
                                    route: .cognitive) {
                        Sparkline(values: scores, color: Theme.Palette.green).frame(width: 76, height: 40)
                    }
                    StateMetricCard(icon: "face.smiling", tint: Theme.Palette.yellow, title: "Настроение и энергия",
                                    value: wellbeing.averageMood(on: .now).map { "\(Formatters.decimal($0.mood)) · \(Formatters.decimal($0.energy))" } ?? "—",
                                    caption: "Заполнено \(filled.count) из 3", route: .mood) {
                        HStack(spacing: 4) {
                            ForEach(DaySlot.allCases) { slot in
                                Capsule().fill(filled.contains(slot) ? Theme.Palette.cyan : Theme.Palette.track)
                                    .frame(width: 8, height: 26)
                            }
                        }
                    }
                    StateMetricCard(icon: "gamecontroller", tint: Theme.Palette.cyan, title: "Игровые тесты",
                                    value: cognitive.gameResults.first.map { "Готовность \($0.readiness)/10" } ?? "Нет данных",
                                    caption: cognitive.gameResults.first?.phase.title ?? "До и после тренировки",
                                    route: .gameTests) { EmptyView() }
                }
            }
            PulseSection(title: "Еженедельно") {
                VStack(spacing: 12) {
                    StateMetricCard(icon: "circle.hexagongrid", tint: Theme.Palette.pink, title: "Колесо баланса",
                                    value: wellbeing.latestBalance.map { "\(Formatters.decimal($0.average)) из 10" } ?? "Не заполнено",
                                    caption: wellbeing.latestBalance.map { "Последнее: \(Formatters.dayMonth($0.day))" } ?? "8 сфер жизни",
                                    route: .balance) {
                        if let entry = wellbeing.latestBalance {
                            RadarChart(labels: BalanceCategory.allCases.map(\.shortTitle), values: entry.orderedValues, showsLabels: false)
                                .frame(width: 64, height: 64)
                        }
                    }
                    StateMetricCard(icon: "checklist", tint: Theme.Palette.textSecondary, title: "Еженедельные тесты",
                                    value: "Скоро", caption: "Анкеты из веб-версии — с подключением API",
                                    route: .tests) { EmptyView() }
                }
            }
        }
    }
}

// MARK: - Игра

private struct GameSectionView: View {
    @Environment(SessionStore.self) private var session
    @Environment(AppRouter.self) private var router
    @State private var showGoals = false
    @Environment(GameStore.self) private var game
    var body: some View {
        let player = game.profile(for: session.user)
        let recent = game.recentMatches(for: session.user, count: 3)
        VStack(alignment: .leading, spacing: 20) {
            FaceitSourceCard()
            if game.hasData {
                GameSummaryCard(player: player) { router.open(.gameStats, in: router.selectedTab) }
                PulseSection(title: "Цели", actionTitle: "Изменить") {
                    showGoals = true
                } content: {
                    GameGoalsCard { showGoals = true }
                }
                PulseSection(title: "Последние матчи", actionTitle: "Все") {
                    router.open(.gameStats, in: router.selectedTab)
                } content: {
                    VStack(spacing: 0) {
                        ForEach(Array(recent.enumerated()), id: \.element.id) { index, match in
                            MatchRow(match: match)
                            if index < recent.count - 1 { DividerLine() }
                        }
                    }
                    .padding(.horizontal, 16)
                    .pulseCard(padding: 0)
                }
            }
        }
        .sheet(isPresented: $showGoals) { GameGoalsSheet().presentationDetents([.large]) }
    }
}

// MARK: - Аналитика

private struct AnalyticsSectionView: View {
    @Environment(AppRouter.self) private var router
    @Environment(SleepStore.self) private var sleep
    @Environment(NutritionStore.self) private var nutrition
    @Environment(WellbeingStore.self) private var wellbeing
    @Environment(CognitiveStore.self) private var cognitive
    @Environment(ScreenTimeStore.self) private var screenTime

    var body: some View {
        let data = StatisticsData(period: .days(30), sleep: sleep, nutrition: nutrition, wellbeing: wellbeing,
                                  cognitive: cognitive, screenTime: screenTime)
        let top = Array(data.correlations.sorted { abs($0.value) > abs($1.value) }.prefix(3))
        VStack(alignment: .leading, spacing: 20) {
            StateMetricCard(icon: "face.smiling", tint: Theme.Palette.yellow, title: "Настроение",
                            value: StatisticsMath.mean(data.mood.map(\.value)).map { "\(Formatters.decimal($0)) из 10" } ?? "—",
                            caption: "Энергия \(StatisticsMath.mean(data.energy.map(\.value)).map { Formatters.decimal($0) } ?? "—") · за 30 дней",
                            route: .statistics) {
                Sparkline(values: Array(data.mood.suffix(14)), color: Theme.Palette.yellow).frame(width: 76, height: 40)
            }
            StateMetricCard(icon: "brain.head.profile", tint: Theme.Palette.green, title: "Когнитивная форма",
                            value: StatisticsMath.mean(data.cognitive.map(\.value)).map { "\(Int($0)) баллов" } ?? "—",
                            caption: "Среднее за 30 дней", route: .statistics) {
                Sparkline(values: Array(data.cognitive.suffix(14)), color: Theme.Palette.green).frame(width: 76, height: 40)
            }
            PulseSection(title: "Главные связи · 30 дней") { CorrelationTable(rows: top) }
            HubRow(icon: "chart.xyaxis.line", tint: Theme.Palette.blue, title: "Вся статистика",
                   subtitle: "Периоды, графики, неигровой и игровой анализ", route: .statistics) {
                router.openStateSection(.statistics)
            }
        }
    }
}

struct HubRow: View {
    let icon: String
    let tint: Color
    let title: String
    let subtitle: String
    let route: Route
    /// Overrides the default push of `route`.
    var action: (() -> Void)?
    @Environment(AppRouter.self) private var router

    var body: some View {
        Button {
            if let action { action() } else { router.open(route, in: router.selectedTab) }
        } label: {
            HStack(spacing: 16) {
                IconBadge(systemName: icon, tint: tint, size: 52, iconSize: 22)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.system(size: 18, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                    Text(subtitle).font(.system(size: 14)).foregroundStyle(Theme.Palette.textSecondary)
                        .lineLimit(1).minimumScaleFactor(0.8)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.Palette.textTertiary)
            }
            .pulseCard(padding: 14)
        }
        .buttonStyle(.pressable)
    }
}

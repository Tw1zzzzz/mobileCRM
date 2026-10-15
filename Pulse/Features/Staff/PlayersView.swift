import SwiftUI
import Charts

/// Staff: список игроков команды.
struct PlayersListContent: View {
    @Environment(AppRouter.self) private var router
    @State private var query = ""

    private var players: [PlayerProfile] {
        let trimmed = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !trimmed.isEmpty else { return DemoData.players }
        return DemoData.players.filter {
            $0.nickname.lowercased().contains(trimmed) || $0.realName.lowercased().contains(trimmed)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 26) {
            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass").foregroundStyle(Theme.Palette.textSecondary)
                TextField("", text: $query, prompt: Text("Поиск игрока").foregroundStyle(Theme.Palette.textTertiary))
                    .foregroundStyle(Theme.Palette.textPrimary)
                    .autocorrectionDisabled()
            }
            .pulseTile(padding: 14)
            VStack(spacing: 12) {
                ForEach(players) { player in
                    Button { router.open(.player(player.id), in: router.selectedTab) } label: { row(player) }
                        .buttonStyle(.pressable)
                }
            }
        }
    }

    private func row(_ player: PlayerProfile) -> some View {
        HStack(spacing: 14) {
            Text(String(player.nickname.prefix(2)).uppercased())
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 52, height: 52)
                .background(Circle().fill(Theme.Gradients.accentDiagonal))
            let snapshot = TeamAnalytics.snapshot(for: player)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(player.nickname).font(.system(size: 18, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                    if snapshot.needsAttention {
                        Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 12)).foregroundStyle(Theme.Palette.orange)
                    }
                }
                Text("\(player.gameRole) · \(relative(player.lastActive))")
                    .font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
                    .lineLimit(1)
                if let archetype = PlayerSurveyDemo.archetype(for: player.nickname) {
                    Label("\(archetype.caption): \(archetype.title.lowercased())", systemImage: archetype.icon)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(archetype.tint)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
                Text("Готовность \(Formatters.decimal(snapshot.readiness)) · сон \(Formatters.duration(minutes: snapshot.sleepMinutes))")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(snapshot.needsAttention ? Theme.Palette.orange : Theme.Palette.green)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text("\(player.elo)").font(.system(size: 18, weight: .bold)).foregroundStyle(Theme.Palette.cyan)
                Text("ELO").font(.system(size: 11, weight: .semibold)).foregroundStyle(Theme.Palette.textTertiary)
            }
        }
        .pulseCard(padding: 14)
    }

    private func relative(_ date: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.locale = Formatters.locale
        formatter.unitsStyle = .short
        return formatter.localizedString(for: date, relativeTo: .now)
    }
}

/// «Экран администратора»: полная статистика выбранного игрока.
struct PlayerDetailView: View {
    let playerID: UUID

    @Environment(NutritionStore.self) private var nutrition
    @State private var period = 30
    @State private var testType = "Все тесты"
    @State private var source = "Все"
    @State private var testsDay: Date?

    private var player: PlayerProfile? { DemoData.players.first { $0.id == playerID } }

    var body: some View {
        ScreenScaffold {
            NavHeader(title: player?.nickname ?? "Игрок", subtitle: player.map { "\($0.realName) · \($0.gameRole)" })
        } content: {
            if let player {
                infoCard(player)
                filters
                summaryGrid(player)
                StaffPlayerPortraitSection(nickname: player.nickname)
                PulseSection(title: "Тренировочная нагрузка") { loadCard(player) }
                PulseSection(title: "Результаты тестов") { testsCard(player) }
                PulseSection(title: "Последние матчи") { recentMatches(player) }
                PulseSection(title: "Игровая статистика · \(period) дней") { PlayerGameStatsView(stats: GameData.stats(for: player, days: period)) }
                PulseSection(title: "Корреляционный анализ") { CorrelationTable(rows: TeamAnalytics.correlations(for: player)) }
                PulseSection(title: "Колесо баланса") { balanceCard(player) }
                PulseSection(title: "Дневник сна") { sleepDiary(player) }
                PulseSection(title: "Дневник питания") { nutritionDiary(player) }
            } else {
                EmptyStateView(systemName: "person.crop.circle.badge.questionmark", title: "Игрок не найден", message: "")
            }
        }
    }

    private func loadCard(_ player: PlayerProfile) -> some View {
        let daily = TeamAnalytics.dailyLoad(for: player)
        let ratio = TrainingLoadMath.ratio(daily: daily)
        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(ratio.value.map { Formatters.decimal($0, fractionDigits: 2) } ?? "—")
                    .font(.system(size: 32, weight: .bold))
                    .foregroundStyle(ratio.status.color)
                Text("ACWR").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.Palette.textSecondary)
                Spacer()
                Text(ratio.status.title)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(ratio.status.color)
                    .padding(.horizontal, 10).padding(.vertical, 5)
                    .background(Capsule().fill(ratio.status.color.opacity(0.15)))
            }
            Text("За 7 дней \(Formatters.grouped(Int(ratio.acute))) ед. · обычно \(Formatters.grouped(Int(ratio.chronicWeekly))) ед. в неделю")
                .font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
            Text(ratio.status.advice).font(.system(size: 14)).foregroundStyle(Theme.Palette.textSecondary)
        }
        .pulseCard(padding: 16)
    }

    private func infoCard(_ player: PlayerProfile) -> some View {
        HStack(spacing: 16) {
            Text(String(player.nickname.prefix(2)).uppercased())
                .font(.system(size: 24, weight: .bold)).foregroundStyle(.white)
                .frame(width: 68, height: 68)
                .background(Circle().fill(Theme.Gradients.accentDiagonal))
            VStack(alignment: .leading, spacing: 5) {
                Text(player.nickname).font(.system(size: 22, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
                Text("Роль: \(player.gameRole)").font(.system(size: 15)).foregroundStyle(Theme.Palette.textSecondary)
                Text("Последняя активность: \(Formatters.dayMonthTime(player.lastActive))")
                    .font(.system(size: 13)).foregroundStyle(Theme.Palette.textTertiary)
            }
            Spacer(minLength: 0)
        }
        .pulseCard()
    }

    private var filters: some View {
        VStack(alignment: .leading, spacing: 10) {
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ForEach([7, 30, 90], id: \.self) { days in
                        ChipButton(title: "\(days) дней", isSelected: period == days) { withAnimation { period = days } }
                    }
                    Menu {
                        ForEach(["Все тесты", "Когнитивные", "Игровые", "Настроение"], id: \.self) { item in
                            Button(item) { testType = item }
                        }
                    } label: { filterLabel(testType) }
                    Menu {
                        ForEach(["Все", "Приложение", "Уведомления", "Веб"], id: \.self) { item in
                            Button(item) { source = item }
                        }
                    } label: { filterLabel("Источник: \(source)") }
                }
            }
            .scrollIndicators(.hidden)
        }
    }

    private func filterLabel(_ text: String) -> some View {
        HStack(spacing: 6) {
            Text(text)
            Image(systemName: "chevron.down").font(.system(size: 11, weight: .bold))
        }
        .font(.system(size: 15, weight: .semibold))
        .foregroundStyle(Theme.Palette.textSecondary)
        .padding(.horizontal, 14).padding(.vertical, 9)
        .background(Capsule().fill(Theme.Palette.tile))
        .overlay(Capsule().strokeBorder(Theme.Palette.stroke, lineWidth: 1))
    }

    private func summaryGrid(_ player: PlayerProfile) -> some View {
        let snapshot = TeamAnalytics.snapshot(for: player, days: period)
        let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]
        return LazyVGrid(columns: columns, spacing: 12) {
            summaryTile("Средний score", "\(snapshot.averageScore)", Theme.Palette.cyan)
            summaryTile("Индекс состояния", Formatters.decimal(snapshot.readiness), Theme.Palette.green)
            summaryTile("Фокус high", "\(snapshot.focusHighDays) дн.", Theme.Palette.purple)
            summaryTile("Усталость high", "\(snapshot.fatigueHighDays) дн.", Theme.Palette.orange)
        }
    }

    private func recentMatches(_ player: PlayerProfile) -> some View {
        let matches = Array(GameData.matches(for: player).prefix(4))
        return VStack(spacing: 0) {
            ForEach(Array(matches.enumerated()), id: \.element.id) { index, match in
                MatchRow(match: match)
                if index < matches.count - 1 { DividerLine() }
            }
        }
        .padding(.horizontal, 16)
        .pulseCard(padding: 0)
    }

    private func summaryTile(_ title: String, _ value: String, _ tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.system(size: 14)).foregroundStyle(Theme.Palette.textSecondary)
            Text(value).font(.system(size: 26, weight: .bold)).foregroundStyle(tint)
        }
        .pulseCard(padding: 14, radius: Theme.Metrics.tileRadius)
    }

    private func testsCard(_ player: PlayerProfile) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 16) {
                legend("Score", Theme.Palette.cyan)
                legend("Индекс состояния ×10", Theme.Palette.green)
            }
            let scores = TeamAnalytics.scores(for: player, days: period)
            let readiness = TeamAnalytics.readiness(for: player, days: period)
            Chart {
                ForEach(scores) { item in
                    LineMark(x: .value("День", item.date, unit: .day), y: .value("Значение", item.value),
                             series: .value("Серия", "Score"))
                        .foregroundStyle(Theme.Palette.cyan)
                        .interpolationMethod(.catmullRom)
                }
                ForEach(readiness) { item in
                    LineMark(x: .value("День", item.date, unit: .day), y: .value("Значение", item.value * 10),
                             series: .value("Серия", "State Index"))
                        .foregroundStyle(Theme.Palette.green)
                        .interpolationMethod(.catmullRom)
                }
                if let day = testsDay {
                    let score = scores.value(on: day)
                    let state = readiness.value(on: day)
                    DayScrubRule(date: day, callout: ChartCallout(title: Formatters.dayMonthShort(day), lines: [
                        score.map { .init(text: "Score \(Int($0.value.rounded()))", tint: Theme.Palette.cyan) },
                        state.map { .init(text: "Индекс \(Formatters.decimal($0.value))", tint: Theme.Palette.green) }
                    ].compactMap { $0 }))
                    if let score { ScrubPoint(x: .value("День", score.date, unit: .day), y: score.value, tint: Theme.Palette.cyan) }
                    if let state { ScrubPoint(x: .value("День", state.date, unit: .day), y: state.value * 10, tint: Theme.Palette.green) }
                }
            }
            .chartYScale(domain: 0...100)
            .pulseChartAxes()
            .chartScrub($testsDay) { (scores + readiness).nearest(to: $0)?.date }
            .frame(height: 170)
        }
        .pulseCard(padding: 18)
    }

    private func legend(_ title: String, _ color: Color) -> some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(title).font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
        }
    }

    private func balanceCard(_ player: PlayerProfile) -> some View {
        var rng = SeededGenerator(seed: player.seed &+ 7)
        let values = BalanceCategory.allCases.map { _ in Double(rng.int(in: 4...9)) }
        return RadarChart(labels: BalanceCategory.allCases.map(\.shortTitle), values: values)
            .frame(height: 290)
            .pulseCard(padding: 12)
    }

    private func sleepDiary(_ player: PlayerProfile) -> some View {
        SleepHistoryList(sessions: Array(DemoData.sleepSessions(for: player).sorted { $0.start > $1.start }.prefix(min(period, 7))))
    }

    /// Same source as «Питание игроков»: real logged meals with photos, demo meals otherwise.
    private func nutritionDiary(_ player: PlayerProfile) -> some View {
        let meals = Array(TeamFood.entries(deviceMeals: nutrition.meals, days: period)
            .filter { $0.player == player.nickname }
            .map(\.meal)
            .prefix(6))
        return Group {
            if meals.isEmpty {
                EmptyStateView(systemName: "fork.knife", title: "Нет записей", message: "Игрок не добавлял еду за период")
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(meals.enumerated()), id: \.element.id) { index, meal in
                        MealRow(meal: meal)
                        if index < meals.count - 1 { DividerLine() }
                    }
                }
                .padding(.horizontal, 16)
                .pulseCard(padding: 0)
            }
        }
    }
}

/// Отдельный экран «Игроки» (маршрут из других мест).
struct PlayersView: View {
    var body: some View {
        ScreenScaffold {
            NavHeader(title: "Игроки", subtitle: "Состав команды")
        } content: {
            PlayersListContent()
        }
    }
}

import SwiftUI
import Charts

/// Бейдж статуса профиля: «Игрок» / «Solo» / «Стафф».
struct RoleBadge: View {
    let role: UserRole
    var compact = false

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: role.badgeIcon).font(.system(size: compact ? 11 : 12, weight: .bold))
            Text(role.badgeTitle).font(.system(size: compact ? 13 : 14, weight: .bold))
        }
        .foregroundStyle(tint)
        .padding(.horizontal, compact ? 10 : 12)
        .padding(.vertical, compact ? 6 : 7)
        .background(Capsule().fill(tint.opacity(0.15)))
        .overlay(Capsule().strokeBorder(tint.opacity(0.4), lineWidth: 1))
        .accessibilityLabel("Статус профиля: \(role.title)")
    }

    private var tint: Color {
        switch role {
        case .staff: Theme.Palette.orange
        case .teamPlayer: Theme.Palette.cyan
        case .soloPlayer: Theme.Palette.purple
        }
    }
}

/// «Аналитика игроков»: дашборд команды — KPI, готовность по игрокам, динамика, ELO vs K/D и таблица.
struct TeamAnalyticsContent: View {
    enum Sort: String, CaseIterable, Identifiable {
        case readiness = "Готовность"
        case score = "Score"
        case sleep = "Сон"
        case elo = "ELO"

        var id: String { rawValue }
    }

    @Environment(AppRouter.self) private var router
    @State private var sort: Sort = .readiness
    @State private var period = 7
    @State private var trendDay: Date?
    @State private var eloKD: Double?

    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]

    var body: some View {
        let snapshots = TeamAnalytics.snapshots(days: period)
        let matches = DemoData.players.flatMap { GameData.matches(for: $0, days: period) }
        let summary = GameSummary(matches: matches)
        VStack(alignment: .leading, spacing: 22) {
            WeeklySummaryCard()
            HStack(spacing: 8) {
                ForEach([7, 30], id: \.self) { days in
                    ChipButton(title: "\(days) дней", isSelected: period == days) { withAnimation { period = days } }
                }
            }
            LazyVGrid(columns: columns, spacing: 12) {
                kpi("Готовность команды", Formatters.decimal(StatisticsMath.mean(snapshots.map(\.readiness)) ?? 0) + " / 10", Theme.Palette.green)
                kpi("Средний score", "\(Int(StatisticsMath.mean(snapshots.map { Double($0.averageScore) }) ?? 0))", Theme.Palette.cyan)
                kpi("Средний сон", Formatters.duration(minutes: Int(StatisticsMath.mean(snapshots.map { Double($0.sleepMinutes) }) ?? 0)), Theme.Palette.purple)
                kpi("Win-rate команды", "\(Int(summary.winRate.rounded()))% · \(summary.matches) игр", Theme.Palette.orange)
            }
            PulseSection(title: "Готовность по игрокам") { readinessChart(snapshots) }
            PulseSection(title: "Динамика готовности") { trendChart }
            PulseSection(title: "ELO и K/D") { eloChart }
            PulseSection(title: "Таблица") { table(snapshots) }
        }
    }

    private func kpi(_ title: String, _ value: String, _ tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary).lineLimit(1).minimumScaleFactor(0.8)
            Text(value).font(.system(size: 20, weight: .bold)).foregroundStyle(tint).lineLimit(1).minimumScaleFactor(0.6)
        }
        .pulseCard(padding: 14, radius: Theme.Metrics.tileRadius)
    }

    private func readinessChart(_ snapshots: [PlayerSnapshot]) -> some View {
        let sorted = snapshots.sorted { $0.readiness > $1.readiness }
        return VStack(alignment: .leading, spacing: 14) {
            ForEach(sorted) { snapshot in
                Button { router.open(.player(snapshot.player.id), in: router.selectedTab) } label: {
                    HStack(spacing: 10) {
                        Text(snapshot.player.nickname)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Theme.Palette.textPrimary)
                            .frame(width: 58, alignment: .leading)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                        GeometryReader { geo in
                            ZStack(alignment: .leading) {
                                Capsule().fill(Theme.Palette.track)
                                Capsule()
                                    .fill(readinessColor(snapshot.readiness))
                                    .frame(width: geo.size.width * CGFloat(min(snapshot.readiness / 10, 1)))
                                Rectangle()
                                    .fill(Theme.Palette.orange)
                                    .frame(width: 2, height: 18)
                                    .offset(x: geo.size.width * 0.6 - 1)
                            }
                        }
                        .frame(height: 12)
                        Text(Formatters.decimal(snapshot.readiness))
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(readinessColor(snapshot.readiness))
                            .monospacedDigit()
                            .frame(width: 30, alignment: .trailing)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            Text("Оранжевая отметка — порог 6,0: ниже него игрок требует внимания. Нажми на игрока, чтобы открыть карточку.")
                .font(.system(size: 12)).foregroundStyle(Theme.Palette.textTertiary)
        }
        .pulseCard(padding: 18)
    }

    private var trendChart: some View {
        let trend = TeamAnalytics.teamReadinessTrend(days: max(period, 7))
        return VStack(alignment: .leading, spacing: 10) {
            Chart {
                ForEach(trend) { point in
                    AreaMark(x: .value("День", point.date, unit: .day), yStart: .value("Мин", point.low), yEnd: .value("Макс", point.high))
                        .foregroundStyle(Theme.Palette.cyan.opacity(0.15))
                        .interpolationMethod(.catmullRom)
                    LineMark(x: .value("День", point.date, unit: .day), y: .value("Среднее", point.average))
                        .foregroundStyle(Theme.Palette.cyan)
                        .lineStyle(StrokeStyle(lineWidth: 2.5))
                        .interpolationMethod(.catmullRom)
                }
                if let day = trendDay, let point = trend.first(where: { $0.date.isSameDay(as: day) }) {
                    DayScrubRule(date: point.date, callout: ChartCallout(title: Formatters.dayMonthShort(point.date), lines: [
                        .init(text: "Среднее \(Formatters.decimal(point.average))", tint: Theme.Palette.cyan),
                        .init(text: "\(Formatters.decimal(point.low)) – \(Formatters.decimal(point.high))", tint: Theme.Palette.textSecondary)
                    ]))
                    ScrubPoint(x: .value("День", point.date, unit: .day), y: point.average, tint: Theme.Palette.cyan)
                }
            }
            .chartYScale(domain: 4...9)
            .pulseChartAxes()
            .chartScrub($trendDay) { raw in
                trend.min { abs($0.date.timeIntervalSince(raw)) < abs($1.date.timeIntervalSince(raw)) }?.date
            }
            .frame(height: 170)
            HStack(spacing: 14) {
                legend("Среднее по команде", Theme.Palette.cyan)
                legend("Разброс игроков", Theme.Palette.cyan.opacity(0.3))
            }
        }
        .pulseCard(padding: 18)
    }

    private struct EloPoint: Identifiable {
        let id: UUID
        let nickname: String
        let kd: Double
        let elo: Int
    }

    private var eloChart: some View {
        let points = DemoData.players.map { player in
            EloPoint(id: player.id, nickname: player.nickname,
                     kd: GameSummary(matches: GameData.matches(for: player, days: period)).kd, elo: player.elo)
        }
        return VStack(alignment: .leading, spacing: 10) {
            let selected = points.first { $0.kd == eloKD }
            Chart {
                ForEach(points) { point in
                    PointMark(x: .value("K/D", point.kd), y: .value("ELO", point.elo))
                        .foregroundStyle(Theme.Palette.cyan)
                        .symbolSize(selected?.id == point.id ? 220 : 120)
                        .opacity(selected == nil || selected?.id == point.id ? 1 : 0.4)
                        .annotation(position: .top, spacing: 4) {
                            if selected?.id == point.id {
                                ChartCallout(title: point.nickname, lines: [
                                    .init(text: "ELO \(point.elo)", tint: Theme.Palette.cyan),
                                    .init(text: "K/D \(Formatters.decimal(point.kd, fractionDigits: 2))", tint: Theme.Palette.green)
                                ])
                            } else {
                                Text(point.nickname).font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                            }
                        }
                }
            }
            .chartScrub($eloKD) { raw in points.min { abs($0.kd - raw) < abs($1.kd - raw) }?.kd }
            .chartXScale(domain: .automatic(includesZero: false))
            .chartYScale(domain: .automatic(includesZero: false))
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 4)) {
                    AxisGridLine(stroke: StrokeStyle(lineWidth: 1, dash: [4, 4])).foregroundStyle(Theme.Palette.divider)
                    AxisValueLabel().foregroundStyle(Theme.Palette.textSecondary)
                }
            }
            .chartYAxis {
                AxisMarks(position: .leading, values: .automatic(desiredCount: 4)) {
                    AxisGridLine(stroke: StrokeStyle(lineWidth: 1, dash: [4, 4])).foregroundStyle(Theme.Palette.divider)
                    AxisValueLabel().foregroundStyle(Theme.Palette.textSecondary)
                }
            }
            .frame(height: 200)
            Text("По горизонтали K/D за \(period) дней, по вертикали текущий ELO")
                .font(.system(size: 12)).foregroundStyle(Theme.Palette.textTertiary)
        }
        .pulseCard(padding: 18)
    }

    private func table(_ snapshots: [PlayerSnapshot]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ForEach(Sort.allCases) { item in
                        ChipButton(title: item.rawValue, isSelected: sort == item) { withAnimation { sort = item } }
                    }
                }
            }
            .scrollIndicators(.hidden)
            VStack(spacing: 0) {
                header
                ForEach(sorted(snapshots)) { snapshot in
                    DividerLine()
                    Button { router.open(.player(snapshot.player.id), in: router.selectedTab) } label: { row(snapshot) }
                        .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 14)
            .pulseCard(padding: 0)
        }
    }

    private func sorted(_ items: [PlayerSnapshot]) -> [PlayerSnapshot] {
        switch sort {
        case .readiness: items.sorted { $0.readiness > $1.readiness }
        case .score: items.sorted { $0.averageScore > $1.averageScore }
        case .sleep: items.sorted { $0.sleepMinutes > $1.sleepMinutes }
        case .elo: items.sorted { $0.player.elo > $1.player.elo }
        }
    }

    private var header: some View {
        HStack {
            Text("Игрок").frame(maxWidth: .infinity, alignment: .leading)
            Text("Готов.").frame(width: 54, alignment: .trailing)
            Text("Score").frame(width: 50, alignment: .trailing)
            Text("Сон").frame(width: 62, alignment: .trailing)
        }
        .font(.system(size: 12, weight: .semibold))
        .foregroundStyle(Theme.Palette.textTertiary)
        .padding(.vertical, 12)
    }

    private func row(_ snapshot: PlayerSnapshot) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(snapshot.player.nickname).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                    if snapshot.needsAttention {
                        Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 11)).foregroundStyle(Theme.Palette.orange)
                    }
                }
                Text("\(snapshot.player.gameRole) · ELO \(snapshot.player.elo)")
                    .font(.system(size: 12)).foregroundStyle(Theme.Palette.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text(Formatters.decimal(snapshot.readiness))
                .foregroundStyle(readinessColor(snapshot.readiness))
                .frame(width: 54, alignment: .trailing)
            Text("\(snapshot.averageScore)").foregroundStyle(Theme.Palette.textPrimary).frame(width: 50, alignment: .trailing)
            Text(Formatters.duration(minutes: snapshot.sleepMinutes))
                .foregroundStyle(snapshot.sleepMinutes < 380 ? Theme.Palette.red : Theme.Palette.textPrimary)
                .frame(width: 62, alignment: .trailing)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .font(.system(size: 15, weight: .semibold))
        .monospacedDigit()
        .padding(.vertical, 12)
        .contentShape(Rectangle())
    }

    private func readinessColor(_ value: Double) -> Color {
        switch value {
        case 7...: Theme.Palette.green
        case 6..<7: Theme.Palette.cyan
        default: Theme.Palette.orange
        }
    }

    private func legend(_ title: String, _ color: Color) -> some View {
        HStack(spacing: 6) {
            RoundedRectangle(cornerRadius: 3).fill(color).frame(width: 14, height: 8)
            Text(title).font(.system(size: 12)).foregroundStyle(Theme.Palette.textSecondary)
        }
    }
}

/// «Корреляционный анализ» для стаффа: тепловая карта факторов × игровых метрик, диаграмма «сон → K/D», главные связи.
struct TeamCorrelationsContent: View {
    @State private var selectedPlayer: UUID?

    private var players: [PlayerProfile] {
        selectedPlayer.flatMap { id in DemoData.players.first { $0.id == id } }.map { [$0] } ?? DemoData.players
    }

    var body: some View {
        let matrix = TeamAnalytics.matrix(players: players)
        let rows = Array(TeamAnalytics.correlationRows(players: players).prefix(5))
        let points = TeamAnalytics.sleepVersusKD(players: players)
        // Plain content: both the staff «Аналитика» tab and the standalone screen provide header and scrolling.
        VStack(alignment: .leading, spacing: 22) {
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ChipButton(title: "Вся команда", isSelected: selectedPlayer == nil) { selectedPlayer = nil }
                    ForEach(DemoData.players) { player in
                        ChipButton(title: player.nickname, isSelected: selectedPlayer == player.id) { selectedPlayer = player.id }
                    }
                }
            }
            .scrollIndicators(.hidden)
            PulseSection(title: "Карта связей") { CorrelationHeatmap(matrix: matrix) }
            PulseSection(title: "Сон → K/D") { SleepKDScatter(points: points) }
            PulseSection(title: "Главные связи") { CorrelationTable(rows: rows) }
            Text("Коэффициент Пирсона от −1 до +1 по дням с матчами. Положительное значение — показатели растут вместе, отрицательное — один растёт, другой падает. Связь не означает причину.")
                .font(.system(size: 13))
                .foregroundStyle(Theme.Palette.textTertiary)
        }
        .animation(.easeInOut(duration: 0.2), value: selectedPlayer)
    }
}

struct CorrelationHeatmap: View {
    let matrix: [[Double?]]

    var body: some View {
        VStack(spacing: 6) {
            HStack(spacing: 6) {
                Text("").frame(width: 96, alignment: .leading)
                ForEach(GameMetric.allCases) { metric in
                    Text(metric.title)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Theme.Palette.textSecondary)
                        .frame(maxWidth: .infinity)
                }
            }
            ForEach(Array(WellbeingFactor.allCases.enumerated()), id: \.element.id) { rowIndex, factor in
                HStack(spacing: 6) {
                    Text(factor.title)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(Theme.Palette.textPrimary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .frame(width: 96, alignment: .leading)
                    ForEach(Array(GameMetric.allCases.enumerated()), id: \.element.id) { columnIndex, _ in
                        cell(matrix[safe: rowIndex]?[safe: columnIndex] ?? nil)
                    }
                }
            }
            HStack(spacing: 6) {
                Text("−1").font(.system(size: 11)).foregroundStyle(Theme.Palette.textTertiary)
                LinearGradient(colors: [Theme.Palette.red, Theme.Palette.track, Theme.Palette.green], startPoint: .leading, endPoint: .trailing)
                    .frame(height: 6)
                    .clipShape(Capsule())
                Text("+1").font(.system(size: 11)).foregroundStyle(Theme.Palette.textTertiary)
            }
            .padding(.top, 6)
        }
        .pulseCard(padding: 16)
    }

    private func cell(_ value: Double?) -> some View {
        let text = value.map { String(format: "%+.2f", $0).replacingOccurrences(of: ".", with: ",") } ?? "—"
        return Text(text)
            .font(.system(size: 13, weight: .bold, design: .monospaced))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .frame(height: 38)
            .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(color(for: value)))
    }

    private func color(for value: Double?) -> Color {
        guard let value else { return Theme.Palette.track }
        let strength = min(abs(value), 1)
        let base = value >= 0 ? Theme.Palette.green : Theme.Palette.red
        return strength < 0.1 ? Theme.Palette.track : base.opacity(0.25 + strength * 0.65)
    }
}

struct SleepKDScatter: View {
    let points: [ScatterPoint]
    @State private var selectedID: UUID?

    var body: some View {
        let line = TeamAnalytics.trendLine(points)
        let xs = points.map(\.sleepHours)
        let r = StatisticsMath.pearson(xs, points.map(\.kd))
        VStack(alignment: .leading, spacing: 10) {
            Chart {
                ForEach(points) { point in
                    PointMark(x: .value("Сон, ч", point.sleepHours), y: .value("K/D", point.kd))
                        .foregroundStyle(point.won ? Theme.Palette.green.opacity(0.8) : Theme.Palette.red.opacity(0.7))
                        .symbolSize(36)
                        .opacity(selectedID == nil || selectedID == point.id ? 1 : 0.35)
                }
                if let point = points.first(where: { $0.id == selectedID }) {
                    RuleMark(x: .value("Сон, ч", point.sleepHours))
                        .foregroundStyle(Theme.Palette.textSecondary.opacity(0.55))
                        .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                        .annotation(position: .top, spacing: 4, overflowResolution: .init(x: .fit(to: .chart), y: .disabled)) {
                            ChartCallout(title: "\(point.player) · \(point.won ? "победа" : "поражение")", lines: [
                                .init(text: "Сон \(MetricFormat.hours(point.sleepHours))", tint: Theme.Palette.purple),
                                .init(text: "K/D \(Formatters.decimal(point.kd, fractionDigits: 2))",
                                      tint: point.won ? Theme.Palette.green : Theme.Palette.red)
                            ])
                        }
                    ScrubPoint(x: .value("Сон, ч", point.sleepHours), y: point.kd,
                               tint: point.won ? Theme.Palette.green : Theme.Palette.red)
                }
                if let line, let low = xs.min(), let high = xs.max() {
                    LineMark(x: .value("Сон, ч", low), y: .value("K/D", line.intercept + line.slope * low), series: .value("Тренд", "t"))
                        .foregroundStyle(Theme.Palette.cyan)
                        .lineStyle(StrokeStyle(lineWidth: 2.5, dash: [6, 4]))
                    LineMark(x: .value("Сон, ч", high), y: .value("K/D", line.intercept + line.slope * high), series: .value("Тренд", "t"))
                        .foregroundStyle(Theme.Palette.cyan)
                        .lineStyle(StrokeStyle(lineWidth: 2.5, dash: [6, 4]))
                }
            }
            .chartXScale(domain: .automatic(includesZero: false))
            .chartYScale(domain: .automatic(includesZero: false))
            .chartScrub(Binding(
                get: { points.first { $0.id == selectedID }?.sleepHours },
                set: { hours in selectedID = hours.flatMap { h in points.first { $0.sleepHours == h }?.id } }
            )) { raw in points.min { abs($0.sleepHours - raw) < abs($1.sleepHours - raw) }?.sleepHours }
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 5)) { value in
                    AxisGridLine(stroke: StrokeStyle(lineWidth: 1, dash: [4, 4])).foregroundStyle(Theme.Palette.divider)
                    AxisValueLabel { if let hours = value.as(Double.self) { Text("\(Formatters.decimal(hours)) ч") } }
                        .foregroundStyle(Theme.Palette.textSecondary)
                }
            }
            .chartYAxis {
                AxisMarks(position: .leading, values: .automatic(desiredCount: 4)) {
                    AxisGridLine(stroke: StrokeStyle(lineWidth: 1, dash: [4, 4])).foregroundStyle(Theme.Palette.divider)
                    AxisValueLabel().foregroundStyle(Theme.Palette.textSecondary)
                }
            }
            .frame(height: 210)
            HStack(spacing: 14) {
                dot("Победа", Theme.Palette.green)
                dot("Поражение", Theme.Palette.red)
                Spacer()
                Text("r = \(r.map { String(format: "%+.2f", $0).replacingOccurrences(of: ".", with: ",") } ?? "—") · \(points.count) матчей")
                    .font(.system(size: 12, weight: .semibold, design: .monospaced))
                    .foregroundStyle(Theme.Palette.textSecondary)
            }
        }
        .pulseCard(padding: 18)
    }

    private func dot(_ title: String, _ color: Color) -> some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(title).font(.system(size: 12)).foregroundStyle(Theme.Palette.textSecondary)
        }
    }
}

extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

/// Отдельный экран «Аналитика игроков».
struct TeamAnalyticsView: View {
    var body: some View {
        ScreenScaffold(spacing: 22) {
            NavHeader(title: "Аналитика игроков", subtitle: "Дашборд команды")
        } content: {
            TeamAnalyticsContent()
        }
    }
}

/// Отдельный экран «Корреляции».
struct TeamCorrelationsView: View {
    var body: some View {
        ScreenScaffold(spacing: 22) {
            NavHeader(title: "Корреляции", subtitle: "Игровые и неигровые признаки · 30 дней")
        } content: {
            TeamCorrelationsContent()
        }
    }
}

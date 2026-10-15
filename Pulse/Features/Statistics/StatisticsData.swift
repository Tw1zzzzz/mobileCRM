import SwiftUI
import Charts

/// Aggregates per-day series from every module for the selected period.
struct StatisticsData {
    let days: [Date]
    let mood: [DailyValue]
    let energy: [DailyValue]
    let cognitive: [DailyValue]
    let sleepHours: [DailyValue]
    let screenHours: [DailyValue]
    let kcal: [DailyValue]
    let averageSleep: Int?
    let sleepCount: Int
    let averageScreenTime: Int
    let screenTimeChange: Double?
    let averageKcal: Int?
    let mealCount: Int

    init(period: StatsPeriod, sleep: SleepStore, nutrition: NutritionStore, wellbeing: WellbeingStore,
         cognitive: CognitiveStore, screenTime: ScreenTimeStore) {
        let end = period.end.startOfDay
        let count = period.dayCount
        let days = (0..<count).reversed().map { end.adding(days: -$0) }
        self.days = days

        let moodPairs = days.compactMap { day in wellbeing.averageMood(on: day).map { (day, $0) } }
        mood = moodPairs.map { DailyValue(date: $0.0, value: $0.1.mood) }
        energy = moodPairs.map { DailyValue(date: $0.0, value: $0.1.energy) }
        self.cognitive = days.compactMap { day in cognitive.averageScore(on: day).map { DailyValue(date: day, value: $0) } }
        sleepHours = days.compactMap { day in sleep.minutes(on: day).map { DailyValue(date: day, value: Double($0) / 60) } }

        let screen = screenTime.daily(lastDays: count * 2, now: period.end)
        let current = Array(screen.suffix(count))
        let previous = Array(screen.prefix(count))
        screenHours = current.map { DailyValue(date: $0.date, value: $0.value / 60) }
        averageScreenTime = Int(StatisticsMath.mean(current.map(\.value)) ?? 0)
        screenTimeChange = StatisticsMath.percentChange(
            from: StatisticsMath.mean(previous.map(\.value)) ?? 0,
            to: Double(averageScreenTime)
        )

        kcal = days.compactMap { day in
            let meals = nutrition.meals(on: day)
            return meals.isEmpty ? nil : DailyValue(date: day, value: Double(NutritionTotals(meals: meals).kcal))
        }
        averageKcal = StatisticsMath.mean(kcal.map(\.value)).map { Int($0) }
        mealCount = days.reduce(0) { $0 + nutrition.meals(on: $1).count }

        let sleepValues = sleepHours.map(\.value)
        averageSleep = StatisticsMath.mean(sleepValues).map { Int($0 * 60) }
        sleepCount = sleepValues.count
    }

    var isEmpty: Bool {
        mood.isEmpty && cognitive.isEmpty && sleepHours.isEmpty && kcal.isEmpty
    }

    /// Pearson correlations between lifestyle factors and outcomes on matching days.
    var correlations: [CorrelationRow] {
        [
            pair("Сон", sleepHours, "Когнитивная форма", cognitive),
            pair("Сон", sleepHours, "Настроение", mood),
            pair("Экранное время", screenHours, "Когнитивная форма", cognitive),
            pair("Экранное время", screenHours, "Энергия", energy),
            pair("Питание", kcal, "Энергия", energy),
            pair("Настроение", mood, "Когнитивная форма", cognitive)
        ].compactMap { $0 }
    }

    private func pair(_ factor: String, _ xs: [DailyValue], _ metric: String, _ ys: [DailyValue]) -> CorrelationRow? {
        let lookup = Dictionary(uniqueKeysWithValues: ys.map { ($0.date.startOfDay, $0.value) })
        let matched = xs.compactMap { x in lookup[x.date.startOfDay].map { (x.value, $0) } }
        guard let r = StatisticsMath.pearson(matched.map(\.0), matched.map(\.1)) else { return nil }
        return CorrelationRow(factor: factor, metric: metric, value: r)
    }
}

/// «Неигровой анализ»: выбор показателей, каждый — отдельный график.
struct NonGameAnalysisView: View {
    let data: StatisticsData

    enum Metric: String, CaseIterable, Identifiable {
        case cognitive = "Когнитивная форма"
        case sleep = "Сон"
        case screen = "Экранное время"
        case mood = "Настроение и энергия"
        case food = "Еда"

        var id: String { rawValue }
    }

    @State private var selected: Set<Metric> = [.sleep, .cognitive]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            FlowChips(items: Metric.allCases.map(\.rawValue), selected: Set(selected.map(\.rawValue))) { title in
                guard let metric = Metric(rawValue: title) else { return }
                // Keep at least one chart open so the section never collapses to nothing.
                let next = selected.symmetricDifference([metric])
                if !next.isEmpty { selected = next }
            }
            ForEach(Metric.allCases.filter { selected.contains($0) }) { metric in
                VStack(alignment: .leading, spacing: 10) {
                    Text(metric.rawValue).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                    chart(for: metric)
                }
                .pulseCard(padding: 18)
            }
        }
    }

    @ViewBuilder
    private func chart(for metric: Metric) -> some View {
        switch metric {
        case .cognitive: MetricLineChart(series: data.cognitive, color: Theme.Palette.green, domain: 0...100, height: 140) { "\(Int($0.rounded())) баллов" }
        case .sleep: MetricLineChart(series: data.sleepHours, color: Theme.Palette.purple, height: 140, format: MetricFormat.hours)
        case .screen: MetricLineChart(series: data.screenHours, color: Theme.Palette.cyan, height: 140, format: MetricFormat.hours)
        case .mood: MetricLineChart(series: data.mood, color: Theme.Palette.yellow, domain: 0...10, height: 140, format: MetricFormat.outOfTen)
        case .food: MetricLineChart(series: data.kcal, color: Theme.Palette.orange, height: 140) { "\(Formatters.grouped(Int($0))) ккал" }
        }
    }
}

/// Value formats for chart callouts.
enum MetricFormat {
    static func hours(_ value: Double) -> String { Formatters.duration(minutes: Int((value * 60).rounded())) }
    static func outOfTen(_ value: Double) -> String { "\(Formatters.decimal(value)) / 10" }
}

/// Wrapping row of toggle chips.
struct FlowChips: View {
    let items: [String]
    let selected: Set<String>
    let onToggle: (String) -> Void

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) { chips }
            ScrollView(.horizontal) { HStack(spacing: 8) { chips } }.scrollIndicators(.hidden)
        }
    }

    private var chips: some View {
        ForEach(items, id: \.self) { item in
            ChipButton(title: item, isSelected: selected.contains(item)) { onToggle(item) }
        }
    }
}

/// Таблица корреляций с цветовой индикацией силы связи.
struct CorrelationTable: View {
    let rows: [CorrelationRow]

    var body: some View {
        VStack(spacing: 0) {
            if rows.isEmpty {
                Text("Недостаточно данных для расчёта (нужно ≥ 3 совпадающих дня)")
                    .font(.system(size: 15))
                    .foregroundStyle(Theme.Palette.textSecondary)
                    .padding(.vertical, 16)
            }
            ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("\(row.factor) → \(row.metric)").font(.system(size: 15, weight: .medium)).foregroundStyle(Theme.Palette.textPrimary)
                        Text("\(CorrelationStrength(row.value).title) \(row.value >= 0 ? "прямая" : "обратная") связь")
                            .font(.system(size: 12)).foregroundStyle(Theme.Palette.textSecondary)
                    }
                    Spacer()
                    Text(String(format: "%+.2f", row.value).replacingOccurrences(of: ".", with: ","))
                        .font(.system(size: 17, weight: .bold, design: .monospaced))
                        .foregroundStyle(color(for: row.value))
                        .padding(.horizontal, 10).padding(.vertical, 6)
                        .background(RoundedRectangle(cornerRadius: 8).fill(color(for: row.value).opacity(0.14)))
                }
                .padding(.vertical, 12)
                if index < rows.count - 1 { DividerLine() }
            }
        }
        .padding(.horizontal, 16)
        .pulseCard(padding: 0)
    }

    private func color(for value: Double) -> Color {
        switch CorrelationStrength(value) {
        case .strong: value > 0 ? Theme.Palette.green : Theme.Palette.red
        case .moderate: value > 0 ? Theme.Palette.cyan : Theme.Palette.orange
        case .weak: Theme.Palette.textSecondary
        }
    }
}

/// «Игровой анализ»: ELO и игровые метрики текущего игрока — один график с переключателем.
/// Те же матчи, что на «Сегодня» и в «Игровой статистике».
struct GameAnalysisView: View {
    enum Metric: String, CaseIterable, Identifiable {
        case elo = "ELO"
        case kd = "K/D"
        case adr = "ADR"

        var id: String { rawValue }

        var color: Color {
            switch self {
            case .elo: Theme.Palette.cyan
            case .kd: Theme.Palette.green
            case .adr: Theme.Palette.orange
            }
        }

        func format(_ value: Double) -> String {
            switch self {
            case .elo: Formatters.grouped(Int(value.rounded()))
            case .kd: Formatters.decimal(value, fractionDigits: 2)
            case .adr: Formatters.decimal(value)
            }
        }
    }

    let days: Int
    @Environment(SessionStore.self) private var session
    @Environment(GameStore.self) private var game
    @State private var metric: Metric = .elo

    private func paddedDomain(_ series: [DailyValue]) -> ClosedRange<Double> {
        let values = series.map(\.value)
        guard let low = values.min(), let high = values.max(), high > low else {
            let value = values.first ?? 0
            return (value - 1)...(value + 1)
        }
        let pad = (high - low) * 0.2
        return (low - pad)...(high + pad)
    }

    private func series(for metric: Metric) -> [DailyValue] {
        let span = max(days, 7)
        switch metric {
        case .elo: return game.eloHistory(for: session.user, days: span)
        case .kd: return game.daily(.kd, for: session.user, days: span)
        case .adr: return game.daily(.adr, for: session.user, days: span)
        }
    }

    var body: some View {
        let values = series(for: metric)
        VStack(alignment: .leading, spacing: 14) {
            PulseSegmented(options: Metric.allCases, selection: $metric, title: \.rawValue, height: 36)
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline) {
                    Text(metric.rawValue).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                    Spacer()
                    if let last = values.last {
                        Text("сейчас \(metric.format(last.value))")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(metric.color)
                    }
                }
                MetricLineChart(series: values, color: metric.color, domain: paddedDomain(values), height: 160,
                                format: metric.format)
                    .id(metric)
            }
            .pulseCard(padding: 18)
        }
    }
}

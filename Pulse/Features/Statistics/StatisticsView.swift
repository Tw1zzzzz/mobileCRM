import SwiftUI
import Charts

enum StatsPeriod: Hashable {
    case days(Int)
    case custom(start: Date, end: Date)

    var title: String {
        switch self {
        case .days(7): "7 дней"
        case .days(30): "30 дней"
        case .days(90): "90 дней"
        case .days(365): "1 год"
        case .days(let n): "\(n) дн."
        case .custom: "Свой"
        }
    }

    var end: Date {
        switch self {
        case .days: .now
        case .custom(_, let end): end
        }
    }

    var dayCount: Int {
        switch self {
        case .days(let n): n
        case .custom(let start, let end):
            max((Calendar.pulse.dateComponents([.day], from: start.startOfDay, to: end.startOfDay).day ?? 0) + 1, 1)
        }
    }
}

/// «Статистика» как отдельный экран (переходы из Фитнеса и Аналитики).
struct StatisticsView: View {
    var body: some View {
        ScreenScaffold {
            NavHeader(title: "Статистика", subtitle: "Агрегированные показатели")
        } content: {
            StatisticsContent()
        }
    }
}

/// «Статистика»: фильтр периода, настроение/энергия, когнитивная форма, колесо баланса,
/// восстановление, неигровой анализ, корреляции и игровой анализ. Also the «Состояние → Статистика» section.
struct StatisticsContent: View {
    @Environment(SleepStore.self) private var sleep
    @Environment(NutritionStore.self) private var nutrition
    @Environment(WellbeingStore.self) private var wellbeing
    @Environment(CognitiveStore.self) private var cognitive
    @Environment(ScreenTimeStore.self) private var screenTime

    @State private var period: StatsPeriod = .days(30)
    @State private var showMood = true
    @State private var showEnergy = true
    @State private var showCustomPeriod = false

    private let presets: [StatsPeriod] = [.days(7), .days(30), .days(90), .days(365)]

    var body: some View {
        let data = StatisticsData(period: period, sleep: sleep, nutrition: nutrition, wellbeing: wellbeing,
                                  cognitive: cognitive, screenTime: screenTime)
        VStack(alignment: .leading, spacing: Theme.Metrics.sectionSpacing) {
            periodPicker
            if data.isEmpty {
                EmptyStateView(systemName: "chart.xyaxis.line", title: "Нет данных за период",
                               message: "Заполни тесты, сон и настроение, чтобы увидеть статистику.")
            } else {
                PulseSection(title: "Настроение и энергия") { moodCard(data) }
                PulseSection(title: "Когнитивная форма") {
                    MetricLineChart(series: data.cognitive, color: Theme.Palette.green, domain: 0...100) { "\(Int($0.rounded())) баллов" }
                        .pulseCard(padding: 20)
                }
                PulseSection(title: "Колесо баланса") { balanceCard }
                PulseSection(title: "Восстановление и образ жизни") { recoveryCards(data) }
                PulseSection(title: "Неигровой анализ") { NonGameAnalysisView(data: data) }
                PulseSection(title: "Корреляции") { CorrelationTable(rows: data.correlations) }
                PulseSection(title: "Игровой анализ") { GameAnalysisView(days: min(period.dayCount, 90)) }
            }
        }
        .animation(.easeInOut(duration: 0.25), value: period)
        .sheet(isPresented: $showCustomPeriod) {
            CustomPeriodSheet { start, end in period = .custom(start: start, end: end) }
                .presentationDetents([.medium])
        }
    }

    private var periodPicker: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(presets, id: \.self) { preset in
                    ChipButton(title: preset.title, isSelected: period == preset) { period = preset }
                }
                ChipButton(title: customTitle, isSelected: isCustom) { showCustomPeriod = true }
            }
        }
        .scrollIndicators(.hidden)
    }

    private var isCustom: Bool {
        if case .custom = period { return true }
        return false
    }

    private var customTitle: String {
        if case .custom(let start, let end) = period {
            return "\(Formatters.dayMonthShort(start)) – \(Formatters.dayMonthShort(end))"
        }
        return "Произвольный"
    }

    private func moodCard(_ data: StatisticsData) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                ChipButton(title: "Настроение", isSelected: showMood, tint: Theme.Palette.yellow) { showMood.toggle() }
                ChipButton(title: "Энергия", isSelected: showEnergy) { showEnergy.toggle() }
            }
            MoodEnergyChart(mood: showMood ? data.mood : [], energy: showEnergy ? data.energy : [])
        }
        .pulseCard(padding: 20)
    }

    @ViewBuilder
    private var balanceCard: some View {
        if let entry = wellbeing.latestBalance {
            VStack(spacing: 6) {
                RadarChart(labels: BalanceCategory.allCases.map(\.shortTitle), values: entry.orderedValues)
                    .frame(height: 300)
                Text("Последнее заполнение: \(Formatters.dayMonth(entry.day)) · средний балл \(Formatters.decimal(entry.average))")
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.Palette.textSecondary)
            }
            .pulseCard(padding: 14)
        } else {
            EmptyStateView(systemName: "circle.hexagongrid", title: "Нет данных", message: "Заполни колесо баланса")
        }
    }

    private func recoveryCards(_ data: StatisticsData) -> some View {
        VStack(spacing: 12) {
            RecoveryStatCard(icon: "moon", tint: Theme.Palette.purple, title: "Сон",
                             value: data.averageSleep.map { Formatters.duration(minutes: $0) } ?? "—",
                             caption: "средняя продолжительность · \(data.sleepCount) записей")
            RecoveryStatCard(icon: "iphone", tint: Theme.Palette.cyan, title: "Экранное время",
                             value: Formatters.duration(minutes: data.averageScreenTime),
                             caption: data.screenTimeChange.map { "\($0 > 0 ? "+" : "")\(Int($0.rounded()))% к прошлому периоду" } ?? "нет данных для сравнения",
                             captionTint: (data.screenTimeChange ?? 0) <= 0 ? Theme.Palette.green : Theme.Palette.red)
            RecoveryStatCard(icon: "fork.knife", tint: Theme.Palette.green, title: "Питание",
                             value: data.averageKcal.map { "\(Formatters.grouped($0)) ккал" } ?? "—",
                             caption: "в среднем в день · \(data.mealCount) записей")
        }
    }
}

struct RecoveryStatCard: View {
    let icon: String
    let tint: Color
    let title: String
    let value: String
    let caption: String
    var captionTint: Color = Theme.Palette.textSecondary

    var body: some View {
        HStack(spacing: 16) {
            IconBadge(systemName: icon, tint: tint, size: 50, iconSize: 22)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.system(size: 15)).foregroundStyle(Theme.Palette.textSecondary)
                Text(value).font(.system(size: 22, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
                Text(caption).font(.system(size: 13)).foregroundStyle(captionTint)
            }
            Spacer(minLength: 0)
        }
        .pulseCard(padding: 16)
    }
}

/// Настроение и энергия на одном графике; удержание пальцем показывает оба значения дня.
struct MoodEnergyChart: View {
    let mood: [DailyValue]
    let energy: [DailyValue]
    @State private var selectedDay: Date?

    var body: some View {
        let moodPoint = mood.value(on: selectedDay)
        let energyPoint = energy.value(on: selectedDay)
        Chart {
            ForEach(mood) { item in
                LineMark(x: .value("День", item.date, unit: .day), y: .value("Значение", item.value), series: .value("Линия", "Настроение"))
                    .foregroundStyle(Theme.Palette.yellow)
                    .interpolationMethod(.catmullRom)
            }
            ForEach(energy) { item in
                LineMark(x: .value("День", item.date, unit: .day), y: .value("Значение", item.value), series: .value("Линия", "Энергия"))
                    .foregroundStyle(Theme.Palette.cyan)
                    .interpolationMethod(.catmullRom)
            }
            if let selectedDay {
                DayScrubRule(date: selectedDay, callout: ChartCallout(title: Formatters.dayMonthShort(selectedDay), lines: [
                    moodPoint.map { .init(text: "Настроение \(Formatters.decimal($0.value))", tint: Theme.Palette.yellow) },
                    energyPoint.map { .init(text: "Энергия \(Formatters.decimal($0.value))", tint: Theme.Palette.cyan) }
                ].compactMap { $0 }))
                if let moodPoint { ScrubPoint(x: .value("День", moodPoint.date, unit: .day), y: moodPoint.value, tint: Theme.Palette.yellow) }
                if let energyPoint { ScrubPoint(x: .value("День", energyPoint.date, unit: .day), y: energyPoint.value, tint: Theme.Palette.cyan) }
            }
        }
        .chartYScale(domain: 0...10)
        .pulseChartAxes()
        .chartScrub($selectedDay) { (mood + energy).nearest(to: $0)?.date }
        .frame(height: 190)
    }
}

/// Generic daily line chart in the app style. Hold a finger to read any day.
struct MetricLineChart: View {
    let series: [DailyValue]
    var color: Color = Theme.Palette.cyan
    var domain: ClosedRange<Double>?
    var height: CGFloat = 170
    var format: (Double) -> String = { Formatters.decimal($0) }

    @State private var selectedDay: Date?

    var body: some View {
        if series.isEmpty {
            Text("Нет данных за период")
                .font(.system(size: 15))
                .foregroundStyle(Theme.Palette.textSecondary)
                .frame(maxWidth: .infinity, minHeight: 80)
        } else {
            chart.frame(height: height)
        }
    }

    @ViewBuilder
    private var chart: some View {
        let selected = series.value(on: selectedDay)
        let base = Chart {
            ForEach(series) { item in
                AreaMark(
                    x: .value("День", item.date, unit: .day),
                    yStart: .value("База", domain?.lowerBound ?? 0),
                    yEnd: .value("Значение", item.value)
                )
                .foregroundStyle(LinearGradient(colors: [color.opacity(0.28), .clear], startPoint: .top, endPoint: .bottom))
                .interpolationMethod(.catmullRom)
                LineMark(x: .value("День", item.date, unit: .day), y: .value("Значение", item.value))
                    .foregroundStyle(color)
                    .lineStyle(StrokeStyle(lineWidth: 2.5))
                    .interpolationMethod(.catmullRom)
            }
            if let selected {
                DayScrubRule(date: selected.date,
                             callout: ChartCallout(title: Formatters.dayMonthShort(selected.date), value: format(selected.value), tint: color))
                ScrubPoint(x: .value("День", selected.date, unit: .day), y: selected.value, tint: color)
            }
        }
        .pulseChartAxes()
        .chartScrub($selectedDay) { series.nearest(to: $0)?.date }
        if let domain {
            base.chartYScale(domain: domain)
        } else {
            base
        }
    }
}

extension View {
    func pulseChartAxes() -> some View {
        self
            .chartYAxis {
                AxisMarks(position: .leading, values: .automatic(desiredCount: 3)) {
                    AxisGridLine(stroke: StrokeStyle(lineWidth: 1, dash: [4, 4])).foregroundStyle(Theme.Palette.divider)
                    AxisValueLabel().foregroundStyle(Theme.Palette.textSecondary)
                }
            }
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 4)) { value in
                    // The last label hugs the right edge instead of being cut off («12 о…»).
                    AxisValueLabel(anchor: value.index == value.count - 1 ? .topTrailing : .top) {
                        if let date = value.as(Date.self) { Text(Formatters.dayMonthShort(date)) }
                    }
                    .foregroundStyle(Theme.Palette.textSecondary)
                }
            }
    }
}

struct CustomPeriodSheet: View {
    let onApply: (Date, Date) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var start = Date.now.adding(days: -14)
    @State private var end = Date.now

    var body: some View {
        SheetScaffold(title: "Произвольный период") {
            VStack(spacing: 14) {
                DatePicker("С", selection: $start, in: ...end, displayedComponents: .date)
                DividerLine()
                DatePicker("По", selection: $end, in: start...Date.now, displayedComponents: .date)
            }
            .foregroundStyle(Theme.Palette.textPrimary)
            .pulseCard()
            GradientButton(title: "Применить") {
                onApply(start, end)
                dismiss()
            }
        }
    }
}

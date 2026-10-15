import SwiftUI
import Charts

/// Mockup 6 — «Экранное время · Приложения и недельная динамика» + категории и история из ТЗ.
struct ScreenTimeAppsView: View {
    @Environment(ScreenTimeStore.self) private var store
    @State private var showAllApps = false
    @State private var showSettings = false
    @State private var period = 7
    @State private var dynamicsDay: Date?
    @State private var categoryAngle: Int?

    var body: some View {
        ScreenScaffold {
            NavHeader(title: "Экранное время", subtitle: "Приложения и недельная динамика") {
                HeaderIconButton(systemName: "gearshape") { showSettings = true }
            }
        } content: {
            if store.showsContent {
                if store.hasDetailedData {
                    PulseSection(title: "Топ приложений", actionTitle: showAllApps ? "Свернуть" : "Все") {
                        withAnimation { showAllApps.toggle() }
                    } content: {
                        AppUsageList(apps: showAllApps ? store.apps : Array(store.apps.prefix(6)))
                    }
                }
                PulseSection(title: "Неделя") { weekCard }
                if store.hasDetailedData {
                    PulseSection(title: "Категории") { categoriesCard }
                }
                PulseSection(title: "Динамика") { dynamicsCard }
                PulseSection(title: "История") { historyCard }
            } else {
                ScreenTimeEmptyState { showSettings = true }
            }
        }
        .sheet(isPresented: $showSettings) {
            ScreenTimeSettingsSheet().presentationDetents([.medium])
        }
    }

    private var weekCard: some View {
        let maxMinutes = Double(max(store.week.map(\.minutes).max() ?? 1, store.dailyLimitMinutes))
        let chartHeight: CGFloat = 210
        let barArea = chartHeight - 30
        let limitY = chartHeight - barArea * CGFloat(Double(store.dailyLimitMinutes) / maxMinutes)
        return VStack(spacing: 26) {
            HStack {
                Text("Среднее").font(.system(size: 19)).foregroundStyle(Theme.Palette.textSecondary)
                Spacer()
                DurationText(minutes: store.weekAverage, valueSize: 36, unitSize: 26, unitColor: Theme.Palette.textPrimary)
            }
            VStack(spacing: 12) {
                ZStack(alignment: .topTrailing) {
                    HStack(alignment: .bottom, spacing: 12) {
                        ForEach(store.week) { day in
                            weekBar(day: day, height: barArea * CGFloat(Double(day.minutes) / maxMinutes))
                        }
                    }
                    .frame(height: chartHeight, alignment: .bottom)

                    GeometryReader { geo in
                        Path { path in
                            path.move(to: CGPoint(x: 0, y: limitY))
                            path.addLine(to: CGPoint(x: geo.size.width, y: limitY))
                        }
                        .stroke(Theme.Palette.orange.opacity(0.85), style: StrokeStyle(lineWidth: 1.5, dash: [5, 4]))
                    }
                    .frame(height: chartHeight)
                    .allowsHitTesting(false)

                    Text("Лимит \(store.dailyLimitMinutes / 60) ч")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(Theme.Palette.orange)
                        .offset(y: limitY - 22)
                }
                HStack(spacing: 12) {
                    ForEach(store.week) { day in
                        let isToday = day.date.isSameDay(as: .now)
                        Text(Formatters.weekdayShort(day.date))
                            .font(.system(size: 16, weight: isToday ? .bold : .regular))
                            .foregroundStyle(isToday ? Theme.Palette.textPrimary : Theme.Palette.textSecondary)
                            .frame(maxWidth: .infinity)
                    }
                }
            }
        }
        .padding(.top, 6)
        .pulseCard(padding: 20)
    }

    private func weekBar(day: ScreenTimeDay, height: CGFloat) -> some View {
        let isToday = day.date.isSameDay(as: .now)
        let parts = Formatters.durationParts(minutes: day.minutes)
        return VStack(spacing: 8) {
            Text("\(parts.hours) ч \(parts.minutes) м")
                .font(.system(size: 12, weight: isToday ? .bold : .medium))
                .foregroundStyle(isToday ? Theme.Palette.textPrimary : Theme.Palette.textSecondary)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .frame(maxWidth: .infinity)
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(
                    isToday
                        ? AnyShapeStyle(LinearGradient(colors: [Color(hex: 0x22E0FF), Color(hex: 0x10B4F0)], startPoint: .top, endPoint: .bottom))
                        : AnyShapeStyle(LinearGradient(colors: [Color(hex: 0x2B6FD6), Color(hex: 0x1A3F86)], startPoint: .top, endPoint: .bottom))
                )
                .frame(height: max(height, 8))
                .padding(.horizontal, 4)
                .shadow(color: isToday ? Theme.Palette.cyan.opacity(0.4) : .clear, radius: 10)
        }
        .frame(maxWidth: .infinity)
    }

    private var categoriesCard: some View {
        let totals = store.categoryTotals
        let sum = Double(max(totals.reduce(0) { $0 + $1.minutes }, 1))
        return HStack(spacing: 20) {
            let selected = category(atAngle: categoryAngle, in: totals)
            Chart(totals, id: \.category) { item in
                SectorMark(angle: .value("Мин", item.minutes), innerRadius: .ratio(0.62),
                           outerRadius: .ratio(selected?.category == item.category ? 1 : 0.92), angularInset: 2)
                    .foregroundStyle(item.category.color)
                    .cornerRadius(4)
                    .opacity(selected == nil || selected?.category == item.category ? 1 : 0.4)
            }
            .chartOverlay { proxy in
                GeometryReader { geo in
                    // Same hold-and-slide gesture as the line charts, so the screen keeps scrolling normally.
                    LongPressScrubView(
                        onChange: { location in
                            let frame = proxy.plotFrame.map { geo[$0] } ?? geo.frame(in: .local)
                            let angle = donutAngle(at: location, center: CGPoint(x: frame.midX, y: frame.midY))
                            let total = totals.reduce(0) { $0 + $1.minutes }
                            let value = Int(angle * Double(total))
                            if category(atAngle: value, in: totals)?.category != selected?.category { categoryAngle = value }
                        },
                        onEnd: { categoryAngle = nil }
                    )
                }
            }
            .chartBackground { proxy in
                GeometryReader { geo in
                    let frame = proxy.plotFrame.map { geo[$0] } ?? geo.frame(in: .local)
                    donutCenter(selected).position(x: frame.midX, y: frame.midY)
                }
            }
            .sensoryFeedback(.selection, trigger: selected?.category)
            .animation(.easeOut(duration: 0.15), value: selected?.category)
            .frame(width: 140, height: 140)
            VStack(alignment: .leading, spacing: 10) {
                ForEach(totals, id: \.category) { item in
                    HStack(spacing: 10) {
                        Circle().fill(item.category.color).frame(width: 10, height: 10)
                        Text(item.category.title).font(.system(size: 15)).foregroundStyle(Theme.Palette.textPrimary)
                        Spacer(minLength: 4)
                        Text("\(Int((Double(item.minutes) / sum * 100).rounded()))%")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(Theme.Palette.textSecondary)
                    }
                }
            }
        }
        .pulseCard(padding: 20)
    }

    private func donutCenter(_ selected: (category: AppCategory, minutes: Int)?) -> some View {
        VStack(spacing: 2) {
            if let selected {
                Text(Formatters.duration(minutes: selected.minutes))
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                Text(selected.category.title)
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.Palette.textSecondary)
            } else {
                Image(systemName: "hand.tap")
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.Palette.textTertiary)
            }
        }
        .lineLimit(1)
        .minimumScaleFactor(0.7)
        .frame(width: 70)
    }

    /// Fraction of a full turn, clockwise from 12 o'clock (how SectorMark lays out sectors).
    private func donutAngle(at location: CGPoint, center: CGPoint) -> Double {
        let angle = atan2(Double(location.y - center.y), Double(location.x - center.x)) + .pi / 2
        return (angle < 0 ? angle + 2 * .pi : angle) / (2 * .pi)
    }

    /// Maps the cumulative angle value picked on the donut back to its category.
    private func category(atAngle angle: Int?, in totals: [(category: AppCategory, minutes: Int)]) -> (category: AppCategory, minutes: Int)? {
        guard let angle else { return nil }
        var running = 0
        for item in totals {
            running += item.minutes
            if angle <= running { return item }
        }
        return totals.last
    }

    private var dynamicsCard: some View {
        let series = store.daily(lastDays: period)
        return VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 8) {
                ForEach([7, 14, 30], id: \.self) { days in
                    ChipButton(title: "\(days) дней", isSelected: period == days) { withAnimation { period = days } }
                }
            }
            Chart {
                ForEach(series) { item in
                    LineMark(x: .value("Дата", item.date), y: .value("Мин", item.value / 60))
                        .foregroundStyle(Theme.Palette.cyan)
                        .interpolationMethod(.catmullRom)
                    AreaMark(x: .value("Дата", item.date), y: .value("Мин", item.value / 60))
                        .foregroundStyle(LinearGradient(colors: [Theme.Palette.cyan.opacity(0.3), .clear], startPoint: .top, endPoint: .bottom))
                        .interpolationMethod(.catmullRom)
                }
                if let selected = series.first(where: { $0.date == dynamicsDay }) {
                    RuleMark(x: .value("Дата", selected.date))
                        .foregroundStyle(Theme.Palette.textSecondary.opacity(0.55))
                        .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                        .annotation(position: .top, spacing: 4, overflowResolution: .init(x: .fit(to: .chart), y: .disabled)) {
                            ChartCallout(title: Formatters.dayMonthShort(selected.date),
                                         value: Formatters.duration(minutes: Int(selected.value)), tint: Theme.Palette.cyan)
                        }
                    ScrubPoint(x: .value("Дата", selected.date), y: selected.value / 60, tint: Theme.Palette.cyan)
                }
            }
            .chartScrub($dynamicsDay) { series.nearest(to: $0)?.date }
            .chartYAxis {
                AxisMarks(position: .leading) { value in
                    AxisGridLine().foregroundStyle(Theme.Palette.divider)
                    AxisValueLabel { if let hours = value.as(Double.self) { Text("\(Int(hours)) ч") } }
                        .foregroundStyle(Theme.Palette.textSecondary)
                }
            }
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 4)) { value in
                    AxisValueLabel { if let date = value.as(Date.self) { Text(Formatters.dayMonthShort(date)) } }
                        .foregroundStyle(Theme.Palette.textSecondary)
                }
            }
            .frame(height: 170)
        }
        .pulseCard(padding: 20)
    }

    private var historyCard: some View {
        VStack(spacing: 0) {
            let days = store.week.reversed()
            ForEach(Array(days.enumerated()), id: \.element.id) { index, day in
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(Formatters.dayMonthWeekday(day.date))
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(Theme.Palette.textPrimary)
                        Text(day.topCategory.map { "Больше всего: \($0.title)" } ?? (day.minutes == 0 ? "Не введено" : "Введено вручную"))
                            .font(.system(size: 14))
                            .foregroundStyle(Theme.Palette.textSecondary)
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 4) {
                        Text(Formatters.duration(minutes: day.minutes))
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(Theme.Palette.textPrimary)
                        Text("\(day.minutes * 100 / max(store.dailyLimitMinutes, 1))% лимита")
                            .font(.system(size: 13))
                            .foregroundStyle(day.minutes > store.dailyLimitMinutes ? Theme.Palette.red : Theme.Palette.green)
                    }
                }
                .padding(.vertical, 12)
                if index < store.week.count - 1 { DividerLine() }
            }
        }
        .padding(.horizontal, 18)
        .pulseCard(padding: 0)
    }
}

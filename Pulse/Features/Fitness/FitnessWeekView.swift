import SwiftUI
import Charts

/// Mockup 3 — «Фитнес · Недельный прогресс».
struct FitnessWeekView: View {
    @Environment(FitnessStore.self) private var store
    @Environment(AppRouter.self) private var router
    @State private var selectedDay: String?

    var body: some View {
        ScreenScaffold {
            NavHeader(title: "Фитнес", subtitle: "Недельный прогресс") {
                HeaderIconButton(systemName: "calendar") { router.open(.calendar(createNew: false), in: router.selectedTab) }
                    .accessibilityLabel("Календарь")
            }
        } content: {
            PulseSection(title: "Объём за неделю") { weeklyCard }
            PulseSection(title: "Цели") { goalsCard }
            PulseSection(title: "Тело", actionTitle: "Детали") {
                router.open(.statistics, in: router.selectedTab)
            } content: {
                bodyRow
            }
            recoveryCard
        }
        .task { await store.refreshHealth() }
    }

    private var weeklyCard: some View {
        let days = store.weeklyActiveMinutes
        let total = Int(days.reduce(0) { $0 + $1.value })
        let goal = store.activeMinutesGoal * 7
        return VStack(spacing: 16) {
            HStack(alignment: .firstTextBaseline) {
                Text("Активные минуты").font(.system(size: 19)).foregroundStyle(Theme.Palette.textPrimary)
                Spacer()
                Text("\(total)").font(.system(size: 36, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
                Text("/ \(goal) мин").font(.system(size: 19)).foregroundStyle(Theme.Palette.textSecondary)
            }
            Chart {
                RuleMark(y: .value("Цель", store.activeMinutesGoal))
                    .foregroundStyle(Theme.Palette.textTertiary)
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4]))
                ForEach(days) { day in
                    let isToday = day.date.isSameDay(as: .now)
                    BarMark(x: .value("День", Formatters.weekdayShort(day.date)), y: .value("Мин", day.value), width: .ratio(0.58))
                        .foregroundStyle(isToday ? Theme.Gradients.barHighlight : Theme.Gradients.barVertical)
                        .clipShape(UnevenRoundedRectangle(topLeadingRadius: 7, topTrailingRadius: 7, style: .continuous))
                        .opacity(selectedDay == nil || selectedDay == Formatters.weekdayShort(day.date) ? 1 : 0.35)
                        .annotation(position: .top, spacing: 6) {
                            Text("\(Int(day.value))")
                                .font(.system(size: 15, weight: .medium))
                                .foregroundStyle(Theme.Palette.textSecondary)
                        }
                }
                if let label = selectedDay, let day = days.first(where: { Formatters.weekdayShort($0.date) == label }) {
                    let left = store.activeMinutesGoal - Int(day.value)
                    RuleMark(x: .value("День", label))
                        .foregroundStyle(.clear)
                        .annotation(position: .top, spacing: 0, overflowResolution: .init(x: .fit(to: .chart), y: .fit(to: .chart))) {
                            ChartCallout(title: Formatters.dayMonthWeekday(day.date), lines: [
                                .init(text: "\(Int(day.value)) активных мин", tint: Theme.Palette.cyan),
                                .init(text: left > 0 ? "до цели \(left) мин" : "цель выполнена",
                                      tint: left > 0 ? Theme.Palette.textSecondary : Theme.Palette.green)
                            ])
                        }
                }
            }
            .chartScrub($selectedDay)
            .chartYScale(domain: 0...75)
            .chartYAxis {
                AxisMarks(position: .leading, values: [0, 30, 60]) { value in
                    AxisValueLabel {
                        if let minutes = value.as(Int.self) {
                            Text(minutes == 0 ? "0\nмин" : "\(minutes)")
                                .font(.system(size: 14))
                                .foregroundStyle(Theme.Palette.textSecondary)
                        }
                    }
                }
            }
            .chartXAxis {
                AxisMarks { value in
                    AxisValueLabel {
                        if let label = value.as(String.self) {
                            let isToday = label == Formatters.weekdayShort(.now)
                            Text(label)
                                .font(.system(size: 16, weight: isToday ? .bold : .regular))
                                .foregroundStyle(isToday ? Theme.Palette.textPrimary : Theme.Palette.textSecondary)
                        }
                    }
                }
            }
            .frame(height: 205)
        }
        .pulseCard(padding: 20)
    }

    private var goalsCard: some View {
        VStack(spacing: 0) {
            GoalRow(icon: "shoeprints.fill", tint: Theme.Palette.green, title: "Шаги",
                    current: store.steps, goal: store.stepsGoal, unit: nil)
            DividerLine()
            GoalRow(icon: "bolt", tint: Theme.Palette.orange, title: "Активные минуты",
                    current: store.activeMinutesToday, goal: store.activeMinutesGoal, unit: "мин")
            DividerLine()
            GoalRow(icon: "drop", tint: Theme.Palette.red, title: "Калории",
                    current: store.kcalToday, goal: store.kcalGoal, unit: "ккал")
        }
        .padding(.horizontal, 18)
        .pulseCard(padding: 0)
    }

    /// Three tiles per row: «О тебе» plus Apple Health can make five.
    private var bodyRow: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: store.bodyMetrics.isEmpty ? 1 : 3), spacing: 12) {
            if store.bodyMetrics.isEmpty {
                Text("Вес и состав тела появятся, когда их запишут в Apple Health (умные весы или вручную).")
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.Palette.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .pulseCard(padding: 16)
            }
            ForEach(store.bodyMetrics) { metric in
                VStack(alignment: .leading, spacing: 10) {
                    Text(metric.title).font(.system(size: 17)).foregroundStyle(Theme.Palette.textSecondary)
                    ValueUnitText(value: Formatters.measure(metric.value), unit: metric.unit,
                                  valueSize: 30, unitSize: 16, monospaced: true)
                    if let delta = metric.delta {
                        Text(Formatters.signedDecimal(delta))
                            .font(.system(size: 17, design: .monospaced))
                            .foregroundStyle(Theme.Palette.green)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .pulseCard(padding: 16, radius: Theme.Metrics.tileRadius)
            }
        }
    }

    private var recoveryCard: some View {
        Button {
            router.open(.sleepRecovery, in: router.selectedTab)
        } label: {
            HStack(spacing: 18) {
                IconBadge(systemName: "sparkle", tint: Theme.Palette.blue, size: 60, iconSize: 26, background: Color(hex: 0x14284D))
                VStack(alignment: .leading, spacing: 6) {
                    Text("ВОССТАНОВЛЕНИЕ")
                        .font(.system(size: 16, weight: .semibold))
                        .tracking(2)
                        .foregroundStyle(Theme.Palette.cyan)
                    Text("Отслеживай нагрузку и самочувствие")
                        .font(.system(size: 17))
                        .foregroundStyle(Theme.Palette.textSecondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(Theme.Palette.textSecondary)
            }
            .pulseCard()
        }
        .buttonStyle(.pressable)
    }
}

struct GoalRow: View {
    let icon: String
    let tint: Color
    let title: String
    let current: Int
    let goal: Int
    let unit: String?

    var body: some View {
        let progress = StatisticsMath.clampedProgress(Double(current), goal: Double(goal))
        HStack(alignment: .center, spacing: 16) {
            IconBadge(systemName: icon, tint: tint, size: 52, iconSize: 22)
            VStack(spacing: 14) {
                HStack(alignment: .firstTextBaseline) {
                    Text(title).font(.system(size: 18)).foregroundStyle(Theme.Palette.textPrimary)
                    Spacer(minLength: 6)
                    Text(Formatters.grouped(current))
                        .font(.system(size: 17, weight: .bold, design: .monospaced))
                        .foregroundStyle(Theme.Palette.textPrimary)
                    Text("/ \(Formatters.grouped(goal))\(unit.map { " \($0)" } ?? "")")
                        .font(.system(size: 17, design: .monospaced))
                        .foregroundStyle(Theme.Palette.textSecondary)
                }
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                HStack(spacing: 14) {
                    GradientBar(progress: progress, height: 10)
                    Text("\(Int((progress * 100).rounded()))%")
                        .font(.system(size: 15, design: .monospaced))
                        .foregroundStyle(Theme.Palette.textSecondary)
                        .frame(width: 44, alignment: .trailing)
                }
            }
        }
        .padding(.vertical, 18)
    }
}

import SwiftUI
import Charts

/// Блок «Статистика питания» из ТЗ: средняя калорийность, динамика 7/30 дней, частота, время суток, дневник.
struct NutritionHistoryView: View {
    @Environment(NutritionStore.self) private var store
    @State private var period = 7
    @State private var selectedDay: Date?

    var body: some View {
        let series = store.dailyCalories(lastDays: period)
        ScreenScaffold {
            NavHeader(title: "Питание", subtitle: "Статистика и дневник")
        } content: {
            HStack(spacing: 8) {
                ChipButton(title: "7 дней", isSelected: period == 7) { withAnimation { period = 7 } }
                ChipButton(title: "30 дней", isSelected: period == 30) { withAnimation { period = 30 } }
            }
            if series.isEmpty {
                EmptyStateView(systemName: "fork.knife", title: "Нет данных",
                               message: "Добавьте первое блюдо, чтобы начать отслеживание питания.")
            } else {
                summaryRow(series: series)
                PulseSection(title: "Динамика калорийности") { lineCard(series: series) }
                PulseSection(title: "По времени суток") { dayPartsCard }
                PulseSection(title: "Дневник") { diary }
            }
        }
    }

    private func summaryRow(series: [DailyValue]) -> some View {
        let average = Int(StatisticsMath.mean(series.map(\.value)) ?? 0)
        let count = store.mealCount(lastDays: period)
        return HStack(spacing: 12) {
            tile("Среднее в день", value: Formatters.grouped(average), unit: "ккал")
            tile("Приёмов пищи", value: "\(count)", unit: "≈ \(Formatters.decimal(Double(count) / Double(max(series.count, 1)))) / день")
        }
    }

    private func tile(_ title: String, value: String, unit: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.system(size: 15)).foregroundStyle(Theme.Palette.textSecondary)
            Text(value).font(.system(size: 30, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
            Text(unit).font(.system(size: 14)).foregroundStyle(Theme.Palette.textTertiary)
        }
        .pulseCard(padding: 16, radius: Theme.Metrics.tileRadius)
    }

    private func lineCard(series: [DailyValue]) -> some View {
        Chart {
            RuleMark(y: .value("Цель", store.goals.kcal))
                .foregroundStyle(Theme.Palette.orange.opacity(0.7))
                .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4]))
            ForEach(series) { item in
                BarMark(x: .value("День", item.date, unit: .day), y: .value("Ккал", item.value))
                    .foregroundStyle(Theme.Gradients.barVertical)
                    .opacity(0.35)
                LineMark(x: .value("День", item.date, unit: .day), y: .value("Ккал", item.value))
                    .foregroundStyle(Theme.Palette.cyan)
                    .lineStyle(StrokeStyle(lineWidth: 2.5))
                    .interpolationMethod(.catmullRom)
            }
            if let selected = series.value(on: selectedDay) {
                let delta = Int(selected.value) - store.goals.kcal
                DayScrubRule(date: selected.date, callout: ChartCallout(title: Formatters.dayMonthWeekday(selected.date), lines: [
                    .init(text: "\(Formatters.grouped(Int(selected.value))) ккал", tint: Theme.Palette.cyan),
                    .init(text: delta > 0 ? "+\(Formatters.grouped(delta)) к цели" : "\(Formatters.grouped(delta)) к цели",
                          tint: delta > 0 ? Theme.Palette.orange : Theme.Palette.green)
                ]))
                ScrubPoint(x: .value("День", selected.date, unit: .day), y: selected.value, tint: Theme.Palette.cyan)
            }
        }
        .chartScrub($selectedDay) { series.nearest(to: $0)?.date }
        .chartYAxis {
            AxisMarks(position: .leading) {
                AxisGridLine().foregroundStyle(Theme.Palette.divider)
                AxisValueLabel().foregroundStyle(Theme.Palette.textSecondary)
            }
        }
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 5)) { value in
                AxisValueLabel { if let date = value.as(Date.self) { Text(Formatters.dayMonthShort(date)) } }
                    .foregroundStyle(Theme.Palette.textSecondary)
            }
        }
        .frame(height: 200)
        .pulseCard(padding: 20)
    }

    private var dayPartsCard: some View {
        let from = Date.now.startOfDay.adding(days: -period)
        let meals = store.meals.filter { $0.date >= from }
        let buckets: [(String, ClosedRange<Int>)] = [("Утро", 5...10), ("День", 11...15), ("Вечер", 16...20), ("Ночь", 21...23)]
        let counts = buckets.map { bucket in
            (bucket.0, meals.filter { bucket.1.contains(Calendar.pulse.component(.hour, from: $0.date)) }.count)
        }
        let maxCount = Double(counts.map(\.1).max() ?? 1)
        return VStack(spacing: 14) {
            ForEach(counts, id: \.0) { name, count in
                HStack(spacing: 14) {
                    Text(name).font(.system(size: 16)).foregroundStyle(Theme.Palette.textPrimary).frame(width: 60, alignment: .leading)
                    GradientBar(progress: maxCount > 0 ? Double(count) / maxCount : 0, height: 10)
                    Text("\(count)").font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.Palette.textSecondary)
                        .frame(width: 34, alignment: .trailing)
                }
            }
        }
        .pulseCard(padding: 20)
    }

    private var diary: some View {
        let meals = Array(store.meals.prefix(30))
        return VStack(spacing: 0) {
            ForEach(Array(meals.enumerated()), id: \.element.id) { index, meal in
                VStack(alignment: .leading, spacing: 2) {
                    if index == 0 || !meals[index - 1].date.isSameDay(as: meal.date) {
                        Text(Formatters.dayMonthWeekday(meal.date))
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(Theme.Palette.cyan)
                            .padding(.top, 14)
                    }
                    MealRow(meal: meal)
                }
                if index < meals.count - 1 { DividerLine() }
            }
        }
        .padding(.horizontal, 16)
        .pulseCard(padding: 0)
    }
}

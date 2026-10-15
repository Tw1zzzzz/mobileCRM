import SwiftUI
import Charts

/// «Нагрузка»: ACWR status, last-7 vs usual week and a daily load chart (hold to inspect a day).
struct TrainingLoadCard: View {
    @Environment(FitnessStore.self) private var store
    @State private var selectedDay: Date?

    var body: some View {
        let daily = store.dailyLoad()
        let ratio = TrainingLoadMath.ratio(daily: daily)
        let recent = Array(daily.suffix(14))
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .center, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("ACWR").font(.system(size: 13, weight: .semibold)).tracking(1.4).foregroundStyle(Theme.Palette.textSecondary)
                    Text(ratio.value.map { Formatters.decimal($0, fractionDigits: 2) } ?? "—")
                        .font(.system(size: 40, weight: .bold))
                        .foregroundStyle(ratio.status.color)
                        .monospacedDigit()
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text(ratio.status.title)
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(ratio.status.color)
                        .padding(.horizontal, 10).padding(.vertical, 5)
                        .background(Capsule().fill(ratio.status.color.opacity(0.15)))
                    Text("7 дней: \(Formatters.grouped(Int(ratio.acute))) · обычно \(Formatters.grouped(Int(ratio.chronicWeekly)))")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.Palette.textSecondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                }
                Spacer(minLength: 0)
            }
            chart(recent)
            Text(ratio.status.advice)
                .font(.system(size: 14))
                .foregroundStyle(Theme.Palette.textSecondary)
            Text("Нагрузка = тяжесть тренировки (1–10) × минуты. Норма ACWR — 0,8–1,3. Оцени тяжесть, нажав на тренировку.")
                .font(.system(size: 12))
                .foregroundStyle(Theme.Palette.textTertiary)
        }
        .pulseCard(padding: 18)
    }

    private func chart(_ days: [DailyValue]) -> some View {
        let selected = days.value(on: selectedDay)
        return Chart {
            ForEach(days) { day in
                BarMark(x: .value("День", day.date, unit: .day), y: .value("Нагрузка", day.value), width: .ratio(0.6))
                    .foregroundStyle(Theme.Gradients.barVertical)
                    .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
                    .opacity(selectedDay == nil || selected?.date == day.date ? 1 : 0.35)
            }
            if let selected {
                DayScrubRule(date: selected.date, callout: ChartCallout(
                    title: Formatters.dayMonthWeekday(selected.date),
                    value: selected.value > 0 ? "\(Formatters.grouped(Int(selected.value))) ед." : "отдых",
                    tint: Theme.Palette.cyan))
            }
        }
        .chartScrub($selectedDay) { days.nearest(to: $0)?.date }
        .pulseChartAxes()
        .frame(height: 140)
    }
}

/// Rate how hard a workout felt (Borg CR-10) — it drives the training load.
struct RateWorkoutSheet: View {
    let workout: Workout
    @Environment(FitnessStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var value = 5.0

    var body: some View {
        SheetScaffold(title: "Тяжесть тренировки") {
            VStack(alignment: .leading, spacing: 6) {
                Text(workout.title).font(.system(size: 18, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                Text("\(Formatters.dayMonthTime(workout.date)) · \(workout.minutes) мин")
                    .font(.system(size: 14)).foregroundStyle(Theme.Palette.textSecondary)
            }
            EffortPicker(value: $value)
            GradientButton(title: "Сохранить", systemImage: "checkmark") {
                store.rate(workout, effort: Int(value))
                dismiss()
            }
        }
        .onAppear { value = Double(store.effort(for: workout).value) }
    }
}

/// 1–10 slider with the meaning of the chosen level.
struct EffortPicker: View {
    @Binding var value: Double

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Насколько было тяжело?").font(.system(size: 16, weight: .medium)).foregroundStyle(Theme.Palette.textPrimary)
                Spacer()
                Text("\(Int(value))/10").font(.system(size: 18, weight: .bold)).foregroundStyle(tint).monospacedDigit()
            }
            Slider(value: $value, in: 1...10, step: 1).tint(tint)
            Text(Self.description(Int(value)))
                .font(.system(size: 14))
                .foregroundStyle(Theme.Palette.textSecondary)
        }
        .pulseCard(padding: 16)
        .sensoryFeedback(.selection, trigger: Int(value))
    }

    private var tint: Color {
        switch Int(value) {
        case ..<4: Theme.Palette.green
        case 4..<7: Theme.Palette.yellow
        case 7..<9: Theme.Palette.orange
        default: Theme.Palette.red
        }
    }

    static func description(_ value: Int) -> String {
        switch value {
        case ..<2: "Очень легко — почти как отдых"
        case 2..<4: "Легко — можно спокойно разговаривать"
        case 4..<6: "Умеренно — дыхание учащено, но комфортно"
        case 6..<8: "Тяжело — говорить трудно"
        case 8..<10: "Очень тяжело — на пределе"
        default: "Максимум — больше сделать невозможно"
        }
    }
}

/// «Восстановление» 0–100: HRV and resting pulse against the personal norm, sleep and energy.
struct RecoveryCard: View {
    @Environment(FitnessStore.self) private var fitness
    @Environment(SleepStore.self) private var sleep
    @Environment(WellbeingStore.self) private var wellbeing

    var body: some View {
        if let result = RecoveryMath.compute(input) {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 18) {
                    ScoreRing(progress: Double(result.score) / 100, value: "\(result.score)", caption: "ВОССТ.",
                              size: 104, lineWidth: 10, valueSize: 36)
                    VStack(alignment: .leading, spacing: 8) {
                        Text(result.label)
                            .font(.system(size: 20, weight: .bold))
                            .foregroundStyle(result.color)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                        Text(advice(result))
                            .font(.system(size: 14))
                            .foregroundStyle(Theme.Palette.textSecondary)
                    }
                }
                VStack(spacing: 12) {
                    ForEach(result.components) { component in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text(component.title).font(.system(size: 14, weight: .medium)).foregroundStyle(Theme.Palette.textPrimary)
                                Spacer()
                                Text(component.detail).font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
                                    .lineLimit(1).minimumScaleFactor(0.75)
                            }
                            GradientBar(progress: component.value / 100, height: 6)
                        }
                    }
                }
                if fitness.health == nil && sleep.isDemo {
                    Text("Демо-данные. С Apple Health и часами показатели будут твоими.")
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.Palette.textTertiary)
                }
            }
            .pulseCard(padding: 18)
        } else {
            Text("Недостаточно данных: нужен сон и хотя бы один показатель пульса из Apple Health.")
                .font(.system(size: 14))
                .foregroundStyle(Theme.Palette.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .pulseCard(padding: 16)
        }
    }

    private func advice(_ result: RecoveryMath.Result) -> String {
        let load = fitness.loadRatio.status
        switch (result.score, load) {
        case (..<34, _): return "Сегодня лучше лёгкая тренировка или отдых и ранний отбой."
        case (_, .high): return "Восстановление в норме, но нагрузка резко выросла — не увеличивай объём."
        case (67..., _): return "Можно тренироваться в полную силу."
        default: return "Подойдёт тренировка средней интенсивности."
        }
    }

    /// Personal norm = average of the previous days (today excluded).
    private var input: RecoveryMath.Input {
        let night = sleep.displayNight
        let energy = (wellbeing.averageMood(on: .now) ?? wellbeing.averageMood(on: Date.now.adding(days: -1)))?.energy
        guard let health = fitness.health else {
            guard sleep.isDemo else { return RecoveryMath.Input(sleepScore: night?.score, energy: energy) }
            return RecoveryMath.Input(hrv: Double(night?.hrv ?? 58), hrvBaseline: 55,
                                      restingHeartRate: Double(night?.restingHeartRate ?? 56), restingBaseline: 58,
                                      sleepScore: night?.score, energy: energy)
        }
        let previousHRV = health.hrvByDay.filter { !$0.date.isSameDay(as: .now) }.map(\.value)
        let previousRHR = health.restingHeartRateByDay.filter { !$0.date.isSameDay(as: .now) }.map(\.value)
        return RecoveryMath.Input(
            hrv: night?.hrv.map(Double.init) ?? health.hrvByDay.last?.value,
            hrvBaseline: StatisticsMath.mean(previousHRV),
            restingHeartRate: night?.restingHeartRate.map(Double.init) ?? health.restingHeartRate.map(Double.init),
            restingBaseline: StatisticsMath.mean(previousRHR),
            sleepScore: night?.score,
            energy: energy
        )
    }
}

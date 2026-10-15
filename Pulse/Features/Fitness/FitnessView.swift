import SwiftUI

/// Mockup 4 — «Фитнес» (сегодня): кольцо активности, тренировки, зоны пульса.
struct FitnessView: View {
    @Environment(FitnessStore.self) private var store
    @Environment(AppRouter.self) private var router
    @State private var showLogWorkout = false
    @State private var ratingWorkout: Workout?

    var body: some View {
        ScreenScaffold {
            NavHeader(title: "Фитнес") {
                HeaderIconButton(systemName: "plus") { showLogWorkout = true }
                    .accessibilityLabel("Записать тренировку")
            }
        } content: {
            HealthSourceCard()
            heroCard
            PulseSection(title: "Восстановление") { RecoveryCard() }
            PulseSection(title: "Тренировки сегодня", actionTitle: "История") {
                router.open(.fitnessWeek, in: router.selectedTab)
            } content: {
                workoutsCard
            }
            PulseSection(title: "Нагрузка") { TrainingLoadCard() }
            PulseSection(title: "Зоны пульса") {
                if let zones = store.heartRateZones, let average = store.averageHeartRate {
                    HeartRateZonesCard(zones: zones, average: average)
                } else {
                    NoHeartRateCard()
                }
            }
        }
        .task { await store.refreshHealth() }
        .refreshable { await store.refreshHealth() }
        .sheet(isPresented: $showLogWorkout) {
            LogWorkoutSheet { store.add($0) }
                .presentationDetents([.large])
        }
        .sheet(item: $ratingWorkout) { workout in
            RateWorkoutSheet(workout: workout).presentationDetents([.medium, .large])
        }
    }

    private var heroCard: some View {
        let minutes = store.activeMinutesToday
        let goal = store.activeMinutesGoal
        return VStack(spacing: 20) {
            HStack(spacing: 18) {
                ScoreRing(progress: Double(minutes) / Double(goal), value: "\(minutes)", caption: "АКТ. МИН",
                          size: 124, lineWidth: 12, valueSize: 46)
                DividerLine(vertical: true).frame(height: 80)
                VStack(alignment: .leading, spacing: 10) {
                    Text("СЕГОДНЯ")
                        .font(.system(size: 15, weight: .semibold))
                        .tracking(2)
                        .foregroundStyle(Theme.Palette.cyan)
                    Text("\(minutes) / \(goal) мин")
                        .font(.system(size: 28, weight: .bold))
                        .foregroundStyle(Theme.Palette.textPrimary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    Text(minutes >= goal ? "Цель выполнена 🎉" : "Ещё \(goal - minutes) мин до цели")
                        .font(.system(size: 16))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .foregroundStyle(Theme.Palette.textSecondary)
                }
                Spacer(minLength: 0)
            }
            HStack(spacing: 10) {
                miniTile(icon: "flame", tint: Theme.Palette.orange, value: "\(store.kcalToday)", unit: "ккал")
                miniTile(icon: "shoe", tint: Theme.Palette.blue, value: Formatters.decimal(store.distanceKm), unit: "км")
                miniTile(icon: "shoeprints.fill", tint: Theme.Palette.green, value: Formatters.grouped(store.steps), unit: "шагов")
            }
        }
        .padding(.vertical, 4)
        .pulseCard(padding: 16)
    }

    private func miniTile(icon: String, tint: Color, value: String, unit: String) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 22))
                .foregroundStyle(tint)
            ValueUnitText(value: value, unit: unit, valueSize: 21, unitSize: 14, minimumScale: 0.55)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, minHeight: 88, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Theme.Palette.background.opacity(0.35)))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Theme.Palette.stroke, lineWidth: 1))
    }

    private var workoutsCard: some View {
        VStack(spacing: 0) {
            if store.todayWorkouts.isEmpty {
                Text("Сегодня тренировок ещё не было")
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.Palette.textSecondary)
                    .padding(.vertical, 18)
            }
            ForEach(Array(store.todayWorkouts.enumerated()), id: \.element.id) { index, workout in
                Button { ratingWorkout = workout } label: {
                    WorkoutRow(workout: workout, effort: store.effort(for: workout))
                }
                .buttonStyle(.plain)
                .accessibilityHint("Оценить тяжесть тренировки")
                if index < store.todayWorkouts.count - 1 { DividerLine() }
            }
            GradientButton(title: "Записать тренировку", systemImage: "plus") { showLogWorkout = true }
                .padding(.top, 16)
        }
        .pulseCard()
    }
}

struct WorkoutRow: View {
    let workout: Workout
    /// Shown as «RPE 7»; estimated values get a «≈».
    var effort: (value: Int, isEstimated: Bool)?

    var body: some View {
        HStack(spacing: 14) {
            IconBadge(systemName: workout.kind.icon, tint: workout.kind.tint, size: 50, iconSize: 22)
            VStack(alignment: .leading, spacing: 6) {
                Text(workout.title)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                Text(workout.averageHeartRate > 0
                     ? "\(workout.minutes) мин · \(workout.kcal) ккал · \(workout.averageHeartRate) уд/мин"
                     : "\(workout.minutes) мин · \(workout.kcal) ккал")
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.Palette.textSecondary)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 4)
            VStack(alignment: .trailing, spacing: 6) {
                Text(Formatters.time(workout.date))
                    .font(.system(size: 15, design: .monospaced))
                    .foregroundStyle(Theme.Palette.textSecondary)
                if let effort {
                    Text("\(effort.isEstimated ? "≈" : "")RPE \(effort.value)")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(effort.isEstimated ? Theme.Palette.textSecondary : Theme.Palette.cyan)
                        .padding(.horizontal, 7).padding(.vertical, 3)
                        .background(Capsule().fill(Theme.Palette.track))
                }
            }
        }
        .padding(.vertical, 12)
        .contentShape(Rectangle())
    }
}

struct HeartRateZonesCard: View {
    let zones: [HeartRateZone]
    let average: Int

    var body: some View {
        VStack(spacing: 18) {
            HStack(spacing: 14) {
                Image(systemName: "heart")
                    .font(.system(size: 24, weight: .medium))
                    .foregroundStyle(Theme.Palette.red)
                Text("Средний пульс")
                    .font(.system(size: 18, weight: .medium))
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                    .foregroundStyle(Theme.Palette.textPrimary)
                Spacer(minLength: 6)
                ValueUnitText(value: "\(average)", unit: "уд/мин", valueSize: 32, unitSize: 16)
                    .fixedSize()
            }
            GeometryReader { geo in
                HStack(spacing: 0) {
                    ForEach(zones) { zone in
                        Rectangle().fill(zone.color).frame(width: geo.size.width * CGFloat(zone.percent) / 100)
                    }
                }
                .clipShape(Capsule())
            }
            .frame(height: 14)
            VStack(spacing: 12) {
                ForEach(zones) { zone in
                    HStack {
                        Circle().fill(zone.color).frame(width: 14, height: 14)
                        Text(zone.name)
                            .font(.system(size: 17))
                            .foregroundStyle(Theme.Palette.textPrimary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                            .padding(.leading, 12)
                        Spacer()
                        Text(zone.range)
                            .font(.system(size: 16))
                            .foregroundStyle(Theme.Palette.textSecondary)
                            .lineLimit(1)
                            .fixedSize()
                        Text("\(zone.percent)%")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(Theme.Palette.textPrimary)
                            .frame(width: 52, alignment: .trailing)
                    }
                }
            }
        }
        .pulseCard(padding: 20)
    }
}

struct LogWorkoutSheet: View {
    let onSave: (Workout) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var kind: WorkoutKind = .strength
    @State private var detail = ""
    @State private var date = Date.now
    @State private var minutes = 30.0
    @State private var kcal = 200.0
    @State private var heartRate = 130.0
    @State private var effort = 6.0

    var body: some View {
        SheetScaffold(title: "Новая тренировка") {
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ForEach(WorkoutKind.allCases) { item in
                        ChipButton(title: item.title, isSelected: item == kind) { kind = item }
                    }
                }
            }
            .scrollIndicators(.hidden)
            PulseTextField(title: "Описание", text: $detail, placeholder: "например, верх / ноги")
            VStack(spacing: 16) {
                DatePicker("Время", selection: $date)
                    .foregroundStyle(Theme.Palette.textPrimary)
                PulseSlider(title: "Длительность, мин", value: $minutes, range: 5...180)
                PulseSlider(title: "Калории, ккал", value: $kcal, range: 10...1500, tint: Theme.Palette.orange)
                PulseSlider(title: "Средний пульс", value: $heartRate, range: 60...200, tint: Theme.Palette.red)
            }
            .pulseCard()
            EffortPicker(value: $effort)
            GradientButton(title: "Сохранить тренировку") {
                onSave(Workout(
                    id: UUID(), kind: kind, detail: detail.trimmingCharacters(in: .whitespaces), date: date,
                    minutes: Int(minutes), kcal: Int(kcal), averageHeartRate: Int(heartRate), rpe: Int(effort)
                ))
                dismiss()
            }
        }
    }
}

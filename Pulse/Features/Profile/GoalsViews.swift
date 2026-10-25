import SwiftUI

/// Which goal editor is open from Профиль → Цели.
enum GoalEditor: String, Identifiable {
    case fitness, nutrition, sleep, screen, game

    var id: String { rawValue }
}

/// Профиль → «Цели»: one row per area with the current targets; a tap opens its editor.
struct GoalsSection: View {
    @Environment(FitnessStore.self) private var fitness
    @Environment(NutritionStore.self) private var nutrition
    @Environment(SleepStore.self) private var sleep
    @Environment(ScreenTimeStore.self) private var screenTime
    @Environment(GameGoalsStore.self) private var gameGoals

    @State private var editor: GoalEditor?

    var body: some View {
        PulseSection(title: "Цели") {
            VStack(spacing: 0) {
                row(.game, icon: "gamecontroller", tint: Theme.Palette.cyan, title: "Игра", value: gameSummary)
                DividerLine()
                row(.fitness, icon: "figure.run", tint: Theme.Palette.orange, title: "Фитнес",
                    value: "\(fitness.goals.activeMinutes) мин · \(Formatters.grouped(fitness.goals.steps)) шагов · \(Formatters.grouped(fitness.goals.activeKcal)) ккал")
                DividerLine()
                row(.nutrition, icon: "fork.knife", tint: Theme.Palette.green, title: "Питание",
                    value: "\(Formatters.grouped(nutrition.goals.kcal)) ккал · вода \(Formatters.liters(milliliters: nutrition.goals.waterMilliliters)) л")
                DividerLine()
                row(.sleep, icon: "moon", tint: Theme.Palette.purple, title: "Сон",
                    value: "\(sleep.window.bedtimeText) → \(sleep.window.wakeText) · \(Formatters.duration(minutes: sleep.window.durationMinutes))")
                DividerLine()
                row(.screen, icon: "hourglass", tint: Theme.Palette.cyan, title: "Экранное время",
                    value: "не больше \(screenTime.dailyLimitMinutes / 60) ч в день")
            }
            .padding(.horizontal, 16)
            .pulseCard(padding: 0)
        }
        .sheet(item: $editor) { editor in
            Group {
                switch editor {
                case .fitness: FitnessGoalsSheet()
                case .nutrition: NutritionGoalsSheet()
                case .sleep: SleepWindowEditor(window: sleep.window) { sleep.updateWindow($0) }
                case .screen: ScreenLimitSheet()
                case .game: GameGoalsSheet()
                }
            }
            .presentationDetents([.large])
        }
    }

    private var gameSummary: String {
        let goals = gameGoals.goals
        let parts = [
            goals.elo.map { "ELO \(Formatters.grouped($0))" },
            goals.kd.map { "K/D \(Formatters.decimal($0, fractionDigits: 2))" },
            goals.adr.map { "ADR \(Formatters.decimal($0))" },
            goals.winRate.map { "победы \($0)%" }
        ].compactMap { $0 }
        return parts.isEmpty ? "Не заданы" : parts.joined(separator: " · ")
    }

    private func row(_ editor: GoalEditor, icon: String, tint: Color, title: String, value: String) -> some View {
        Button { self.editor = editor } label: {
            HStack(spacing: 14) {
                IconBadge(systemName: icon, tint: tint, size: 36, iconSize: 16)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 16)).foregroundStyle(Theme.Palette.textPrimary)
                    Text(value).font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
                        .lineLimit(1).minimumScaleFactor(0.75)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.Palette.textTertiary)
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Progress towards the player's CS2 targets (Профиль and «Состояние → Игра»).
struct GameGoalsCard: View {
    @Environment(GameGoalsStore.self) private var store
    @Environment(SessionStore.self) private var session
    @Environment(GameStore.self) private var game
    var onEdit: () -> Void

    var body: some View {
        let player = game.profile(for: session.user)
        let recent = GameSummary(matches: game.recentMatches(for: session.user, count: GameGoals.recentMatches))
        let rows = game.hasData ? store.progress(elo: player.elo, recent: recent) : []
        VStack(alignment: .leading, spacing: 16) {
            if !game.hasData && !store.goals.isEmpty {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "link").font(.system(size: 20)).foregroundStyle(Theme.Palette.orange)
                    Text("Цели сохранены. Подключи Faceit в «Состояние → Игра», чтобы видеть прогресс по матчам.")
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.Palette.textSecondary)
                }
            } else if rows.isEmpty {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "target").font(.system(size: 22)).foregroundStyle(Theme.Palette.cyan)
                    Text("Поставь цель — например, ELO 3 000 или K/D 1,2 — и следи за прогрессом.")
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.Palette.textSecondary)
                }
                GradientButton(title: "Поставить цель", systemImage: "target", action: onEdit)
            } else {
                ForEach(rows) { row in
                    GoalProgressRow(row: row)
                }
                Text("K/D, ADR и победы — по последним \(GameGoals.recentMatches) матчам")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.Palette.textTertiary)
            }
        }
        .pulseCard(padding: 16)
    }
}

struct GoalProgressRow: View {
    let row: GoalProgress

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(row.title).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                Spacer()
                Text(row.current).font(.system(size: 17, weight: .bold)).foregroundStyle(tint).monospacedDigit()
                Text("/ \(row.target)").font(.system(size: 15)).foregroundStyle(Theme.Palette.textSecondary).monospacedDigit()
            }
            GradientBar(progress: row.progress, height: 8,
                        fill: row.isReached ? AnyShapeStyle(Theme.Palette.green) : AnyShapeStyle(Theme.Gradients.accent))
            Text(row.note).font(.system(size: 13)).foregroundStyle(row.isReached ? Theme.Palette.green : Theme.Palette.textSecondary)
        }
        .accessibilityElement(children: .combine)
    }

    private var tint: Color { row.isReached ? Theme.Palette.green : Theme.Palette.cyan }
}

// MARK: - Editors

/// Slider for fractional targets (K/D) with a formatted value.
struct DecimalSlider: View {
    let title: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    let step: Double
    var digits = 1
    var suffix = ""
    var tint: Color = Theme.Palette.cyan

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(title).font(.system(size: 16, weight: .medium)).foregroundStyle(Theme.Palette.textPrimary)
                Spacer()
                Text((digits == 0 ? Formatters.grouped(Int(value.rounded())) : Formatters.decimal(value, fractionDigits: digits)) + suffix)
                    .font(.system(size: 18, weight: .bold)).foregroundStyle(tint).monospacedDigit()
            }
            Slider(value: $value, in: range, step: step).tint(tint)
        }
    }
}

struct GameGoalsSheet: View {
    @Environment(GameGoalsStore.self) private var store
    @Environment(SessionStore.self) private var session
    @Environment(GameStore.self) private var game
    @Environment(\.dismiss) private var dismiss

    @State private var useElo = false
    @State private var elo = 3000.0
    @State private var useKD = false
    @State private var kd = 1.1
    @State private var useADR = false
    @State private var adr = 85.0
    @State private var useWinRate = false
    @State private var winRate = 55.0

    var body: some View {
        let player = game.profile(for: session.user)
        let recent = GameSummary(matches: game.recentMatches(for: session.user, count: GameGoals.recentMatches))
        SheetScaffold(title: "Игровые цели") {
            Text("Включи нужные цели. Сейчас: ELO \(Formatters.grouped(player.elo)) · K/D \(Formatters.decimal(recent.kd, fractionDigits: 2)) · ADR \(Formatters.decimal(recent.adr)) · победы \(Int(recent.winRate.rounded()))%")
                .font(.system(size: 14))
                .foregroundStyle(Theme.Palette.textSecondary)
            goalCard("ELO Faceit", isOn: $useElo) {
                DecimalSlider(title: "Цель", value: $elo, range: eloRange(current: player.elo), step: 25, digits: 0)
            }
            goalCard("K/D", isOn: $useKD) {
                DecimalSlider(title: "Цель", value: $kd, range: 0.5...2.5, step: 0.05, digits: 2, tint: Theme.Palette.green)
            }
            goalCard("ADR", isOn: $useADR) {
                DecimalSlider(title: "Цель", value: $adr, range: 50...130, step: 1, digits: 0, tint: Theme.Palette.orange)
            }
            goalCard("Процент побед", isOn: $useWinRate) {
                DecimalSlider(title: "Цель", value: $winRate, range: 30...90, step: 1, digits: 0, suffix: "%", tint: Theme.Palette.purple)
            }
            GradientButton(title: "Сохранить", systemImage: "checkmark") {
                store.update(GameGoals(
                    elo: useElo ? Int(elo) : nil,
                    kd: useKD ? kd : nil,
                    adr: useADR ? adr : nil,
                    winRate: useWinRate ? Int(winRate) : nil
                ), currentElo: player.elo)
                dismiss()
            }
        }
        .onAppear { populate(currentElo: player.elo) }
    }

    /// Targets start just above the current ELO, rounded to 25.
    private func eloRange(current: Int) -> ClosedRange<Double> {
        let low = Double((current / 25 + 1) * 25)
        return low...max(low + 25, low + 1500)
    }

    private func populate(currentElo: Int) {
        let goals = store.goals
        useElo = goals.elo != nil
        elo = Double(goals.elo ?? (currentElo / 100 + 2) * 100)
        useKD = goals.kd != nil
        kd = goals.kd ?? 1.1
        useADR = goals.adr != nil
        adr = goals.adr ?? 85
        useWinRate = goals.winRate != nil
        winRate = Double(goals.winRate ?? 55)
    }

    private func goalCard<Content: View>(_ title: String, isOn: Binding<Bool>, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Toggle(isOn: isOn.animation(.easeInOut(duration: 0.2))) {
                Text(title).font(.system(size: 17, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
            }
            .tint(Theme.Palette.blue)
            if isOn.wrappedValue { content() }
        }
        .pulseCard(padding: 16)
    }
}

struct FitnessGoalsSheet: View {
    @Environment(FitnessStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var minutes = 60.0
    @State private var steps = 10_000.0
    @State private var kcal = 600.0

    var body: some View {
        SheetScaffold(title: "Цели фитнеса") {
            VStack(spacing: 18) {
                PulseSlider(title: "Активные минуты в день", value: $minutes, range: 10...180, tint: Theme.Palette.orange)
                DecimalSlider(title: "Шаги в день", value: $steps, range: 2000...30000, step: 500, digits: 0, tint: Theme.Palette.green)
                DecimalSlider(title: "Активные калории, ккал", value: $kcal, range: 100...2000, step: 50, digits: 0, tint: Theme.Palette.red)
            }
            .pulseCard(padding: 18)
            Text("ВОЗ рекомендует 150–300 минут умеренной активности в неделю — это 30–60 минут в день.")
                .font(.system(size: 13))
                .foregroundStyle(Theme.Palette.textTertiary)
            GradientButton(title: "Сохранить", systemImage: "checkmark") {
                store.updateGoals(FitnessGoals(activeMinutes: Int(minutes), steps: Int(steps), activeKcal: Int(kcal)))
                dismiss()
            }
        }
        .onAppear {
            minutes = Double(store.goals.activeMinutes)
            steps = Double(store.goals.steps)
            kcal = Double(store.goals.activeKcal)
        }
    }
}

struct NutritionGoalsSheet: View {
    @Environment(NutritionStore.self) private var nutrition
    @Environment(\.dismiss) private var dismiss
    @State private var kcal = 2400.0
    @State private var protein = 140.0
    @State private var fat = 80.0
    @State private var carbs = 300.0
    @State private var water = 2.5

    var body: some View {
        SheetScaffold(title: "Цели питания") {
            VStack(spacing: 18) {
                DecimalSlider(title: "Калории, ккал", value: $kcal, range: 1200...4500, step: 50, digits: 0)
                PulseSlider(title: "Белки, г", value: $protein, range: 40...300, tint: Theme.Palette.cyan)
                PulseSlider(title: "Жиры, г", value: $fat, range: 20...200, tint: Theme.Palette.orange)
                PulseSlider(title: "Углеводы, г", value: $carbs, range: 50...600, tint: Theme.Palette.purple)
                DecimalSlider(title: "Вода, л", value: $water, range: 1...5, step: 0.25, digits: 2)
            }
            .pulseCard(padding: 18)
            GradientButton(title: "Сохранить", systemImage: "checkmark") {
                nutrition.updateGoals(NutritionGoals(
                    kcal: Int(kcal), protein: Int(protein), fat: Int(fat), carbs: Int(carbs),
                    waterMilliliters: Int(water * 1000)
                ))
                dismiss()
            }
        }
        .onAppear {
            let goals = nutrition.goals
            kcal = Double(goals.kcal)
            protein = Double(goals.protein)
            fat = Double(goals.fat)
            carbs = Double(goals.carbs)
            water = Double(goals.waterMilliliters) / 1000
        }
    }
}

struct ScreenLimitSheet: View {
    @Environment(ScreenTimeStore.self) private var screenTime
    @Environment(\.dismiss) private var dismiss
    @State private var hours = 5.0

    var body: some View {
        SheetScaffold(title: "Экранное время") {
            PulseSlider(title: "Лимит в день, ч", value: $hours, range: 1...12, tint: Theme.Palette.cyan)
                .pulseCard(padding: 18)
            GradientButton(title: "Сохранить", systemImage: "checkmark") {
                screenTime.dailyLimitMinutes = Int(hours) * 60
                dismiss()
            }
        }
        .onAppear { hours = Double(screenTime.dailyLimitMinutes) / 60 }
    }
}

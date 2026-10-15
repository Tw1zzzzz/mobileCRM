import SwiftUI

/// «Настроение и энергия»: дни недели с тремя полосками-слотами, блоки утро/день/вечер.
struct MoodEnergyView: View {
    @Environment(WellbeingStore.self) private var store
    @State private var selectedDay = Date.now.startOfDay
    @State private var editingSlot: DaySlot?

    private var weekDays: [Date] {
        let start = Date.now.startOfWeek
        return (0..<7).map { start.adding(days: $0) }
    }

    var body: some View {
        ScreenScaffold {
            NavHeader(title: "Настроение и энергия")
        } content: {
            weekStrip
            Text(Formatters.dayMonthWeekday(selectedDay))
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(Theme.Palette.textPrimary)
                .padding(.horizontal, 4)
            VStack(spacing: 12) {
                ForEach(DaySlot.allCases) { slot in slotCard(slot) }
            }
        }
        .sheet(item: $editingSlot) { slot in
            MoodEntrySheet(day: selectedDay, slot: slot, existing: store.moodEntry(on: selectedDay, slot: slot)) { mood, energy in
                store.saveMood(day: selectedDay, slot: slot, mood: mood, energy: energy)
            }
            .presentationDetents([.medium])
        }
    }

    private var weekStrip: some View {
        HStack(spacing: 6) {
            ForEach(weekDays, id: \.self) { day in
                let isSelected = day.isSameDay(as: selectedDay)
                let filled = store.filledSlots(on: day)
                let isFuture = day > Date.now
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) { selectedDay = day }
                } label: {
                    VStack(spacing: 8) {
                        Text(Formatters.weekdayShort(day)).font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
                        Text("\(Calendar.pulse.component(.day, from: day))")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundStyle(isSelected ? .white : Theme.Palette.textPrimary)
                        VStack(spacing: 3) {
                            ForEach(DaySlot.allCases) { slot in
                                Capsule()
                                    .fill(filled.contains(slot) ? Theme.Palette.cyan : Theme.Palette.track)
                                    .frame(width: 22, height: 4)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(isSelected ? AnyShapeStyle(Theme.Palette.blue.opacity(0.35)) : AnyShapeStyle(Theme.Palette.tile))
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .strokeBorder(isSelected ? Theme.Palette.cyan : Theme.Palette.stroke, lineWidth: 1)
                    )
                    .opacity(isFuture ? 0.45 : 1)
                }
                .buttonStyle(.plain)
                .disabled(isFuture)
            }
        }
    }

    /// Title row with the action on top; the two scales get their own full-width row
    /// (side by side with the button they squeezed the text into one letter per line).
    private func slotCard(_ slot: DaySlot) -> some View {
        let entry = store.moodEntry(on: selectedDay, slot: slot)
        return VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 16) {
                IconBadge(systemName: slot.icon, tint: entry == nil ? Theme.Palette.textSecondary : Theme.Palette.cyan, size: 50, iconSize: 22)
                VStack(alignment: .leading, spacing: 4) {
                    Text(slot.title).font(.system(size: 18, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                    if entry == nil {
                        Text("Нет данных").font(.system(size: 14)).foregroundStyle(Theme.Palette.textTertiary)
                    }
                }
                Spacer(minLength: 8)
                Button(entry == nil ? "Добавить" : "Изменить") { editingSlot = slot }
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(entry == nil ? .white : Theme.Palette.cyan)
                    .lineLimit(1)
                    .fixedSize()
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .background(Capsule().fill(entry == nil ? AnyShapeStyle(Theme.Gradients.accent) : AnyShapeStyle(Theme.Palette.track)))
            }
            if let entry {
                HStack(spacing: 16) {
                    metric("Настроение", entry.mood, Theme.Palette.yellow)
                    metric("Энергия", entry.energy, Theme.Palette.green)
                }
            }
        }
        .pulseCard(padding: 16)
    }

    private func metric(_ title: String, _ value: Int, _ tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(title).font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
                Spacer(minLength: 4)
                Text("\(value)/10").font(.system(size: 14, weight: .semibold)).foregroundStyle(tint)
            }
            .lineLimit(1)
            GradientBar(progress: Double(value) / 10, height: 6, fill: AnyShapeStyle(tint))
        }
        .frame(maxWidth: .infinity)
    }
}

struct MoodEntrySheet: View {
    let day: Date
    let slot: DaySlot
    let existing: MoodEntry?
    let onSave: (Int, Int) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var mood = 6.0
    @State private var energy = 6.0

    var body: some View {
        SheetScaffold(title: "\(slot.title) · \(Formatters.dayMonth(day))") {
            VStack(spacing: 24) {
                PulseSlider(title: "Настроение \(emoji(for: mood))", value: $mood, tint: Theme.Palette.yellow)
                PulseSlider(title: "Энергия ⚡️", value: $energy, tint: Theme.Palette.green)
            }
            .pulseCard(padding: 20)
            GradientButton(title: "Сохранить") {
                onSave(Int(mood), Int(energy))
                dismiss()
            }
        }
        .onAppear {
            if let existing {
                mood = Double(existing.mood)
                energy = Double(existing.energy)
            }
        }
    }

    private func emoji(for value: Double) -> String {
        switch value {
        case ..<4: "😞"
        case ..<6: "😐"
        case ..<8: "🙂"
        default: "😄"
        }
    }
}

/// «Колесо баланса»: выбор даты, 8 ползунков, живая радарная диаграмма, сохранение на дату.
struct BalanceWheelView: View {
    @Environment(WellbeingStore.self) private var store
    @State private var day = Date.now.startOfDay
    @State private var values: [BalanceCategory: Double] = [:]
    @State private var savedToast = false

    var body: some View {
        ScreenScaffold {
            NavHeader(title: "Колесо баланса")
        } content: {
            dateSelector
            RadarChart(labels: BalanceCategory.allCases.map(\.shortTitle), values: orderedValues)
                .frame(height: 320)
                .pulseCard(padding: 12)
            VStack(spacing: 18) {
                ForEach(BalanceCategory.allCases) { category in
                    PulseSlider(title: category.title, value: binding(for: category), range: 0...10)
                }
            }
            .pulseCard(padding: 20)
            GradientButton(title: savedToast ? "Сохранено ✓" : "Сохранить", systemImage: savedToast ? nil : "checkmark") {
                store.saveBalance(day: day, values: values)
                withAnimation { savedToast = true }
                Task { @MainActor in
                    try? await Task.sleep(for: .seconds(1.5))
                    withAnimation { savedToast = false }
                }
            }
        }
        .onAppear { load() }
        .onChange(of: day) { _, _ in load() }
    }

    private var orderedValues: [Double] {
        BalanceCategory.allCases.map { values[$0] ?? 5 }
    }

    private var dateSelector: some View {
        HStack {
            HeaderIconButton(systemName: "chevron.left") { day = day.adding(days: -1) }
            Spacer()
            VStack(spacing: 2) {
                Text(Formatters.dayMonthWeekday(day)).font(.system(size: 17, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                Text(store.balanceEntry(on: day) == nil ? "Нет сохранённых данных" : "Данные сохранены")
                    .font(.system(size: 13))
                    .foregroundStyle(store.balanceEntry(on: day) == nil ? Theme.Palette.textTertiary : Theme.Palette.green)
            }
            Spacer()
            HeaderIconButton(systemName: "chevron.right") { day = day.adding(days: 1) }
                .disabled(day.isSameDay(as: .now))
                .opacity(day.isSameDay(as: .now) ? 0.4 : 1)
        }
    }

    private func binding(for category: BalanceCategory) -> Binding<Double> {
        Binding(
            get: { values[category] ?? 5 },
            set: { newValue in values = values.merging([category: newValue]) { _, new in new } }
        )
    }

    private func load() {
        let entry = store.balanceEntry(on: day) ?? store.latestBalance
        values = Dictionary(uniqueKeysWithValues: BalanceCategory.allCases.map { ($0, entry?.value(for: $0) ?? 5) })
    }
}

/// «Игровые тесты»: результаты до/после тренировки с ручным редактированием.
struct GameTestsView: View {
    @Environment(CognitiveStore.self) private var store
    @State private var editing: GameTestResult?

    var body: some View {
        ScreenScaffold {
            NavHeader(title: "Игровые тесты", subtitle: "До и после тренировки")
        } content: {
            Text("Результаты приходят из уведомлений перед и после тренировки. Нажми на запись, чтобы поправить вручную.")
                .font(.system(size: 15))
                .foregroundStyle(Theme.Palette.textSecondary)
            VStack(spacing: 12) {
                ForEach(store.gameResults) { result in
                    Button { editing = result } label: { row(result) }
                        .buttonStyle(.pressable)
                }
            }
        }
        .sheet(item: $editing) { result in
            GameTestEditSheet(result: result) { store.updateGameResult($0) }
                .presentationDetents([.medium])
        }
    }

    private func row(_ result: GameTestResult) -> some View {
        let isBefore = result.phase == .beforeTraining
        let tint = isBefore ? Theme.Palette.cyan : Theme.Palette.orange
        return VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text(result.phase.title)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(tint)
                    .lineLimit(1)
                    .fixedSize()
                    .padding(.horizontal, 10).padding(.vertical, 5)
                    .background(Capsule().fill(tint.opacity(0.14)))
                Spacer()
                Text(Formatters.dayMonthTime(result.date))
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.Palette.textSecondary)
                    .lineLimit(1)
            }
            HStack {
                score("Фокус", result.focus)
                Spacer()
                score("Усталость", result.fatigue)
                Spacer()
                score("Готовность", result.readiness)
            }
        }
        .pulseCard(padding: 16)
    }

    private func score(_ title: String, _ value: Int) -> some View {
        VStack(spacing: 2) {
            Text("\(value)").font(.system(size: 22, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
            Text(title).font(.system(size: 12)).foregroundStyle(Theme.Palette.textSecondary)
        }
        .frame(minWidth: 80)
    }
}

struct GameTestEditSheet: View {
    let result: GameTestResult
    let onSave: (GameTestResult) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var focus = 5.0
    @State private var fatigue = 5.0
    @State private var readiness = 5.0

    var body: some View {
        SheetScaffold(title: result.phase.title) {
            VStack(spacing: 20) {
                PulseSlider(title: "Фокус", value: $focus)
                PulseSlider(title: "Усталость", value: $fatigue, tint: Theme.Palette.orange)
                PulseSlider(title: "Готовность", value: $readiness, tint: Theme.Palette.green)
            }
            .pulseCard(padding: 20)
            GradientButton(title: "Сохранить изменения") {
                onSave(GameTestResult(id: result.id, date: result.date, phase: result.phase,
                                      focus: Int(focus), fatigue: Int(fatigue), readiness: Int(readiness)))
                dismiss()
            }
        }
        .onAppear {
            focus = Double(result.focus)
            fatigue = Double(result.fatigue)
            readiness = Double(result.readiness)
        }
    }
}

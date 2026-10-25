import SwiftUI

/// Блоки 1–2 из ТЗ: кнопка «Начать / Завершить сон» и текущая сессия с живым таймером.
struct SleepTrackingCard: View {
    @Environment(SleepStore.self) private var store
    @State private var lastSaved: SleepSession?
    @State private var addingNight = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let start = store.activeStart {
                activeSession(start: start)
            } else {
                idleState
            }
            GradientButton(
                title: store.isSleeping ? "Завершить сон" : "Начать сон",
                systemImage: store.isSleeping ? "sun.max" : "moon.zzz"
            ) {
                withAnimation(.spring(duration: 0.4)) {
                    if store.isSleeping {
                        lastSaved = store.stopSleep()
                    } else {
                        lastSaved = nil
                        store.startSleep()
                    }
                }
            }
            // Forgot to press «Начать сон»: the night can still go into the history.
            if !store.isSleeping {
                SecondaryButton(title: "Добавить ночь вручную", systemImage: "pencil") { addingNight = true }
            }
            if let lastSaved {
                StatusPill(
                    text: "Сохранено · \(Formatters.duration(minutes: lastSaved.durationMinutes))",
                    tint: lastSaved.isValid ? Theme.Palette.green : Theme.Palette.orange
                )
            }
            Label(store.isSleeping
                  ? "Таймер сна виден на экране блокировки — там же кнопка «Проснулся»."
                  : "Вечером придёт напоминание «Скоро отбой» — сон можно начать прямо из уведомления.",
                  systemImage: store.isSleeping ? "lock.iphone" : "bell.badge")
                .font(.system(size: 13))
                .foregroundStyle(Theme.Palette.textTertiary)
        }
        .pulseCard()
        .sensoryFeedback(.success, trigger: store.isSleeping)
        .sheet(isPresented: $addingNight) {
            AddNightSheet()
                .presentationDetents([.large])
        }
    }

    private var idleState: some View {
        HStack(spacing: 14) {
            IconBadge(systemName: "bed.double", tint: Theme.Palette.purple, size: 48)
            VStack(alignment: .leading, spacing: 4) {
                Text("Сон не запущен")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                Text("Нажми, когда ложишься спать")
                    .font(.system(size: 15))
                    .foregroundStyle(Theme.Palette.textSecondary)
            }
        }
    }

    private func activeSession(start: Date) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Circle().fill(Theme.Palette.green).frame(width: 10, height: 10)
                Text("Сон активен")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Theme.Palette.green)
                Spacer()
                Text("с \(Formatters.time(start))")
                    .font(.system(size: 15))
                    .foregroundStyle(Theme.Palette.textSecondary)
            }
            TimelineView(.periodic(from: .now, by: 1)) { context in
                Text(Formatters.timer(context.date.timeIntervalSince(start)))
                    .font(.system(size: 48, weight: .bold, design: .rounded))
                    .foregroundStyle(Theme.Palette.textPrimary)
                    .monospacedDigit()
                    .contentTransition(.numericText())
            }
        }
    }
}

/// Блок 4 из ТЗ: дата, начало, окончание, длительность, статус.
struct SleepHistoryList: View {
    let sessions: [SleepSession]

    var body: some View {
        if sessions.isEmpty {
            EmptyStateView(systemName: "moon.zzz", title: "Записей пока нет", message: "Начни первую сессию сна или добавь прошлую ночь вручную.")
        } else {
            VStack(spacing: 0) {
                ForEach(Array(sessions.enumerated()), id: \.element.id) { index, session in
                    row(session)
                    if index < sessions.count - 1 { DividerLine() }
                }
            }
            .pulseCard(padding: 4)
        }
    }

    private func row(_ session: SleepSession) -> some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text(Formatters.dayMonth(session.end))
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                Text("\(Formatters.time(session.start)) → \(Formatters.time(session.end))")
                    .font(.system(size: 15))
                    .foregroundStyle(Theme.Palette.textSecondary)
                    .monospacedDigit()
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                Text(Formatters.duration(minutes: session.durationMinutes))
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                    .monospacedDigit()
                Text(session.statusTitle)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(session.isValid ? Theme.Palette.green : Theme.Palette.orange)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }
}

/// «Добавить ночь»: wake day, bedtime and wake time of a night tracked without «Начать сон».
struct AddNightSheet: View {
    @Environment(SleepStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var now = Date.now
    @State private var daysAgo = 0
    @State private var bedtime = Date.now
    @State private var wake = Date.now
    @State private var error: String?
    @State private var prepared = false

    private var night: ManualNight {
        let calendar = Calendar.pulse
        return ManualNight(wakeDaysAgo: daysAgo,
                           bedHour: calendar.component(.hour, from: bedtime), bedMinute: calendar.component(.minute, from: bedtime),
                           wakeHour: calendar.component(.hour, from: wake), wakeMinute: calendar.component(.minute, from: wake))
    }

    var body: some View {
        SheetScaffold(title: "Добавить ночь") {
            VStack(alignment: .leading, spacing: 10) {
                Text("Проснулся").font(.system(size: 15)).foregroundStyle(Theme.Palette.textSecondary)
                HStack(spacing: 8) {
                    ForEach(Array(ManualNight.days.enumerated()), id: \.offset) { index, title in
                        ChipButton(title: title, isSelected: daysAgo == index) { daysAgo = index; error = nil }
                    }
                }
            }
            VStack(spacing: 14) {
                DatePicker("Отбой", selection: $bedtime, displayedComponents: .hourAndMinute)
                DividerLine()
                DatePicker("Подъём", selection: $wake, displayedComponents: .hourAndMinute)
            }
            .font(.system(size: 18))
            .foregroundStyle(Theme.Palette.textPrimary)
            .pulseCard()
            let interval = night.interval(now: now)
            Text("Сон: \(Formatters.duration(minutes: max(Int(interval.end.timeIntervalSince(interval.start) / 60), 0)))")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Theme.Palette.textPrimary)
            if let error {
                Text(error).font(.system(size: 13)).foregroundStyle(Theme.Palette.red)
            }
            GradientButton(title: "Сохранить") {
                error = store.addNight(night)
                if error == nil { dismiss() }
            }
        }
        .onAppear {
            guard !prepared else { return }
            prepared = true
            let suggested = ManualNight.suggested(window: store.window, now: now)
            daysAgo = suggested.wakeDaysAgo
            bedtime = now.setting(hour: suggested.bedHour, minute: suggested.bedMinute)
            wake = now.setting(hour: suggested.wakeHour, minute: suggested.wakeMinute)
        }
        .onChange(of: bedtime) { _, _ in error = nil }
        .onChange(of: wake) { _, _ in error = nil }
    }
}

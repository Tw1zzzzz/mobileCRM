import SwiftUI

/// Блоки 1–2 из ТЗ: кнопка «Начать / Завершить сон» и текущая сессия с живым таймером.
struct SleepTrackingCard: View {
    @Environment(SleepStore.self) private var store
    @State private var lastSaved: SleepSession?

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
            EmptyStateView(systemName: "moon.zzz", title: "Записей пока нет", message: "Начни первую сессию сна, чтобы увидеть историю.")
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

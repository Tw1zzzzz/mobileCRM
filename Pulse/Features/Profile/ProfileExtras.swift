import SwiftUI

/// Настройка профиля (п. 15 ТЗ): имя и Faceit. Цели редактируются в «Профиль → Цели».
struct EditProfileSheet: View {
    @Environment(SessionStore.self) private var session
    @Environment(\.dismiss) private var dismiss

    @State private var nickname = ""
    @State private var faceit = ""
    @State private var error: String?

    var body: some View {
        SheetScaffold(title: "Профиль") {
            PulseTextField(title: "Имя *", text: $nickname, placeholder: "Nova")
            PulseTextField(title: "Ссылка Faceit", text: $faceit, placeholder: "https://www.faceit.com/…", keyboard: .URL)
            if !session.isStaff {
                Label("Цели по игре, фитнесу, питанию, сну и экранному времени — в разделе «Цели» профиля.",
                      systemImage: "target")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.Palette.textTertiary)
            }
            if let error { ErrorBanner(message: error) }
            GradientButton(title: "Сохранить", systemImage: "checkmark") { save() }
        }
        .onAppear {
            nickname = session.user?.nickname ?? ""
            faceit = session.user?.faceitURL ?? ""
        }
    }

    private func save() {
        if let message = session.updateProfile(nickname: nickname, faceitURL: faceit) {
            withAnimation { error = message }
            return
        }
        dismiss()
    }
}

/// Инструменты для показа MVP без сервера: смена роли, тестовые уведомления, сброс данных.
struct DemoModeSection: View {
    @Environment(SessionStore.self) private var session
    @Environment(CalendarStore.self) private var calendar

    @State private var notice: String?
    @State private var confirmReset = false

    var body: some View {
        PulseSection(title: "Демо-режим") {
            VStack(alignment: .leading, spacing: 14) {
                Text("Сменить роль без выхода из аккаунта")
                    .font(.system(size: 14)).foregroundStyle(Theme.Palette.textSecondary)
                HStack(spacing: 8) {
                    ForEach([UserRole.teamPlayer, .soloPlayer, .staff]) { role in
                        ChipButton(title: role.badgeTitle, isSelected: session.user?.role == role) {
                            Task { await session.switchToDemo(role: role) }
                        }
                    }
                }
                DividerLine()
                if session.user?.role.isStaff == false {
                    demoButton("Пуш «Игровой тест до тренировки»", icon: "gamecontroller") {
                        let sent = await NotificationScheduler.sendDemoGameTest(phase: .beforeTraining)
                        return sent ? "Уведомление придёт через 5 секунд" : nil
                    }
                    demoButton("Пуш «Скоро отбой» (начать сон)", icon: "moon.zzz") {
                        let sent = await SleepNotifications.sendDemoBedtime(window: AppDependencies.shared.sleep.window)
                        return sent ? "Заблокируй телефон — пуш придёт через 5 секунд" : nil
                    }
                }
                demoButton("Пуш «Напоминание о событии»", icon: "calendar.badge.clock") {
                    guard let event = calendar.events.first(where: { $0.start > .now }) ?? calendar.events.first else { return nil }
                    let sent = await NotificationScheduler.sendDemoEventReminder(for: event)
                    return sent ? "Уведомление придёт через 5 секунд" : nil
                }
                demoButton("Сбросить демо-данные", icon: "arrow.counterclockwise", tint: Theme.Palette.red) {
                    confirmReset = true
                    return nil
                }
                if let notice {
                    StatusPill(text: notice, tint: Theme.Palette.cyan)
                }
            }
            .pulseCard()
        }
        .confirmationDialog("Вернуть все демо-данные к исходным?", isPresented: $confirmReset, titleVisibility: .visible) {
            Button("Сбросить", role: .destructive) {
                AppDependencies.shared.resetDemoData()
                show("Демо-данные восстановлены")
            }
        }
    }

    private func demoButton(
        _ title: String, icon: String, tint: Color = Theme.Palette.cyan,
        action: @escaping () async -> String?
    ) -> some View {
        Button {
            Task {
                if let message = await action() {
                    show(message)
                } else if title.hasPrefix("Пуш") {
                    show("Разрешите уведомления в настройках iPhone")
                }
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: icon).frame(width: 22).foregroundStyle(tint)
                Text(title).font(.system(size: 15, weight: .medium)).foregroundStyle(Theme.Palette.textPrimary)
                Spacer()
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func show(_ message: String) {
        withAnimation { notice = message }
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(4))
            withAnimation { if notice == message { notice = nil } }
        }
    }
}

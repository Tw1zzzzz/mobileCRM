import AppIntents
import UserNotifications
import os

/// Local reminders 1 hour before calendar events (spec: «Уведомление о событии»).
enum NotificationScheduler {
    static let eventIDKey = "eventID"
    private static let logger = Logger(subsystem: "com.pulse.mobile", category: "Notifications")

    static func requestAuthorization() async -> Bool {
        #if DEBUG
        // UI review on fresh simulators: `-PulseNoPrompts` skips the system permission prompt.
        if ProcessInfo.processInfo.arguments.contains("-PulseNoPrompts") { return false }
        #endif
        do {
            return try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
        } catch {
            logger.error("Notification authorization failed: \(error.localizedDescription, privacy: .public)")
            return false
        }
    }

    static func scheduleReminder(for event: CalendarEvent, now: Date = .now) async {
        let fireDate = event.start.addingTimeInterval(-3600)
        cancelReminder(for: event.id)
        guard fireDate > now else { return }
        guard await requestAuthorization() else { return }

        let content = UNMutableNotificationContent()
        content.title = event.title
        content.body = "Через час · \(Formatters.time(event.start))–\(Formatters.time(event.end))"
        content.sound = .default
        content.userInfo = [eventIDKey: event.id.uuidString]

        let components = Calendar.pulse.dateComponents([.year, .month, .day, .hour, .minute], from: fireDate)
        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
        let request = UNNotificationRequest(identifier: event.id.uuidString, content: content, trigger: trigger)
        do {
            try await UNUserNotificationCenter.current().add(request)
        } catch {
            logger.error("Failed to schedule reminder: \(error.localizedDescription, privacy: .public)")
        }
    }

    static func cancelReminder(for id: UUID) {
        let ids = [id.uuidString] + GameTestPhase.allCases.map { GameTestSchedule.identifier(eventID: id, phase: $0) }
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ids)
    }

    static let gameTestPhaseKey = "gameTestPhase"

    /// «Игровые тесты»: push до (за 15 мин) и после (через 5 мин) каждой тренировки.
    static func scheduleGameTests(for events: [CalendarEvent], now: Date = .now) async {
        let triggers = GameTestSchedule.triggers(for: events, now: now)
        guard !triggers.isEmpty else { return }
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        guard settings.authorizationStatus == .authorized else { return }
        for trigger in triggers {
            let content = gameTestContent(phase: trigger.phase, eventTitle: trigger.eventTitle)
            let components = Calendar.pulse.dateComponents([.year, .month, .day, .hour, .minute], from: trigger.date)
            await add(UNNotificationRequest(
                identifier: trigger.identifier,
                content: content,
                trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
            ))
        }
    }

    /// Demo mode: fire a notification in a few seconds to show the flow live.
    static func sendDemoGameTest(phase: GameTestPhase, after seconds: TimeInterval = 5) async -> Bool {
        guard await requestAuthorization() else { return false }
        await add(UNNotificationRequest(
            identifier: "demo-game-\(UUID().uuidString)",
            content: gameTestContent(phase: phase, eventTitle: "Тренировка"),
            trigger: UNTimeIntervalNotificationTrigger(timeInterval: seconds, repeats: false)
        ))
        return true
    }

    static func sendDemoEventReminder(for event: CalendarEvent, after seconds: TimeInterval = 5) async -> Bool {
        guard await requestAuthorization() else { return false }
        let content = UNMutableNotificationContent()
        content.title = event.title
        content.body = "Через час · \(Formatters.time(event.start))–\(Formatters.time(event.end))"
        content.sound = .default
        content.userInfo = [eventIDKey: event.id.uuidString]
        await add(UNNotificationRequest(
            identifier: "demo-event-\(UUID().uuidString)",
            content: content,
            trigger: UNTimeIntervalNotificationTrigger(timeInterval: seconds, repeats: false)
        ))
        return true
    }

    private static func gameTestContent(phase: GameTestPhase, eventTitle: String) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        switch phase {
        case .beforeTraining:
            content.title = "Игровой тест перед тренировкой"
            content.body = "«\(eventTitle)» скоро начнётся. Оцени фокус и готовность — 20 секунд."
        case .afterTraining:
            content.title = "Игровой тест после тренировки"
            content.body = "Как прошла «\(eventTitle)»? Отметь фокус и усталость."
        }
        content.sound = .default
        content.userInfo = [gameTestPhaseKey: phase.rawValue]
        return content
    }

    private static func add(_ request: UNNotificationRequest) async {
        do {
            try await UNUserNotificationCenter.current().add(request)
        } catch {
            logger.error("Failed to schedule \(request.identifier, privacy: .public): \(error.localizedDescription, privacy: .public)")
        }
    }
}

/// Pure scheduling rules for game-test notifications (unit-tested).
enum GameTestSchedule {
    struct Trigger: Equatable {
        let identifier: String
        let date: Date
        let phase: GameTestPhase
        let eventTitle: String
    }

    static let minutesBefore = 15
    static let minutesAfter = 5
    static let horizonDays = 7

    static func isTraining(_ event: CalendarEvent) -> Bool {
        event.title.lowercased().contains("тренировк")
    }

    static func identifier(eventID: UUID, phase: GameTestPhase) -> String {
        "game-\(phase.rawValue)-\(eventID.uuidString)"
    }

    static func triggers(for events: [CalendarEvent], now: Date) -> [Trigger] {
        let horizon = now.adding(days: horizonDays)
        return events.filter(isTraining).flatMap { event -> [Trigger] in
            [
                Trigger(identifier: identifier(eventID: event.id, phase: .beforeTraining),
                        date: event.start.adding(minutes: -minutesBefore), phase: .beforeTraining, eventTitle: event.title),
                Trigger(identifier: identifier(eventID: event.id, phase: .afterTraining),
                        date: event.end.adding(minutes: minutesAfter), phase: .afterTraining, eventTitle: event.title)
            ]
        }
        .filter { $0.date > now && $0.date <= horizon }
    }
}

/// Start/stop sleep without opening the app: available in Shortcuts, Siri, Spotlight
/// and assignable to the Action Button / Lock Screen controls.
struct ToggleSleepIntent: AppIntent {
    static var title: LocalizedStringResource = "Начать / завершить сон"
    static var description = IntentDescription("Фиксирует начало или окончание сна в Pulse.")
    static var openAppWhenRun = false

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let store = AppDependencies.shared.sleep
        if store.isSleeping {
            let session = store.stopSleep()
            let text = session.map { "Сон завершён: \(Formatters.duration(minutes: $0.durationMinutes))" } ?? "Сессия сна сброшена"
            return .result(dialog: IntentDialog(stringLiteral: text))
        }
        store.startSleep()
        return .result(dialog: "Сон начат в \(Formatters.time(.now)). Спокойной ночи!")
    }
}

struct PulseShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: ToggleSleepIntent(),
            phrases: [
                "Начать сон в \(.applicationName)",
                "Завершить сон в \(.applicationName)",
                "Toggle sleep in \(.applicationName)"
            ],
            shortTitle: "Сон",
            systemImageName: "moon.zzz"
        )
    }
}

/// Repeating Sunday 19:00 «Итоги недели готовы» → opens the weekly report.
enum WeeklyNotifications {
    static let routeKey = "weeklyReport"
    static let identifier = "weekly-report"

    static func schedule() async {
        let content = UNMutableNotificationContent()
        content.title = "Итоги недели готовы"
        content.body = "Сон, нагрузка, настроение и игра — сравнение с прошлой неделей и план на следующую."
        content.sound = .default
        content.userInfo = [routeKey: true]
        var components = DateComponents()
        components.weekday = 1 // Sunday
        components.hour = 19
        components.minute = 0
        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: true)
        do {
            try await UNUserNotificationCenter.current()
                .add(UNNotificationRequest(identifier: identifier, content: content, trigger: trigger))
        } catch {
            Logger(subsystem: "com.pulse.mobile", category: "Notifications")
                .error("Failed to schedule weekly report: \(error.localizedDescription, privacy: .public)")
        }
    }
}

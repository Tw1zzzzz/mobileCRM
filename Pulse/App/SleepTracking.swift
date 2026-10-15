import ActivityKit
import UserNotifications
import os

/// App-side handler for the Live Activity «Проснулся» button (see Shared/SleepActivity.swift).
enum SleepTrackingBridge {
    @MainActor
    static func stopSleep() async {
        AppDependencies.shared.sleep.stopSleep()
    }
}

/// Keeps the lock screen and notifications in sync with the sleep session,
/// whoever started it: the app, Shortcuts / Action Button, a notification or the Live Activity.
@MainActor
enum SleepTrackingCoordinator {
    static func trackingChanged(activeStart: Date?, window: SleepWindow) {
        if let activeStart {
            SleepLiveActivity.start(at: activeStart, window: window)
            Task { await SleepNotifications.scheduleWakeCheck(after: activeStart, window: window) }
        } else {
            Task { await SleepLiveActivity.endAll() }
            SleepNotifications.cancelWakeCheck()
        }
    }

    /// On launch: restore a Live Activity for a session that survived an app restart, or clear a stale one.
    static func sync(store: SleepStore) {
        trackingChanged(activeStart: store.activeStart, window: store.window)
    }
}

enum SleepLiveActivity {
    private static let logger = Logger(subsystem: "com.pulse.mobile", category: "SleepLiveActivity")

    @MainActor
    static func start(at start: Date, window: SleepWindow) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let existing = Activity<SleepActivityAttributes>.activities
        if existing.contains(where: { $0.attributes.startedAt == start && $0.activityState == .active }) { return }
        for stale in existing {
            Task { await stale.end(nil, dismissalPolicy: .immediate) }
        }
        let state = SleepActivityAttributes.ContentState(alarmText: window.wakeText)
        do {
            _ = try Activity.request(
                attributes: SleepActivityAttributes(startedAt: start),
                content: ActivityContent(state: state, staleDate: nil)
            )
        } catch {
            logger.error("Live Activity request failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    static func endAll() async {
        for activity in Activity<SleepActivityAttributes>.activities {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
    }
}

/// «Пора спать» (with «Начать сон») and the morning «Завершить сон?» check.
enum SleepNotifications {
    static let bedtimeCategory = "SLEEP_BEDTIME"
    static let wakeCategory = "SLEEP_WAKE"
    static let startAction = "START_SLEEP"
    static let stopAction = "STOP_SLEEP"
    static let bedtimeID = "sleep-bedtime"
    static let wakeCheckID = "sleep-wake-check"
    static let routeKey = "openSleep"
    /// Reminder lead time before the planned bedtime.
    static let bedtimeLeadMinutes = 15
    /// The wake check fires a little after the planned alarm, in case the user forgot to stop tracking.
    static let wakeCheckDelayMinutes = 30

    private static let logger = Logger(subsystem: "com.pulse.mobile", category: "SleepNotifications")

    static func registerCategories() {
        let start = UNNotificationAction(identifier: startAction, title: "🌙 Начать сон", options: [])
        let stop = UNNotificationAction(identifier: stopAction, title: "☀️ Я проснулся", options: [])
        UNUserNotificationCenter.current().setNotificationCategories([
            UNNotificationCategory(identifier: bedtimeCategory, actions: [start], intentIdentifiers: []),
            UNNotificationCategory(identifier: wakeCategory, actions: [stop], intentIdentifiers: [])
        ])
    }

    /// Daily reminder before the planned bedtime from the sleep window.
    static func scheduleBedtimeReminder(window: SleepWindow) async {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [bedtimeID])
        guard await center.notificationSettings().authorizationStatus == .authorized else { return }
        let time = bedtimeReminderTime(for: window)
        let trigger = UNCalendarNotificationTrigger(dateMatching: DateComponents(hour: time.hour, minute: time.minute), repeats: true)
        await add(UNNotificationRequest(identifier: bedtimeID, content: bedtimeContent(window: window), trigger: trigger))
    }

    /// Bedtime minus the lead time, wrapped around midnight.
    static func bedtimeReminderTime(for window: SleepWindow) -> (hour: Int, minute: Int) {
        let total = (window.bedtimeHour * 60 + window.bedtimeMinute - bedtimeLeadMinutes + 24 * 60) % (24 * 60)
        return (total / 60, total % 60)
    }

    /// First planned wake-up after the session start, plus a grace period.
    static func wakeCheckDate(after start: Date, window: SleepWindow) -> Date {
        var wake = start.setting(hour: window.wakeHour, minute: window.wakeMinute)
        if wake <= start { wake = wake.adding(days: 1) }
        return wake.adding(minutes: wakeCheckDelayMinutes)
    }

    static func scheduleWakeCheck(after start: Date, window: SleepWindow) async {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [wakeCheckID])
        guard await center.notificationSettings().authorizationStatus == .authorized else { return }
        let fireDate = wakeCheckDate(after: start, window: window)
        let content = UNMutableNotificationContent()
        content.title = "Доброе утро ☀️"
        content.body = "Сон всё ещё отслеживается с \(Formatters.time(start)). Нажми «Я проснулся», чтобы сохранить ночь."
        content.sound = .default
        content.categoryIdentifier = wakeCategory
        content.userInfo = [routeKey: true]
        let components = Calendar.pulse.dateComponents([.year, .month, .day, .hour, .minute], from: fireDate)
        await add(UNNotificationRequest(identifier: wakeCheckID, content: content,
                                        trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: false)))
    }

    static func cancelWakeCheck() {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [wakeCheckID])
        center.removeDeliveredNotifications(withIdentifiers: [wakeCheckID])
    }

    /// Профиль → Демо-режим: show the bedtime push in a few seconds.
    static func sendDemoBedtime(window: SleepWindow, after seconds: TimeInterval = 5) async -> Bool {
        guard await NotificationScheduler.requestAuthorization() else { return false }
        await add(UNNotificationRequest(identifier: "demo-bedtime-\(UUID().uuidString)", content: bedtimeContent(window: window),
                                        trigger: UNTimeIntervalNotificationTrigger(timeInterval: seconds, repeats: false)))
        return true
    }

    private static func bedtimeContent(window: SleepWindow) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = "Скоро отбой 🌙"
        content.body = "План — лечь в \(window.bedtimeText). Нажми «Начать сон», когда ляжешь: таймер появится на экране блокировки."
        content.sound = .default
        content.categoryIdentifier = bedtimeCategory
        content.userInfo = [routeKey: true]
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

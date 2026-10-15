import ActivityKit
import AppIntents
import Foundation

/// Live Activity «Сон отслеживается»: lock screen / Dynamic Island timer while a sleep session runs.
/// Compiled into both the app and the widget extension.
struct SleepActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// Planned wake-up time from the sleep window, e.g. "07:00".
        var alarmText: String
    }

    var startedAt: Date
}

/// «Проснулся» button on the Live Activity. LiveActivityIntent runs in the app process,
/// so it reaches the real sleep store through `SleepTrackingBridge` (a no-op stub in the extension).
struct StopSleepActivityIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Завершить сон"
    static var description = IntentDescription("Завершает текущую сессию сна в Pulse.")

    func perform() async throws -> some IntentResult {
        await SleepTrackingBridge.stopSleep()
        return .result()
    }
}

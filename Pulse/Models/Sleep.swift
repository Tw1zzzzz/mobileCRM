import SwiftUI

enum SleepStage: Int, Codable, CaseIterable, Identifiable {
    case awake, rem, light, deep

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .awake: "Бодрствование"
        case .rem: "REM"
        case .light: "Лёгкий"
        case .deep: "Глубокий"
        }
    }

    var color: Color {
        switch self {
        case .awake: Theme.Palette.orange
        case .rem: Theme.Palette.purple
        case .light: Theme.Palette.lightBlue
        case .deep: Theme.Palette.blue
        }
    }

    /// Vertical position on the hypnogram (0 = top).
    var level: CGFloat {
        switch self {
        case .awake: 0.14
        case .rem: 0.50
        case .light: 0.635
        case .deep: 0.83
        }
    }
}

struct SleepStageSegment: Identifiable, Equatable {
    let id = UUID()
    let stage: SleepStage
    let start: Date
    let end: Date

    var minutes: Int { Int(end.timeIntervalSince(start) / 60) }
}

/// Detailed night from the tracker (phases, body metrics): Apple Health when connected, demo otherwise.
struct SleepNight {
    let start: Date
    let end: Date
    let score: Int
    let baselineDelta: Int
    let segments: [SleepStageSegment]
    let cycles: Int
    let efficiency: Int
    let latencyMinutes: Int
    let awakenings: Int
    let heartRate: [Int]
    /// Body metrics need a watch / tracker; nil = not recorded that night.
    let restingHeartRate: Int?
    let minHeartRate: Int?
    let heartRateBaselineDelta: Int?
    let respiratoryRate: Int?
    let hrv: Int?
    let spo2: Int?
    /// Sleep phases are only recorded by a watch / tracker.
    var hasStages = true

    /// Neutral stand-in used only for layout when there is no night yet (never shown as data).
    static let placeholder = SleepNight(
        start: .now, end: .now, score: 0, baselineDelta: 0, segments: [], cycles: 0, efficiency: 0,
        latencyMinutes: 0, awakenings: 0, heartRate: [], restingHeartRate: nil, minHeartRate: nil,
        heartRateBaselineDelta: nil, respiratoryRate: nil, hrv: nil, spo2: nil, hasStages: false
    )

    /// Time actually asleep (without awake segments).
    var asleepMinutes: Int { totalMinutes - minutes(in: .awake) }

    var totalMinutes: Int { Int(end.timeIntervalSince(start) / 60) }

    func minutes(in stage: SleepStage) -> Int {
        segments.filter { $0.stage == stage }.reduce(0) { $0 + $1.minutes }
    }

    var averageCycleMinutes: Int {
        guard cycles > 0 else { return 0 }
        return (totalMinutes - minutes(in: .awake)) / cycles
    }

    var scoreLabel: String {
        switch score {
        case 85...: "Отлично"
        case 70..<85: "Хорошо"
        case 50..<70: "Средне"
        default: "Плохо"
        }
    }
}

/// A manually tracked sleep session (start/stop button in the app or via Shortcuts / Action Button).
struct SleepSession: Codable, Identifiable, Equatable {
    let id: UUID
    let start: Date
    let end: Date

    static let minimumValidMinutes = 60

    var durationMinutes: Int { max(Int(end.timeIntervalSince(start) / 60), 0) }
    var isValid: Bool { durationMinutes >= Self.minimumValidMinutes }
    var statusTitle: String { isValid ? "Валидная" : "Неполная" }
}

struct SleepWindow: Codable, Equatable {
    var bedtimeHour = 23
    var bedtimeMinute = 0
    var wakeHour = 7
    var wakeMinute = 0
    var smartAlarmEnabled = true

    var bedtimeText: String { String(format: "%02d:%02d", bedtimeHour, bedtimeMinute) }
    var wakeText: String { String(format: "%02d:%02d", wakeHour, wakeMinute) }

    var durationMinutes: Int {
        let bed = bedtimeHour * 60 + bedtimeMinute
        let wake = wakeHour * 60 + wakeMinute
        let diff = wake - bed
        return diff > 0 ? diff : diff + 24 * 60
    }
}

/// One bar in the weekly stacked chart (deep / REM / light minutes).
struct SleepWeekDay: Identifiable {
    let id = UUID()
    let date: Date
    let deep: Int
    let rem: Int
    let light: Int

    var total: Int { deep + rem + light }
}

/// «Добавить ночь»: a night entered by hand when «Начать сон» was forgotten (Kotlin `ManualNight`). `wakeDaysAgo`
/// picks the wake day (0 = «Сегодня»); a bedtime at or after the wake time belongs to the evening before.
struct ManualNight: Equatable {
    var wakeDaysAgo: Int
    var bedHour: Int
    var bedMinute: Int
    var wakeHour: Int
    var wakeMinute: Int

    static let days = ["Сегодня", "Вчера", "Позавчера"]
    static let durationMinutes = 60...16 * 60

    func interval(now: Date) -> (start: Date, end: Date) {
        let wakeDay = now.adding(days: -wakeDaysAgo)
        let end = wakeDay.setting(hour: wakeHour, minute: wakeMinute)
        let bedDay = bedHour * 60 + bedMinute >= wakeHour * 60 + wakeMinute ? wakeDay.adding(days: -1) : wakeDay
        return (bedDay.setting(hour: bedHour, minute: bedMinute), end)
    }

    /// The planned window, waking today once this morning's wake time has passed, yesterday before that.
    static func suggested(window: SleepWindow, now: Date) -> ManualNight {
        let woke = now >= now.setting(hour: window.wakeHour, minute: window.wakeMinute)
        return ManualNight(wakeDaysAgo: woke ? 0 : 1, bedHour: window.bedtimeHour, bedMinute: window.bedtimeMinute,
                           wakeHour: window.wakeHour, wakeMinute: window.wakeMinute)
    }
}

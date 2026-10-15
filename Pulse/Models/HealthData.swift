import SwiftUI

/// One day of activity read from Apple Health.
struct HealthDaySummary: Equatable {
    var steps: Int
    var distanceKm: Double
    var activeKcal: Int
    /// Apple «Упражнения» minutes (watch) or, without a watch, minutes of brisk walking.
    var activeMinutes: Int
}

/// Everything the fitness screens need from Apple Health, fetched in one pass.
struct HealthSnapshot: Equatable {
    var today: HealthDaySummary
    /// Active minutes per day, oldest first, last element is today.
    var weeklyActiveMinutes: [DailyValue]
    var workouts: [Workout]
    /// Share of today's heart-rate samples per zone; nil when there are no samples (no watch).
    var heartRateZones: [HeartRateZone]?
    var averageHeartRate: Int?
    var restingHeartRate: Int?
    var hrv: Int?
    var bodyMetrics: [BodyMetric]
    var fetchedAt: Date
    /// Raw sleep intervals for the last 30 days (phases need a watch / tracker).
    var sleepIntervals: [SleepInterval] = []
    /// Body metrics recorded during the most recent night.
    var nightVitals: SleepMath.NightVitals?
    /// Daily averages for the last 30 days — personal baselines for recovery.
    var restingHeartRateByDay: [DailyValue] = []
    var hrvByDay: [DailyValue] = []
    /// Workouts of the last 28 days for training load (`workouts` keeps the last 7 for the screens).
    var loadWorkouts: [Workout] = []

    static func == (lhs: HealthSnapshot, rhs: HealthSnapshot) -> Bool {
        lhs.today == rhs.today && lhs.weeklyActiveMinutes == rhs.weeklyActiveMinutes
            && lhs.workouts == rhs.workouts && lhs.averageHeartRate == rhs.averageHeartRate
            && lhs.restingHeartRate == rhs.restingHeartRate && lhs.hrv == rhs.hrv && lhs.fetchedAt == rhs.fetchedAt
            && lhs.sleepIntervals == rhs.sleepIntervals && lhs.restingHeartRateByDay == rhs.restingHeartRateByDay
            && lhs.hrvByDay == rhs.hrvByDay && lhs.loadWorkouts == rhs.loadWorkouts
    }
}

/// Pure formulas behind the fitness numbers (unit-tested).
enum FitnessMath {
    /// A minute with at least this many steps counts as brisk walking (≈ moderate intensity).
    static let briskStepsPerMinute = 100.0
    /// Rough active energy per step for a 70 kg person.
    static let kcalPerStep = 0.04
    static let referenceWeightKg = 70.0
    /// Typical age of esports players, used when Health has no birth date.
    static let defaultAge = 21

    static func briskMinutes(stepsPerMinute: [Double]) -> Int {
        stepsPerMinute.filter { $0 >= briskStepsPerMinute }.count
    }

    /// Fallback when the device does not record active energy.
    static func estimatedActiveKcal(steps: Int, weightKg: Double?) -> Int {
        let weight = weightKg ?? referenceWeightKg
        return Int((Double(steps) * kcalPerStep * weight / referenceWeightKg).rounded())
    }

    /// Tanaka formula: 208 − 0.7 × age.
    static func maxHeartRate(age: Int) -> Double {
        208 - 0.7 * Double(age)
    }

    static func age(birthDate: DateComponents?, now: Date = .now) -> Int {
        guard let birthDate, let date = Calendar.pulse.date(from: birthDate) else { return defaultAge }
        let years = Calendar.pulse.dateComponents([.year], from: date, to: now).year ?? defaultAge
        return (10...90).contains(years) ? years : defaultAge
    }

    /// Zone boundaries as a share of max heart rate, matching the mockup's five zones.
    static let zoneBounds: [(name: String, range: String, upper: Double, color: Color)] = [
        ("Покой", "< 60%", 0.6, Theme.Palette.restZone),
        ("Жиросжигание", "60–70%", 0.7, Theme.Palette.cyan),
        ("Кардио", "70–80%", 0.8, Theme.Palette.green),
        ("Пик", "80–90%", 0.9, Theme.Palette.orange),
        ("Максимум", "> 90%", .infinity, Theme.Palette.red)
    ]

    static func zoneIndex(bpm: Double, maxHeartRate: Double) -> Int {
        let share = bpm / maxHeartRate
        return zoneBounds.firstIndex { share < $0.upper } ?? zoneBounds.count - 1
    }

    /// Percent of samples in each zone; percentages sum to 100 (largest-remainder rounding).
    static func zones(samples: [Double], maxHeartRate: Double) -> [HeartRateZone]? {
        guard !samples.isEmpty, maxHeartRate > 0 else { return nil }
        var counts = Array(repeating: 0, count: zoneBounds.count)
        for bpm in samples { counts[zoneIndex(bpm: bpm, maxHeartRate: maxHeartRate)] += 1 }
        let exact = counts.map { Double($0) / Double(samples.count) * 100 }
        var percents = exact.map { Int($0) }
        let missing = 100 - percents.reduce(0, +)
        let order = exact.indices.sorted { exact[$0] - Double(percents[$0]) > exact[$1] - Double(percents[$1]) }
        for index in order.prefix(missing) { percents[index] += 1 }
        return zoneBounds.indices.map { index in
            HeartRateZone(name: zoneBounds[index].name, range: zoneBounds[index].range,
                          percent: percents[index], color: zoneBounds[index].color)
        }
    }
}

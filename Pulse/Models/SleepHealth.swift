import Foundation

/// One raw sleep interval from Apple Health (`nil` stage = «В кровати»).
struct SleepInterval: Equatable {
    let stage: SleepStage?
    let start: Date
    let end: Date

    var minutes: Double { end.timeIntervalSince(start) / 60 }
}

/// Builds nights and the sleep score from Apple Health intervals (unit-tested, no HealthKit here).
enum SleepMath {
    /// A gap longer than this starts a new night (naps and the next night are separate).
    static let nightGap: TimeInterval = 3 * 3600

    /// Groups intervals into nights, oldest first.
    static func nights(from intervals: [SleepInterval]) -> [[SleepInterval]] {
        let sorted = intervals.filter { $0.end > $0.start }.sorted { $0.start < $1.start }
        var groups: [[SleepInterval]] = []
        var lastEnd: Date?
        for interval in sorted {
            if let lastEnd, interval.start.timeIntervalSince(lastEnd) <= nightGap, !groups.isEmpty {
                groups[groups.count - 1].append(interval)
            } else {
                groups.append([interval])
            }
            lastEnd = max(lastEnd ?? interval.end, interval.end)
        }
        // A real night has some sleep in it; drop «in bed only» groups and short naps (< 2 h asleep).
        return groups.filter { asleepMinutes($0) >= 120 }
    }

    static func asleepMinutes(_ night: [SleepInterval]) -> Double {
        night.filter { $0.stage != nil && $0.stage != .awake }.reduce(0) { $0 + $1.minutes }
    }

    /// Stage segments merged into a continuous hypnogram (overlaps resolved by the later sample).
    static func segments(_ night: [SleepInterval]) -> [SleepStageSegment] {
        let staged = night.filter { $0.stage != nil }.sorted { $0.start < $1.start }
        var result: [SleepStageSegment] = []
        for interval in staged {
            guard let stage = interval.stage else { continue }
            let start = max(interval.start, result.last?.end ?? interval.start)
            guard interval.end > start else { continue }
            if let last = result.last, last.stage == stage, start.timeIntervalSince(last.end) < 60 {
                result[result.count - 1] = SleepStageSegment(stage: stage, start: last.start, end: interval.end)
            } else {
                result.append(SleepStageSegment(stage: stage, start: start, end: interval.end))
            }
        }
        return result
    }

    /// 0–100: duration vs. the planned window (50), efficiency (20), deep + REM share (20), awakenings (10).
    static func score(asleepMinutes: Double, targetMinutes: Int, efficiency: Int, restorativeShare: Double?, awakenings: Int) -> Int {
        let duration = min(asleepMinutes / Double(max(targetMinutes, 60)), 1) * 50
        let efficiencyPart = min(max((Double(efficiency) - 70) / 25, 0), 1) * 20
        // Without stages (no watch) the restorative part is neutral.
        let restorative = (restorativeShare.map { min($0 / 0.4, 1) } ?? 0.5) * 20
        let wake = Double(max(10 - awakenings * 2, 0))
        return Int((duration + efficiencyPart + restorative + wake).rounded())
    }

    /// Averages heart-rate samples into a fixed number of buckets for the night chart.
    static func heartRateCurve(_ samples: [(date: Date, bpm: Double)], from start: Date, to end: Date, buckets: Int = 20) -> [Int] {
        let span = end.timeIntervalSince(start)
        guard span > 0, !samples.isEmpty else { return [] }
        var sums = Array(repeating: 0.0, count: buckets)
        var counts = Array(repeating: 0, count: buckets)
        for sample in samples where sample.date >= start && sample.date <= end {
            let index = min(Int(sample.date.timeIntervalSince(start) / span * Double(buckets)), buckets - 1)
            sums[index] += sample.bpm
            counts[index] += 1
        }
        return zip(sums, counts).compactMap { $1 > 0 ? Int(($0 / Double($1)).rounded()) : nil }
    }

    struct NightVitals {
        var heartRate: [(date: Date, bpm: Double)] = []
        var restingHeartRate: Int?
        var restingBaseline: Double?
        var hrv: Int?
        var respiratoryRate: Int?
        var spo2: Int?
    }

    /// Night from a manual start/stop session: duration only, no phases or body metrics.
    static func manualNight(_ session: SleepSession, targetMinutes: Int) -> SleepNight {
        SleepNight(
            start: session.start, end: session.end,
            score: score(asleepMinutes: Double(session.durationMinutes), targetMinutes: targetMinutes,
                         efficiency: 90, restorativeShare: nil, awakenings: 0),
            baselineDelta: 0, segments: [], cycles: max(session.durationMinutes / 90, 1), efficiency: 90,
            latencyMinutes: 0, awakenings: 0, heartRate: [], restingHeartRate: nil, minHeartRate: nil,
            heartRateBaselineDelta: nil, respiratoryRate: nil, hrv: nil, spo2: nil, hasStages: false
        )
    }

    /// Stage minutes per wake-up day for the weekly chart (light includes unspecified sleep).
    static func week(_ nights: [SleepNight], now: Date = .now) -> [SleepWeekDay] {
        (0..<7).map { index in
            let day = now.startOfDay.adding(days: index - 6)
            let night = nights.last { $0.end.isSameDay(as: day) }
            return SleepWeekDay(date: day, deep: night?.minutes(in: .deep) ?? 0, rem: night?.minutes(in: .rem) ?? 0,
                                light: night?.minutes(in: .light) ?? 0)
        }
    }

    /// A full `SleepNight` for the screens. `previousScores` give the personal baseline for «к базовому».
    static func night(_ intervals: [SleepInterval], targetMinutes: Int, vitals: NightVitals, previousScores: [Int]) -> SleepNight? {
        guard let start = intervals.map(\.start).min(), let end = intervals.map(\.end).max() else { return nil }
        let segments = segments(intervals)
        let asleep = asleepMinutes(intervals)
        let span = end.timeIntervalSince(start) / 60
        let efficiency = span > 0 ? Int((asleep / span * 100).rounded()) : 0
        let firstSleep = segments.first { $0.stage != .awake }?.start ?? start
        let latency = Int(firstSleep.timeIntervalSince(start) / 60)
        let awakenings = segments.enumerated().filter { index, segment in
            segment.stage == .awake && index > 0 && index < segments.count - 1
        }.count
        let hasStages = segments.contains { $0.stage == .deep || $0.stage == .rem }
        let restorative = hasStages
            ? Double(segments.filter { $0.stage == .deep || $0.stage == .rem }.reduce(0) { $0 + $1.minutes }) / max(asleep, 1)
            : nil
        let score = Self.score(asleepMinutes: asleep, targetMinutes: targetMinutes, efficiency: efficiency,
                               restorativeShare: restorative, awakenings: awakenings)
        let baseline = StatisticsMath.mean(previousScores.map(Double.init))
        let curve = heartRateCurve(vitals.heartRate, from: start, to: end)
        let minimum = vitals.heartRate.filter { $0.date >= start && $0.date <= end }.map(\.bpm).min()
        let resting = vitals.restingHeartRate ?? curve.min()
        return SleepNight(
            start: start,
            end: end,
            score: score,
            baselineDelta: baseline.map { score - Int($0.rounded()) } ?? 0,
            segments: segments,
            cycles: hasStages ? max(segments.filter { $0.stage == .rem }.count, 1) : max(Int(asleep / 90), 1),
            efficiency: min(efficiency, 100),
            latencyMinutes: max(latency, 0),
            awakenings: awakenings,
            heartRate: curve,
            restingHeartRate: resting,
            minHeartRate: minimum.map { Int($0.rounded()) },
            heartRateBaselineDelta: both(resting, vitals.restingBaseline).map { Int((Double($0) - $1).rounded()) },
            respiratoryRate: vitals.respiratoryRate,
            hrv: vitals.hrv,
            spo2: vitals.spo2,
            hasStages: hasStages
        )
    }
}

private func both<A, B>(_ a: A?, _ b: B?) -> (A, B)? {
    guard let a, let b else { return nil }
    return (a, b)
}

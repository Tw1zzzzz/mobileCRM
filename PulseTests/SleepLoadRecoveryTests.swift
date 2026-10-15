import Foundation
import Testing
@testable import Pulse

struct SleepMathTests {
    private let bed = Date.now.startOfDay.adding(days: -1).setting(hour: 23, minute: 0)

    private func interval(_ stage: SleepStage?, _ from: Int, _ to: Int) -> SleepInterval {
        SleepInterval(stage: stage, start: bed.adding(minutes: from), end: bed.adding(minutes: to))
    }

    private var night: [SleepInterval] {
        [
            interval(nil, 0, 480),          // в кровати
            interval(.light, 15, 60),
            interval(.deep, 60, 120),
            interval(.light, 120, 200),
            interval(.awake, 200, 210),
            interval(.rem, 210, 270),
            interval(.light, 270, 400),
            interval(.rem, 400, 460),
            interval(.awake, 460, 480)
        ]
    }

    @Test func groupsNightsAndDropsNaps() {
        let nap = SleepInterval(stage: .light, start: bed.adding(minutes: 900), end: bed.adding(minutes: 940))
        let groups = SleepMath.nights(from: night + [nap])
        #expect(groups.count == 1)
        #expect(SleepMath.asleepMinutes(groups[0]) == 435)
    }

    @Test func buildsNightMetrics() throws {
        let built = try #require(SleepMath.night(night, targetMinutes: 480, vitals: .init(), previousScores: [70, 74]))
        #expect(built.hasStages)
        #expect(built.latencyMinutes == 15)
        #expect(built.awakenings == 1)          // the final wake-up is not counted
        #expect(built.cycles == 2)              // two REM periods
        #expect(built.efficiency == 91)         // 435 asleep of 480 in bed
        #expect(built.minutes(in: .deep) == 60)
        #expect(built.baselineDelta == built.score - 72)
        #expect((0...100).contains(built.score))
    }

    @Test func scoreRewardsLongerRestorativeSleep() {
        let short = SleepMath.score(asleepMinutes: 300, targetMinutes: 480, efficiency: 85, restorativeShare: 0.3, awakenings: 3)
        let good = SleepMath.score(asleepMinutes: 470, targetMinutes: 480, efficiency: 93, restorativeShare: 0.42, awakenings: 1)
        #expect(good > short)
        #expect(SleepMath.score(asleepMinutes: 600, targetMinutes: 480, efficiency: 100, restorativeShare: 0.5, awakenings: 0) == 100)
    }

    @Test func heartRateCurveAveragesBuckets() {
        let samples = (0..<40).map { (date: bed.adding(minutes: $0 * 12), bpm: Double(50 + $0 % 4)) }
        let curve = SleepMath.heartRateCurve(samples, from: bed, to: bed.adding(minutes: 480), buckets: 10)
        #expect(curve.count == 10)
        #expect(curve.allSatisfy { (50...53).contains($0) })
    }

    @Test func healthNightsReplaceOverlappingSessions() {
        let store = SleepStore(storage: nil, seed: [
            SleepSession(id: UUID(), start: bed.adding(minutes: -20), end: bed.adding(minutes: 470)),   // demo / manual night
            SleepSession(id: UUID(), start: bed.adding(days: -2), end: bed.adding(days: -2).adding(minutes: 420))
        ])
        let snapshot = HealthSnapshot(
            today: HealthDaySummary(steps: 0, distanceKm: 0, activeKcal: 0, activeMinutes: 0),
            weeklyActiveMinutes: [], workouts: [], heartRateZones: nil, averageHeartRate: nil, restingHeartRate: nil,
            hrv: nil, bodyMetrics: [], fetchedAt: .now, sleepIntervals: night
        )
        store.applyHealth(snapshot)
        #expect(store.isHealthConnected)
        #expect(store.sessions.count == 2)
        #expect(store.sessions.contains { $0.start == bed && $0.end == bed.adding(minutes: 480) })
        #expect(store.displayNight?.hasStages == true)
        store.applyHealth(nil)
        #expect(!store.isHealthConnected)
    }

    @Test func manualNightHasNoStages() {
        let session = SleepSession(id: UUID(), start: bed, end: bed.adding(minutes: 450))
        let built = SleepMath.manualNight(session, targetMinutes: 480)
        #expect(!built.hasStages)
        #expect(built.segments.isEmpty)
        #expect(built.hrv == nil)
    }
}

struct TrainingLoadTests {
    @Test func estimatedEffortFromPulseOrType() {
        #expect(TrainingLoadMath.estimatedRPE(kind: .yoga, averageHeartRate: 170, maxHeartRate: 190) == 8)
        #expect(TrainingLoadMath.estimatedRPE(kind: .hiit, averageHeartRate: 0, maxHeartRate: 190) == 8)
        #expect(TrainingLoadMath.estimatedRPE(kind: .stretch, averageHeartRate: 0, maxHeartRate: 190) == 2)
    }

    @Test func acwrComparesLastWeekWithUsualWeek() {
        let now = Date.now
        // 3 weeks of 300/day, then a week of 600/day → acute 4 200, chronic weekly (3·2 100 + 4 200) / 4 = 2 625.
        let entries = (0..<28).map { offset in
            (date: now.startOfDay.adding(days: -offset).setting(hour: 18, minute: 0), load: offset < 7 ? 600.0 : 300.0)
        }
        let daily = TrainingLoadMath.dailyLoads(entries, days: 28, now: now)
        #expect(daily.count == 28)
        let ratio = TrainingLoadMath.ratio(daily: daily)
        #expect(ratio.acute == 4200)
        #expect(ratio.chronicWeekly == 2625)
        #expect(abs((ratio.value ?? 0) - 1.6) < 0.0001)
        #expect(ratio.status == .high)
        #expect(TrainingLoadMath.ratio(daily: []).status == .unknown)
        #expect(LoadStatus(ratio: 1.0) == .optimal)
        #expect(LoadStatus(ratio: 0.5) == .low)
    }

    @Test func ratingsOverrideEstimates() {
        let workout = Workout(id: UUID(), kind: .strength, detail: "", date: .now, minutes: 50, kcal: 300, averageHeartRate: 0)
        let store = FitnessStore(storage: nil, seed: [workout], healthService: nil)
        #expect(store.effort(for: workout) == (6, true))
        store.rate(workout, effort: 9)
        let updated = store.workouts.first { $0.id == workout.id }!
        #expect(store.effort(for: updated) == (9, false))
        #expect(store.dailyLoad().last?.value ?? 0 >= 450)
    }
}

struct RecoveryTests {
    @Test func goodNumbersGiveHighRecovery() throws {
        let result = try #require(RecoveryMath.compute(.init(hrv: 66, hrvBaseline: 55, restingHeartRate: 54, restingBaseline: 58,
                                                             sleepScore: 88, energy: 8)))
        #expect(result.score >= 80)
        #expect(result.label == "Готов к нагрузке")
        #expect(result.components.count == 4)
    }

    @Test func badNightAndHighPulseGiveLowRecovery() throws {
        let result = try #require(RecoveryMath.compute(.init(hrv: 40, hrvBaseline: 60, restingHeartRate: 66, restingBaseline: 58,
                                                             sleepScore: 45, energy: 3)))
        #expect(result.score < 34)
        #expect(result.label == "Нужен отдых")
    }

    @Test func missingPartsAreSkipped() throws {
        let result = try #require(RecoveryMath.compute(.init(sleepScore: 70)))
        #expect(result.score == 70)
        #expect(RecoveryMath.compute(.init()) == nil)
    }
}

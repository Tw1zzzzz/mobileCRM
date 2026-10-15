import Foundation
import Testing
@testable import Pulse

struct FitnessMathTests {
    @Test func briskMinutesCountOnlyFastWalking() {
        #expect(FitnessMath.briskMinutes(stepsPerMinute: [0, 40, 99, 100, 130, 180]) == 3)
        #expect(FitnessMath.briskMinutes(stepsPerMinute: []) == 0)
    }

    @Test func kcalEstimateScalesWithWeight() {
        #expect(FitnessMath.estimatedActiveKcal(steps: 10_000, weightKg: nil) == 400)
        #expect(FitnessMath.estimatedActiveKcal(steps: 10_000, weightKg: 87.5) == 500)
    }

    @Test func maxHeartRateAndAge() {
        #expect(FitnessMath.maxHeartRate(age: 20) == 194)
        let birth = DateComponents(year: 2005, month: 1, day: 1)
        let now = Calendar.pulse.date(from: DateComponents(year: 2026, month: 10, day: 14))!
        #expect(FitnessMath.age(birthDate: birth, now: now) == 21)
        #expect(FitnessMath.age(birthDate: nil) == FitnessMath.defaultAge)
    }

    @Test func zonesSplitSamplesAndSumTo100() throws {
        // max HR 200: 100 → покой, 130 → жиросжигание, 150 → кардио, 170 → пик, 190 → максимум.
        let zones = try #require(FitnessMath.zones(samples: [100, 100, 130, 150, 170, 190], maxHeartRate: 200))
        #expect(zones.map(\.percent).reduce(0, +) == 100)
        #expect(zones.map(\.name) == ["Покой", "Жиросжигание", "Кардио", "Пик", "Максимум"])
        #expect(zones[0].percent == 33)
        #expect(FitnessMath.zones(samples: [], maxHeartRate: 200) == nil)
    }
}

private struct FakeHealth: HealthDataProviding {
    var isAvailable = true
    var snapshot: HealthSnapshot

    func requestAuthorization() async throws {}
    func snapshot(now: Date) async throws -> HealthSnapshot { snapshot }
}

@MainActor
struct FitnessStoreHealthTests {
    private func makeStorage() -> JSONFileStorage<FitnessStore.State> {
        JSONFileStorage(filename: FitnessStore.stateFile,
                        directory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
    }

    private func snapshot(steps: Int, minutes: Int, kcal: Int) -> HealthSnapshot {
        let workout = Workout(id: UUID(), kind: .cardio, detail: "Apple Health", date: .now, minutes: 30, kcal: 250, averageHeartRate: 140)
        return HealthSnapshot(
            today: HealthDaySummary(steps: steps, distanceKm: 5.1, activeKcal: kcal, activeMinutes: minutes),
            weeklyActiveMinutes: (0..<7).map { DailyValue(date: Date.now.startOfDay.adding(days: $0 - 6), value: 30) },
            workouts: [workout], heartRateZones: nil, averageHeartRate: nil, restingHeartRate: 58, hrv: 70,
            bodyMetrics: [], fetchedAt: .now
        )
    }

    @Test func demoUntilConnectedThenRealData() async {
        let store = FitnessStore(storage: makeStorage(), healthService: FakeHealth(snapshot: snapshot(steps: 9000, minutes: 35, kcal: 380)))
        #expect(!store.isHealthConnected)
        #expect(store.steps == DemoData.baselineSteps)
        #expect(store.heartRateZones != nil)

        await store.connectHealth()
        #expect(store.isHealthConnected)
        #expect(store.steps == 9000)
        #expect(store.activeMinutesToday == 35)
        #expect(store.kcalToday == 380)
        #expect(store.weeklyActiveMinutes.last?.value == 35)
        // Real data hides demo seeds and shows the Health workout instead.
        #expect(store.todayWorkouts.allSatisfy { !DemoData.seededWorkoutIDs.contains($0.id) })
        #expect(store.todayWorkouts.contains { $0.detail == "Apple Health" })
        // No watch → no zones rather than fake ones.
        #expect(store.heartRateZones == nil)
    }

    @Test func manualWorkoutsAddOnTopOfHealth() async {
        let store = FitnessStore(storage: makeStorage(), seed: [], healthService: FakeHealth(snapshot: snapshot(steps: 100, minutes: 10, kcal: 50)))
        await store.connectHealth()
        store.add(Workout(id: UUID(), kind: .strength, detail: "", date: .now, minutes: 40, kcal: 220, averageHeartRate: 120))
        #expect(store.activeMinutesToday == 50)
        #expect(store.kcalToday == 270)
    }

    @Test func disconnectReturnsToDemoAndIsRemembered() async {
        let storage = makeStorage()
        let fake = FakeHealth(snapshot: snapshot(steps: 0, minutes: 0, kcal: 0))
        let store = FitnessStore(storage: storage, healthService: fake)
        await store.connectHealth()
        #expect(store.healthLooksEmpty == false) // a Health workout exists today
        #expect(FitnessStore(storage: storage, healthService: fake).isHealthConnected)
        store.disconnectHealth()
        #expect(store.steps == DemoData.baselineSteps)
        #expect(!FitnessStore(storage: storage, healthService: fake).isHealthConnected)
    }

    @Test func unavailableDeviceShowsError() async {
        let store = FitnessStore(storage: makeStorage(), healthService: FakeHealth(isAvailable: false, snapshot: snapshot(steps: 1, minutes: 1, kcal: 1)))
        await store.connectHealth()
        #expect(!store.isHealthConnected)
        #expect(store.healthError != nil)
    }
}

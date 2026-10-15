import Foundation
import Testing
@testable import Pulse

struct SleepStoreTests {
    private let base = Date(timeIntervalSince1970: 1_800_000_000)

    @Test func startAndStopRecordsDuration() throws {
        let store = SleepStore(storage: nil, seed: [])
        store.startSleep(at: base)
        #expect(store.isSleeping)
        let session = try #require(store.stopSleep(at: base.adding(minutes: 462)))
        #expect(session.durationMinutes == 462)
        #expect(session.isValid)
        #expect(!store.isSleeping)
        #expect(store.sessions.count == 1)
    }

    @Test func stopWithoutStartReturnsNil() {
        let store = SleepStore(storage: nil, seed: [])
        #expect(store.stopSleep(at: base) == nil)
        #expect(store.sessions.isEmpty)
    }

    @Test func secondStartDoesNotResetActiveSession() {
        let store = SleepStore(storage: nil, seed: [])
        store.startSleep(at: base)
        store.startSleep(at: base.adding(minutes: 30))
        #expect(store.activeStart == base)
    }

    @Test func shortSessionIsIncomplete() throws {
        let store = SleepStore(storage: nil, seed: [])
        store.startSleep(at: base)
        let session = try #require(store.stopSleep(at: base.adding(minutes: 20)))
        #expect(!session.isValid)
        #expect(session.statusTitle == "Неполная")
    }

    @Test func averagesIgnoreIncompleteSessions() {
        let now = base.adding(days: 3)
        let seed = [
            SleepSession(id: UUID(), start: base, end: base.adding(minutes: 400)),
            SleepSession(id: UUID(), start: base.adding(days: 1), end: base.adding(days: 1).adding(minutes: 500)),
            SleepSession(id: UUID(), start: base.adding(days: 2), end: base.adding(days: 2).adding(minutes: 10))
        ]
        let store = SleepStore(storage: nil, seed: seed)
        #expect(store.averageMinutes(lastDays: 7, now: now) == 450)
        #expect(store.totalMinutes(lastDays: 7, now: now) == 910)
    }

    @Test func sleepWindowDurationWrapsMidnight() {
        let window = SleepWindow(bedtimeHour: 23, bedtimeMinute: 0, wakeHour: 7, wakeMinute: 0)
        #expect(window.durationMinutes == 480)
    }
}

struct NutritionStoreTests {
    @Test func totalsAndWaterForToday() {
        let store = NutritionStore(analyzer: MockFoodAnalyzer(latency: .zero), storage: nil, seed: [])
        store.addMeal(name: "Овсянка", kcal: 300, protein: 10, fat: 5, carbs: 50, source: .manual)
        store.addMeal(name: "Курица", kcal: 500, protein: 40, fat: 10, carbs: 40, source: .photo)
        store.addWater(milliliters: 250)
        store.addWater(milliliters: 250)

        let totals = store.totals(on: .now)
        #expect(totals == NutritionTotals(kcal: 800, protein: 50, fat: 15, carbs: 90))
        #expect(store.water(on: .now) == 500)
        #expect(store.mealCount(lastDays: 1) == 2)
    }

    @Test func negativeValuesAreClamped() {
        let store = NutritionStore(analyzer: MockFoodAnalyzer(latency: .zero), storage: nil, seed: [])
        store.addMeal(name: "Ошибка", kcal: -100, protein: -1, source: .manual)
        #expect(store.meals.first?.kcal == 0)
        #expect(store.meals.first?.protein == 0)
    }

    @Test func deleteRemovesMeal() throws {
        let store = NutritionStore(analyzer: MockFoodAnalyzer(latency: .zero), storage: nil, seed: [])
        store.addMeal(name: "Кофе", kcal: 60, source: .quick)
        let id = try #require(store.meals.first?.id)
        store.deleteMeal(id: id)
        #expect(store.meals.isEmpty)
    }

    @Test func mockAnalyzerRejectsTinyImages() async {
        let analyzer = MockFoodAnalyzer(latency: .zero)
        await #expect(throws: FoodAnalysisError.self) {
            try await analyzer.analyze(imageData: Data(count: 10))
        }
        let result = try? await analyzer.analyze(imageData: Data(count: 4096))
        #expect(result != nil)
    }
}

struct WellbeingStoreTests {
    @Test func savingSameSlotReplacesEntry() {
        let store = WellbeingStore(storage: nil, moodSeed: [], balanceSeed: [])
        let day = Date.now
        store.saveMood(day: day, slot: .morning, mood: 4, energy: 5)
        store.saveMood(day: day, slot: .morning, mood: 8, energy: 7)
        store.saveMood(day: day, slot: .evening, mood: 6, energy: 3)

        #expect(store.moodEntries.count == 2)
        #expect(store.moodEntry(on: day, slot: .morning)?.mood == 8)
        #expect(store.filledSlots(on: day) == [.morning, .evening])
        let average = store.averageMood(on: day)
        #expect(average?.mood == 7)
        #expect(average?.energy == 5)
    }

    @Test func balanceIsStoredPerDay() {
        let store = WellbeingStore(storage: nil, moodSeed: [], balanceSeed: [])
        let day = Date.now
        let values = Dictionary(uniqueKeysWithValues: BalanceCategory.allCases.map { ($0, 6.0) })
        store.saveBalance(day: day, values: values)
        store.saveBalance(day: day, values: values.merging([.financial: 9]) { _, new in new })

        #expect(store.balanceEntries.count == 1)
        #expect(store.balanceEntry(on: day)?.value(for: .financial) == 9)
        #expect(store.balanceEntry(on: day.adding(days: -1)) == nil)
    }
}

struct CalendarStoreTests {
    private func event(_ title: String, day: Date, tag: EventTag) -> CalendarEvent {
        CalendarEvent(id: UUID(), title: title, details: "", start: day.setting(hour: 12, minute: 0),
                      end: day.setting(hour: 13, minute: 0), links: [], tag: tag, ownerId: nil)
    }

    @Test func filtersByDateAndTag() {
        let today = Date.now
        let store = CalendarStore(storage: nil, seed: [
            event("Тренировка", day: today, tag: .team),
            event("Пробежка", day: today, tag: .personal),
            event("Матч", day: today.adding(days: 1), tag: .team)
        ])
        #expect(store.events(on: today, tag: nil).count == 2)
        #expect(store.events(on: today, tag: .team).map(\.title) == ["Тренировка"])
        #expect(store.hasEvents(on: today.adding(days: 1), tag: .team))
        #expect(!store.hasEvents(on: today.adding(days: 1), tag: .personal))
    }

    @Test func upsertUpdatesExistingEvent() {
        let original = event("Тренировка", day: .now, tag: .team)
        let store = CalendarStore(storage: nil, seed: [original])
        store.upsert(original.updated(title: "Разбор игры", tag: .player("Nova")))
        #expect(store.events.count == 1)
        #expect(store.event(id: original.id)?.title == "Разбор игры")
        #expect(store.event(id: original.id)?.tag == .player("Nova"))
        store.delete(id: original.id)
        #expect(store.events.isEmpty)
    }
}

struct CognitiveStoreTests {
    @Test func recordsAndAveragesDailyScores() {
        let store = CognitiveStore(storage: nil, seed: [], gameSeed: [])
        store.record(kind: .reaction, outcome: CognitiveOutcome(score: 80, metric: "260 мс"))
        store.record(kind: .memory, outcome: CognitiveOutcome(score: 60, metric: "6 цифр"))
        #expect(store.averageScore(on: .now) == 70)
        #expect(store.latest(for: .reaction)?.score == 80)
        #expect(!store.completedToday)
        for kind in CognitiveTestKind.allCases {
            store.record(kind: kind, outcome: CognitiveOutcome(score: 50, metric: ""))
        }
        #expect(store.completedToday)
    }
}

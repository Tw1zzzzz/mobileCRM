import Foundation
import Testing
@testable import Pulse

struct GameTestScheduleTests {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func event(_ title: String, startIn minutes: Int, length: Int = 120) -> CalendarEvent {
        let start = now.adding(minutes: minutes)
        return CalendarEvent(id: UUID(), title: title, details: "", start: start, end: start.adding(minutes: length),
                             links: [], tag: .team, ownerId: nil)
    }

    @Test func trainingGetsBeforeAndAfterTriggers() {
        let training = event("Тренировка", startIn: 60)
        let triggers = GameTestSchedule.triggers(for: [training], now: now)
        #expect(triggers.map(\.phase) == [.beforeTraining, .afterTraining])
        #expect(triggers[0].date == training.start.adding(minutes: -15))
        #expect(triggers[1].date == training.end.adding(minutes: 5))
        #expect(triggers[0].identifier != triggers[1].identifier)
    }

    @Test func nonTrainingEventsAreIgnored() {
        #expect(GameTestSchedule.triggers(for: [event("Разбор игры", startIn: 60)], now: now).isEmpty)
        #expect(GameTestSchedule.isTraining(event("Командная тренировка", startIn: 60)))
    }

    @Test func pastAndFarFutureTriggersAreDropped() {
        let ongoing = event("Тренировка", startIn: -30, length: 60)
        let triggers = GameTestSchedule.triggers(for: [ongoing], now: now)
        #expect(triggers.map(\.phase) == [.afterTraining])
        #expect(GameTestSchedule.triggers(for: [event("Тренировка", startIn: 60 * 24 * 10)], now: now).isEmpty)
    }
}

@MainActor
struct DemoModeTests {
    @Test func profileUpdateValidatesAndPersistsFields() async {
        let store = SessionStore(authService: MockAuthService(latency: .zero), storage: nil)
        await store.switchToDemo(role: .teamPlayer)
        #expect(store.updateProfile(nickname: " ", faceitURL: "") == AuthError.emptyFields.message)
        #expect(store.updateProfile(nickname: "Nova", faceitURL: "https://evil.com") == AuthError.invalidFaceit.message)
        #expect(store.updateProfile(nickname: "Nova2", faceitURL: "https://www.faceit.com/ru/players/nova") == nil)
        #expect(store.user?.nickname == "Nova2")
        #expect(store.user?.role == .teamPlayer)
        #expect(store.user?.faceitURL == "https://www.faceit.com/ru/players/nova")
    }

    @Test func switchingDemoRoles() async {
        let store = SessionStore(authService: MockAuthService(latency: .zero), storage: nil)
        await store.switchToDemo(role: .staff)
        #expect(store.isStaff)
        await store.switchToDemo(role: .soloPlayer)
        #expect(store.user?.role == .soloPlayer)
        #expect(!store.hasSubscription)
    }

    @Test func resetRestoresDemoData() {
        let nutrition = NutritionStore(analyzer: MockFoodAnalyzer(latency: .zero), storage: nil, seed: [])
        nutrition.updateGoals(NutritionGoals(kcal: 1800, protein: 100, fat: 60, carbs: 200, waterMilliliters: 2000))
        #expect(nutrition.goals.kcal == 1800)
        nutrition.resetToDemo()
        #expect(nutrition.goals == NutritionGoals())
        #expect(!nutrition.meals.isEmpty)

        let sleep = SleepStore(storage: nil, seed: [])
        sleep.startSleep()
        sleep.resetToDemo()
        #expect(!sleep.isSleeping)
        #expect(sleep.sessions.count == 30)
    }

    @Test func gameResultFromNotificationIsStored() {
        let store = CognitiveStore(storage: nil, seed: [], gameSeed: [])
        store.addGameResult(phase: .afterTraining, focus: 6, fatigue: 8, readiness: 5)
        #expect(store.gameResults.first?.phase == .afterTraining)
        #expect(store.gameResults.first?.fatigue == 8)
    }

    @Test func screenTimeSettingsPersist() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let storage = JSONFileStorage<ScreenTimeStore.State>(filename: "screen-time.json", directory: folder)
        let first = ScreenTimeStore(storage: storage)
        #expect(first.isAuthorized)
        #expect(first.dailyLimitMinutes == 300)
        first.isAuthorized = false
        first.dailyLimitMinutes = 240
        let second = ScreenTimeStore(storage: storage)
        #expect(!second.isAuthorized)
        #expect(second.dailyLimitMinutes == 240)
    }
}

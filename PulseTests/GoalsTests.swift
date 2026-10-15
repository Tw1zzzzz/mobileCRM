import Foundation
import Testing
@testable import Pulse

struct GoalsTests {
    private func summary(kills: Int, deaths: Int, adr: Double, won: [Bool]) -> GameSummary {
        let matches = won.enumerated().map { index, win in
            GameMatch(id: UUID(), date: Date.now.adding(days: -index), map: .mirage, won: win,
                      roundsWon: win ? 13 : 9, roundsLost: win ? 9 : 13, kills: kills, deaths: deaths, assists: 3, adr: adr, eloDelta: win ? 25 : -25)
        }
        return GameSummary(matches: matches)
    }

    @Test func eloProgressCountsFromStartingPoint() throws {
        let goals = GameGoals(elo: 3000, eloStart: 2800)
        let row = try #require(GoalMath.progress(goals: goals, elo: 2900, recent: summary(kills: 20, deaths: 20, adr: 80, won: [true])).first)
        #expect(row.metric == .elo)
        #expect(abs(row.progress - 0.5) < 0.0001)
        #expect(!row.isReached)
        #expect(row.note.contains("100"))
    }

    @Test func reachedGoalsAreFull() {
        let goals = GameGoals(elo: 2500, kd: 1.0, adr: 70, winRate: 50)
        let rows = GoalMath.progress(goals: goals, elo: 2600, recent: summary(kills: 24, deaths: 20, adr: 85, won: [true, true, false]))
        #expect(rows.count == 4)
        #expect(rows.allSatisfy { $0.isReached && $0.progress == 1 })
    }

    @Test func emptyGoalsShowNothing() {
        #expect(GoalMath.progress(goals: GameGoals(), elo: 2000, recent: summary(kills: 10, deaths: 10, adr: 70, won: [true])).isEmpty)
        #expect(GameGoals().isEmpty)
    }

    @Test func eloStartIsTakenWhenTargetChanges() {
        let store = GameGoalsStore(storage: nil)
        store.update(GameGoals(elo: 3200), currentElo: 2900)
        #expect(store.goals.eloStart == 2900)
        // Same target again keeps the original starting point.
        store.update(GameGoals(elo: 3200, kd: 1.2), currentElo: 2950)
        #expect(store.goals.eloStart == 2900)
        store.update(GameGoals(kd: 1.2), currentElo: 2950)
        #expect(store.goals.eloStart == nil)
    }

    @Test func fitnessGoalsPersist() {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let storage = JSONFileStorage<FitnessStore.State>(filename: FitnessStore.stateFile, directory: folder)
        let store = FitnessStore(storage: storage, healthService: nil)
        store.updateGoals(FitnessGoals(activeMinutes: 45, steps: 8000, activeKcal: 500))
        let reloaded = FitnessStore(storage: storage, healthService: nil)
        #expect(reloaded.activeMinutesGoal == 45)
        #expect(reloaded.stepsGoal == 8000)
    }
}

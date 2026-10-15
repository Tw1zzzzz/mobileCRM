import Foundation
import Observation

/// Personal CS2 targets (ELO, K/D, ADR, win-rate), kept on the device until the backend exists.
@Observable
final class GameGoalsStore {
    private(set) var goals: GameGoals
    private let storage: JSONFileStorage<GameGoals>?

    init(storage: JSONFileStorage<GameGoals>? = JSONFileStorage(filename: "game-goals.json")) {
        self.storage = storage
        self.goals = storage?.load() ?? GameGoals.demo
    }

    func reload(isDemo: Bool) {
        goals = storage?.load() ?? (isDemo ? GameGoals.demo : GameGoals())
    }

    /// Saves new targets. The ELO starting point is re-taken only when the ELO target changes,
    /// so progress keeps counting from the moment the goal was set.
    func update(_ newGoals: GameGoals, currentElo: Int) {
        var updated = newGoals
        if newGoals.elo == nil {
            updated.eloStart = nil
        } else if newGoals.elo != goals.elo || goals.eloStart == nil {
            updated.eloStart = currentElo
        } else {
            updated.eloStart = goals.eloStart
        }
        goals = updated
        storage?.save(updated)
    }

    func progress(elo: Int, recent: GameSummary) -> [GoalProgress] {
        GoalMath.progress(goals: goals, elo: elo, recent: recent)
    }

    func resetToDemo() {
        goals = GameGoals.demo
        storage?.save(goals)
    }
}

import Foundation
import Observation

@Observable
final class CognitiveStore {
    struct State: Codable {
        var results: [CognitiveResult]
        var gameResults: [GameTestResult]
    }

    private(set) var results: [CognitiveResult]
    private(set) var gameResults: [GameTestResult]

    private let storage: JSONFileStorage<State>?

    init(
        storage: JSONFileStorage<State>? = JSONFileStorage(filename: "cognitive.json"),
        seed: [CognitiveResult] = DemoData.cognitiveHistory(),
        gameSeed: [GameTestResult] = DemoData.gameTestHistory()
    ) {
        self.storage = storage
        let saved = storage?.load()
        self.results = (saved?.results ?? seed).sorted { $0.date > $1.date }
        self.gameResults = (saved?.gameResults ?? gameSeed).sorted { $0.date > $1.date }
    }

    func reload(isDemo: Bool) {
        let saved = storage?.load()
        results = (saved?.results ?? (isDemo ? DemoData.cognitiveHistory() : [])).sorted { $0.date > $1.date }
        gameResults = (saved?.gameResults ?? (isDemo ? DemoData.gameTestHistory() : [])).sorted { $0.date > $1.date }
    }

    func record(kind: CognitiveTestKind, outcome: CognitiveOutcome, date: Date = .now) {
        let result = CognitiveResult(id: UUID(), kind: kind, date: date, score: outcome.score, metric: outcome.metric)
        results = ([result] + results).sorted { $0.date > $1.date }
        persist()
    }

    func latest(for kind: CognitiveTestKind) -> CognitiveResult? {
        results.first { $0.kind == kind }
    }

    /// Average battery score for a given day (nil if no tests that day).
    func averageScore(on day: Date) -> Double? {
        StatisticsMath.mean(results.filter { $0.date.isSameDay(as: day) }.map { Double($0.score) })
    }

    func dailyScores(lastDays days: Int, now: Date = .now) -> [DailyValue] {
        (0..<days).reversed().compactMap { offset in
            let day = now.startOfDay.adding(days: -offset)
            return averageScore(on: day).map { DailyValue(date: day, value: $0) }
        }
    }

    var completedToday: Bool {
        Set(results.filter { $0.date.isSameDay(as: .now) }.map(\.kind)).count == CognitiveTestKind.allCases.count
    }

    func updateGameResult(_ updated: GameTestResult) {
        gameResults = gameResults.map { $0.id == updated.id ? updated : $0 }
        persist()
    }

    func addGameResult(phase: GameTestPhase, focus: Int, fatigue: Int, readiness: Int, date: Date = .now) {
        let result = GameTestResult(id: UUID(), date: date, phase: phase, focus: focus, fatigue: fatigue, readiness: readiness)
        gameResults = ([result] + gameResults).sorted { $0.date > $1.date }
        persist()
    }

    func resetToDemo() {
        results = DemoData.cognitiveHistory().sorted { $0.date > $1.date }
        gameResults = DemoData.gameTestHistory().sorted { $0.date > $1.date }
        persist()
    }

    private func persist() {
        storage?.save(State(results: results, gameResults: gameResults))
    }
}

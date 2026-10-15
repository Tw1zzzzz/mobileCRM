import Foundation

/// One CS2 match of the player (demo data until Faceit sync / manual entry).
struct GameMatch: Identifiable, Equatable {
    let id: UUID
    let date: Date
    let map: CSMap
    let won: Bool
    let roundsWon: Int
    let roundsLost: Int
    let kills: Int
    let deaths: Int
    let assists: Int
    let adr: Double
    let eloDelta: Int
    /// Extra per-match numbers available from Faceit (nil for demo matches).
    var details: Details? = nil

    struct Details: Equatable {
        let faceitMatchId: String?
        let mapName: String?
        let headshotsPercent: Double?
        let mvps: Int?
        let firstKills: Int?
        let entryCount: Int
        let entryWins: Int
        let clutchesWon: Int
        let multiKills: Int
        let utilityDamage: Int?
        let enemiesFlashed: Int?
        let sniperKills: Int?
        let rounds: Int
    }

    var kd: Double { deaths > 0 ? Double(kills) / Double(deaths) : Double(kills) }
    var score: String { "\(roundsWon):\(roundsLost)" }
}

/// Aggregates over a list of matches.
struct GameSummary: Equatable {
    let matches: Int
    let wins: Int
    let kd: Double
    let adr: Double

    var winRate: Double { matches > 0 ? Double(wins) / Double(matches) * 100 : 0 }

    init(matches list: [GameMatch]) {
        matches = list.count
        wins = list.filter(\.won).count
        let kills = list.reduce(0) { $0 + $1.kills }
        let deaths = list.reduce(0) { $0 + $1.deaths }
        kd = deaths > 0 ? Double(kills) / Double(deaths) : 0
        adr = StatisticsMath.mean(list.map(\.adr)) ?? 0
    }
}

/// «Форма дня»: one 0–100 number from sleep, energy, mood, cognitive form and fatigue.
/// Missing inputs are skipped and the remaining weights are renormalized.
enum FormIndex {
    struct Component: Identifiable, Equatable {
        let id: String
        let title: String
        let value: Double  // 0...100
    }

    struct Result: Equatable {
        let score: Int
        let components: [Component]

        var label: String {
            switch score {
            case 80...: "Отличная форма"
            case 65..<80: "Хорошая форма"
            case 50..<65: "Средняя форма"
            default: "Нужен отдых"
            }
        }
    }

    static let targetSleepMinutes = 480.0

    static func compute(
        sleepMinutes: Int?, energy: Double?, mood: Double?, cognitive: Double?, fatigue: Double?
    ) -> Result? {
        let parts: [(String, String, Double?, Double)] = [
            ("sleep", "Сон", sleepMinutes.map { min(Double($0) / targetSleepMinutes, 1) * 100 }, 0.30),
            ("energy", "Энергия", energy.map { $0 * 10 }, 0.20),
            ("cognitive", "Когн. форма", cognitive, 0.20),
            ("mood", "Настроение", mood.map { $0 * 10 }, 0.15),
            ("fatigue", "Свежесть", fatigue.map { (10 - $0) * 10 }, 0.15)
        ]
        let available = parts.compactMap { part in part.2.map { (part.0, part.1, min(max($0, 0), 100), part.3) } }
        let totalWeight = available.reduce(0) { $0 + $1.3 }
        guard totalWeight > 0 else { return nil }
        let score = available.reduce(0) { $0 + $1.2 * $1.3 } / totalWeight
        return Result(
            score: Int(score.rounded()),
            components: available.map { Component(id: $0.0, title: $0.1, value: $0.2) }
        )
    }
}

/// One human-readable insight linking recovery and game results (no tables on the dashboard).
enum GameInsight {
    struct Result: Equatable {
        let text: String
        let isPositive: Bool
    }

    /// Compares K/D on days after ≥7h of sleep vs shorter nights.
    static func sleepVersusKD(matches: [GameMatch], sleepMinutes: (Date) -> Int?) -> Result? {
        let rested = matches.filter { (sleepMinutes($0.date) ?? 0) >= 420 }
        let tired = matches.filter { match in sleepMinutes(match.date).map { $0 < 420 } ?? false }
        guard rested.count >= 2, tired.count >= 2 else { return nil }
        let restedKD = GameSummary(matches: rested).kd
        let tiredKD = GameSummary(matches: tired).kd
        let diff = restedKD - tiredKD
        guard abs(diff) >= 0.05 else {
            return Result(text: "Пока сон заметно не влияет на твой K/D — продолжай отмечать данные", isPositive: true)
        }
        let better = diff > 0
        return Result(
            text: "После сна 7+ часов твой K/D \(Formatters.decimal(restedKD, fractionDigits: 2)) против \(Formatters.decimal(tiredKD, fractionDigits: 2)) после короткой ночи",
            isPositive: better
        )
    }
}

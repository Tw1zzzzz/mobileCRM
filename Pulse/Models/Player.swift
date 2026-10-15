import Foundation

/// CS2 game statistics shown to staff (раздел «Экран администратора»).
struct GameStats: Equatable {
    let kills: Int
    let deaths: Int
    let assists: Int
    let adr: Double
    let kpr: Double
    let deathsPerRound: Double
    let avgKR: Double
    let avgKD: Double
    let kast: Double
    let firstKills: Int
    let firstDeaths: Int
    let udr: Double
    let openDuelDiff: Int
    let avgMultikills: Double
    let clutchesWon: Int
    let avgFlashTime: Double
    let matches: Int
    let wins: Int
    let losses: Int
    let draws: Int
    let rounds: Int
    let roundsWon: Int
    let pistolRounds: Int
    let pistolWon: Int

    var roundsLost: Int { rounds - roundsWon }
    var winRate: Double { matches > 0 ? Double(wins) / Double(matches) * 100 : 0 }
    var roundWinRate: Double { rounds > 0 ? Double(roundsWon) / Double(rounds) * 100 : 0 }
    var pistolWinRate: Double { pistolRounds > 0 ? Double(pistolWon) / Double(pistolRounds) * 100 : 0 }
    var avgRoundsWon: Double { matches > 0 ? Double(roundsWon) / Double(matches) : 0 }
    var avgRoundsLost: Double { matches > 0 ? Double(roundsLost) / Double(matches) : 0 }
}

struct PlayerProfile: Identifiable, Equatable {
    let id: UUID
    let nickname: String
    let realName: String
    let gameRole: String
    let lastActive: Date
    let stats: GameStats
    let elo: Int
    let seed: UInt64
    /// Demo: daily state-index range ×10 (a player in a rough patch gets a lower range).
    var readinessRange: ClosedRange<Int> = 50...85

    static func == (lhs: PlayerProfile, rhs: PlayerProfile) -> Bool { lhs.id == rhs.id }
}

struct CorrelationRow: Identifiable {
    let id = UUID()
    let factor: String
    let metric: String
    let value: Double
}

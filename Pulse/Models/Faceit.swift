import Foundation

// MARK: - Faceit Data API v4 DTOs (https://open.faceit.com/data/v4/docs/swagger.json)

/// `GET /players?nickname=&game=cs2`
struct FaceitPlayer: Decodable, Equatable {
    struct GameDetail: Decodable, Equatable {
        let faceitElo: Int?
        let skillLevel: Int?
        let region: String?
        let gamePlayerName: String?

        enum CodingKeys: String, CodingKey {
            case faceitElo = "faceit_elo", skillLevel = "skill_level", region, gamePlayerName = "game_player_name"
        }
    }

    let playerId: String
    let nickname: String
    let avatar: String?
    let country: String?
    let faceitUrl: String?
    let games: [String: GameDetail]?

    // Explicit keys: a snake_case decoding strategy would also rewrite free-form stat names.
    enum CodingKeys: String, CodingKey {
        case playerId = "player_id", nickname, avatar, country, faceitUrl = "faceit_url", games
    }

    var cs2: GameDetail? { games?["cs2"] }
}

/// Stat values in Faceit responses are loosely typed: numbers often arrive as strings ("1.25", "52").
enum FaceitValue: Decodable, Equatable {
    case text(String)
    case number(Double)
    case list([String])
    case other

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let number = try? container.decode(Double.self) {
            self = .number(number)
        } else if let text = try? container.decode(String.self) {
            self = .text(text)
        } else if let list = try? container.decode([String].self) {
            self = .list(list)
        } else {
            self = .other
        }
    }

    var double: Double? {
        switch self {
        case .number(let value): value
        case .text(let text): Double(text.replacingOccurrences(of: ",", with: ".").trimmingCharacters(in: .whitespaces))
        default: nil
        }
    }

    var string: String? {
        switch self {
        case .text(let text): text
        case .number(let value): value == value.rounded() ? String(Int(value)) : String(value)
        default: nil
        }
    }

    var list: [String] {
        if case .list(let items) = self { return items }
        return []
    }
}

typealias FaceitStats = [String: FaceitValue]

extension Dictionary where Key == String, Value == FaceitValue {
    func double(_ keys: String...) -> Double? {
        keys.lazy.compactMap { self[$0]?.double }.first
    }

    func int(_ keys: String...) -> Int? {
        keys.lazy.compactMap { self[$0]?.double }.first.map { Int($0.rounded()) }
    }

    func string(_ keys: String...) -> String? {
        keys.lazy.compactMap { self[$0]?.string }.first
    }
}

/// `GET /players/{id}/stats/cs2` — lifetime numbers and a segment per map.
struct FaceitPlayerStats: Decodable, Equatable {
    struct Segment: Decodable, Equatable {
        let label: String
        let mode: String?
        let type: String?
        let stats: FaceitStats
    }

    let lifetime: FaceitStats
    let segments: [Segment]?
}

/// `GET /players/{id}/games/cs2/stats` — per-match stats of the player, newest first.
struct FaceitMatchStatsList: Decodable {
    struct Item: Decodable {
        let stats: FaceitStats
    }

    let items: [Item]
}

/// `GET /rankings/games/cs2/regions/{region}/players/{id}`
struct FaceitRanking: Decodable, Equatable {
    let position: Int?
}

// MARK: - App-side model

/// Everything we keep from Faceit for the signed-in player.
struct FaceitProfile: Equatable {
    struct MapStats: Identifiable, Equatable {
        let map: String
        let matches: Int
        let winRate: Double
        let kd: Double
        let adr: Double?
        let headshots: Double?

        var id: String { map }
    }

    struct Lifetime: Equatable {
        let matches: Int
        let winRate: Double
        let kd: Double
        let headshots: Double
        let adr: Double?
        let longestWinStreak: Int
        let currentWinStreak: Int
        let recentResults: [Bool]
        let entrySuccessRate: Double?
        let utilityDamagePerRound: Double?
        let clutch1v1WinRate: Double?
    }

    let playerId: String
    let nickname: String
    let avatar: URL?
    let country: String?
    let region: String?
    let elo: Int
    let skillLevel: Int?
    let regionRank: Int?
    let lifetime: Lifetime?
    let maps: [MapStats]
    let matches: [GameMatch]
    let fetchedAt: Date
}

enum FaceitMapper {
    /// `https://www.faceit.com/ru/players/s1mple` → `s1mple` (a bare nickname is accepted too).
    static func nickname(fromProfileLink link: String) -> String? {
        let trimmed = link.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        guard let url = URL(string: trimmed), let host = url.host, host.contains("faceit.com") else {
            return trimmed.contains("/") || trimmed.contains(" ") ? nil : trimmed
        }
        let parts = url.pathComponents.filter { $0 != "/" }
        guard let index = parts.firstIndex(of: "players"), index + 1 < parts.count else { return nil }
        return parts[index + 1]
    }

    static func lifetime(_ stats: FaceitStats) -> FaceitProfile.Lifetime {
        FaceitProfile.Lifetime(
            matches: stats.int("Matches") ?? 0,
            winRate: stats.double("Win Rate %") ?? 0,
            kd: stats.double("Average K/D Ratio", "K/D Ratio") ?? 0,
            headshots: stats.double("Average Headshots %") ?? 0,
            adr: stats.double("ADR"),
            longestWinStreak: stats.int("Longest Win Streak") ?? 0,
            currentWinStreak: stats.int("Current Win Streak") ?? 0,
            recentResults: (stats["Recent Results"]?.list ?? []).map { $0 == "1" },
            entrySuccessRate: stats.double("Entry Success Rate").map { $0 <= 1 ? $0 * 100 : $0 },
            utilityDamagePerRound: stats.double("Utility Damage per Round"),
            clutch1v1WinRate: stats.double("1v1 Win Rate").map { $0 <= 1 ? $0 * 100 : $0 }
        )
    }

    static func maps(_ segments: [FaceitPlayerStats.Segment]) -> [FaceitProfile.MapStats] {
        segments
            .filter { ($0.type ?? "Map") == "Map" }
            .map { segment in
                FaceitProfile.MapStats(
                    map: segment.label,
                    matches: segment.stats.int("Matches") ?? 0,
                    winRate: segment.stats.double("Win Rate %") ?? 0,
                    kd: segment.stats.double("Average K/D Ratio", "K/D Ratio") ?? 0,
                    adr: segment.stats.double("ADR"),
                    headshots: segment.stats.double("Average Headshots %")
                )
            }
            .filter { $0.matches > 0 }
            .sorted { $0.matches > $1.matches }
    }

    /// Per-match stats → app match. ELO change is not part of the Data API (it stays 0).
    static func match(_ stats: FaceitStats) -> GameMatch? {
        guard let kills = stats.int("Kills"), let deaths = stats.int("Deaths") else { return nil }
        let finishedMs = stats.double("Match Finished At", "Updated At", "Created At")
        let date = finishedMs.map { Date(timeIntervalSince1970: $0 > 10_000_000_000 ? $0 / 1000 : $0) } ?? .now
        let score = (stats.string("Score") ?? "").split(separator: "/").compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
        let won = stats.string("Result") == "1"
        let ownRounds = score.count == 2 ? (won ? score.max() ?? 0 : score.min() ?? 0) : 0
        let otherRounds = score.count == 2 ? (won ? score.min() ?? 0 : score.max() ?? 0) : 0
        let rounds = max(stats.int("Rounds") ?? (ownRounds + otherRounds), 1)
        let entryCount = stats.int("Entry Count") ?? 0
        let entryWins = stats.int("Entry Wins") ?? 0
        return GameMatch(
            id: UUID(uuidString: uuidLike(stats.string("Match Id") ?? "")) ?? UUID(),
            date: date,
            map: CSMap(faceitMap: stats.string("Map") ?? ""),
            won: won,
            roundsWon: ownRounds,
            roundsLost: otherRounds,
            kills: kills,
            deaths: deaths,
            assists: stats.int("Assists") ?? 0,
            adr: stats.double("ADR") ?? 0,
            eloDelta: 0,
            details: GameMatch.Details(
                faceitMatchId: stats.string("Match Id"),
                mapName: stats.string("Map"),
                headshotsPercent: stats.double("Headshots %"),
                mvps: stats.int("MVPs"),
                firstKills: stats.int("First Kills"),
                entryCount: entryCount,
                entryWins: entryWins,
                clutchesWon: (stats.int("1v1Wins") ?? 0) + (stats.int("1v2Wins") ?? 0),
                multiKills: (stats.int("Double Kills") ?? 0) + (stats.int("Triple Kills") ?? 0)
                    + (stats.int("Quadro Kills") ?? 0) + (stats.int("Penta Kills") ?? 0),
                utilityDamage: stats.int("Utility Damage"),
                enemiesFlashed: stats.int("Enemies Flashed"),
                sniperKills: stats.int("Sniper Kills"),
                rounds: rounds
            )
        )
    }

    /// Faceit match ids look like "1-<uuid>"; keep a stable app id when possible.
    private static func uuidLike(_ raw: String) -> String {
        raw.hasPrefix("1-") ? String(raw.dropFirst(2)) : raw
    }
}

extension CSMap {
    /// "de_mirage" / "Mirage" → .mirage; unknown maps fall back to Mirage for the icon only (the name is kept in details).
    init(faceitMap raw: String) {
        let key = raw.lowercased().replacingOccurrences(of: "de_", with: "")
        self = CSMap.allCases.first { key.contains($0.rawValue) } ?? (key.contains("dust") ? .dust2 : .mirage)
    }
}

import Foundation
import Testing
@testable import Pulse

struct FaceitParsingTests {
    @Test func nicknameFromLinks() {
        #expect(FaceitMapper.nickname(fromProfileLink: "https://www.faceit.com/ru/players/s1mple") == "s1mple")
        #expect(FaceitMapper.nickname(fromProfileLink: "https://faceit.com/en/players/Nova_99/stats/cs2") == "Nova_99")
        #expect(FaceitMapper.nickname(fromProfileLink: "  donk ") == "donk")
        #expect(FaceitMapper.nickname(fromProfileLink: "https://example.com/players/x") == nil)
        #expect(FaceitMapper.nickname(fromProfileLink: "") == nil)
    }

    @Test func decodesPlayer() throws {
        let json = """
        {"player_id":"abc-1","nickname":"Nova","avatar":"https://a/b.png","country":"ru","faceit_url":"https://www.faceit.com/{lang}/players/Nova",
         "games":{"cs2":{"faceit_elo":2840,"skill_level":10,"region":"EU","game_player_name":"Nova"}}}
        """
        let player = try JSONDecoder().decode(FaceitPlayer.self, from: Data(json.utf8))
        #expect(player.playerId == "abc-1")
        #expect(player.cs2?.faceitElo == 2840)
        #expect(player.cs2?.skillLevel == 10)
        #expect(player.cs2?.region == "EU")
    }

    @Test func lifetimeAndMapsFromStringValues() throws {
        let json = """
        {"player_id":"abc-1","game_id":"cs2",
         "lifetime":{"Matches":"412","Win Rate %":"54","Average K/D Ratio":"1.18","Average Headshots %":"47",
                     "Longest Win Streak":"9","Current Win Streak":"2","Recent Results":["1","0","1","1","0"],
                     "ADR":"84.3","Entry Success Rate":"0.52","Utility Damage per Round":"7.4","1v1 Win Rate":"0.61"},
         "segments":[{"label":"Mirage","mode":"5v5","type":"Map","stats":{"Matches":"120","Win Rate %":"58","Average K/D Ratio":"1.25"}},
                     {"label":"Anubis","mode":"5v5","type":"Map","stats":{"Matches":"0","Win Rate %":"0","Average K/D Ratio":"0"}},
                     {"label":"Inferno","mode":"5v5","type":"Map","stats":{"Matches":"80","Win Rate %":"45","Average K/D Ratio":"1.02"}}]}
        """
        let stats = try JSONDecoder().decode(FaceitPlayerStats.self, from: Data(json.utf8))
        let lifetime = FaceitMapper.lifetime(stats.lifetime)
        #expect(lifetime.matches == 412)
        #expect(abs(lifetime.kd - 1.18) < 0.0001)
        #expect(lifetime.recentResults == [true, false, true, true, false])
        #expect(lifetime.entrySuccessRate == 52)
        #expect(lifetime.clutch1v1WinRate == 61)
        let maps = FaceitMapper.maps(stats.segments ?? [])
        #expect(maps.map(\.map) == ["Mirage", "Inferno"]) // played maps only, most played first
    }

    @Test func matchStatsBecomeGameMatches() throws {
        let json = """
        {"items":[{"stats":{"Match Id":"1-6f1b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b","Map":"de_inferno","Result":"1","Score":"13 / 9",
           "Kills":"24","Deaths":"15","Assists":"5","ADR":"96.4","Headshots %":"50","MVPs":"4","First Kills":"3",
           "Entry Count":"5","Entry Wins":"3","1v1Wins":"1","1v2Wins":"1","Double Kills":"3","Triple Kills":"1",
           "Utility Damage":"180","Enemies Flashed":"7","Sniper Kills":"2","Rounds":"22","Match Finished At":1760000000000}},
          {"stats":{"Map":"de_nuke","Result":"0"}}]}
        """
        let list = try JSONDecoder().decode(FaceitMatchStatsList.self, from: Data(json.utf8))
        let matches = list.items.compactMap { FaceitMapper.match($0.stats) }
        #expect(matches.count == 1) // the item without kills/deaths is skipped
        let match = try #require(matches.first)
        #expect(match.map == .inferno)
        #expect(match.won)
        #expect(match.roundsWon == 13 && match.roundsLost == 9)
        #expect(match.kills == 24 && match.deaths == 15)
        #expect(match.date == Date(timeIntervalSince1970: 1_760_000_000))
        #expect(match.details?.clutchesWon == 2)
        #expect(match.details?.multiKills == 4)
        #expect(match.details?.rounds == 22)
        #expect(match.id.uuidString.lowercased() == "6f1b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b")
    }

    @Test func lossTakesTheLowerScore() throws {
        let stats: FaceitStats = ["Result": .text("0"), "Score": .text("13 / 7"), "Kills": .text("10"), "Deaths": .text("18")]
        let match = try #require(FaceitMapper.match(stats))
        #expect(!match.won)
        #expect(match.roundsWon == 7 && match.roundsLost == 13)
    }
}

private final class MemorySecrets: SecretStore {
    var values: [String: String] = [:]
    func read(_ key: String) -> String? { values[key] }
    func write(_ value: String?, for key: String) { values[key] = value }
}

private struct FakeFaceit: FaceitProviding {
    var result: Result<FaceitProfile, FaceitError>
    func profile(nickname: String) async throws -> FaceitProfile { try result.get() }
}

@MainActor
struct GameStoreTests {
    private func profile(elo: Int) -> FaceitProfile {
        let match = GameMatch(id: UUID(), date: .now, map: .mirage, won: true, roundsWon: 13, roundsLost: 8,
                              kills: 22, deaths: 14, assists: 4, adr: 90, eloDelta: 0)
        return FaceitProfile(playerId: "p1", nickname: "Nova", avatar: nil, country: "ru", region: "EU", elo: elo,
                             skillLevel: 10, regionRank: 1500, lifetime: nil, maps: [], matches: [match], fetchedAt: .now)
    }

    @Test func demoUntilConnected() {
        let store = GameStore(storage: nil, secrets: MemorySecrets()) { _ in FakeFaceit(result: .success(self.profile(elo: 3000))) }
        #expect(!store.isConnected)
        #expect(!store.usesRealData)
        #expect(store.profile(for: nil).elo == GameData.profile(for: nil).elo)
    }

    @Test func connectLoadsRealDataAndKeepsKeyInSecrets() async {
        let secrets = MemorySecrets()
        let store = GameStore(storage: nil, secrets: secrets) { _ in FakeFaceit(result: .success(self.profile(elo: 3010))) }
        await store.connect(nickname: "https://www.faceit.com/ru/players/Nova", apiKey: " key-123 ")
        #expect(secrets.values[GameStore.apiKeyAccount] == "key-123")
        #expect(store.nickname == "Nova")
        #expect(store.isConnected)
        #expect(store.profile(for: nil).elo == 3010)
        #expect(store.matches(for: nil).count == 1)
        #expect(store.eloHistory(for: nil, days: 7).map(\.value) == [3010])
    }

    @Test func connectWithoutKeyAsksForIt() async {
        let store = GameStore(storage: nil, secrets: MemorySecrets()) { _ in FakeFaceit(result: .success(self.profile(elo: 1))) }
        await store.connect(nickname: "Nova", apiKey: "")
        #expect(store.error != nil)
        #expect(!store.usesRealData)
    }

    @Test func errorsAreShownAndDemoStays() async {
        let store = GameStore(storage: nil, secrets: MemorySecrets()) { _ in FakeFaceit(result: .failure(.playerNotFound)) }
        await store.connect(nickname: "nobody", apiKey: "key")
        #expect(store.error == FaceitError.playerNotFound.errorDescription)
        #expect(!store.usesRealData)
    }

    @Test func oneEloSnapshotPerDay() {
        let morning = Date.now.startOfDay.adding(minutes: 9 * 60)
        var list = GameStore.recording(2900, at: morning.adding(days: -1), into: [])
        list = GameStore.recording(2950, at: morning, into: list)
        list = GameStore.recording(2970, at: morning.adding(minutes: 120), into: list)
        #expect(list.map(\.elo) == [2900, 2970])
    }
}

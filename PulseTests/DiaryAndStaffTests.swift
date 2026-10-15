import Foundation
import Testing
@testable import Pulse

struct DiaryTests {
    private let solo = User(id: UUID(), nickname: "Raven", email: "r@x.gg", role: .soloPlayer, tariff: .free, team: nil, streakDays: 0)
    private let player = User(id: UUID(), nickname: "Nova", email: "n@x.gg", role: .teamPlayer, tariff: .pro, team: "T", streakDays: 0)
    private let staff = User(id: UUID(), nickname: "Coach", email: "c@x.gg", role: .staff, tariff: .pro, team: "T", streakDays: 0)

    private func entry(_ title: String, author: String, visibility: LineupVisibility, type: LineupType = .smoke) -> Lineup {
        Lineup(id: UUID(), title: title, map: .mirage, side: .t, type: type, details: "детали \(title)", videoURL: nil,
               author: author, createdAt: .now, visibility: visibility)
    }

    private var items: [Lineup] {
        [
            entry("Командный смок", author: "Coach", visibility: .team),
            entry("Личная заметка Nova", author: "Nova", visibility: .personal, type: .note),
            entry("Общая флешка Nova", author: "Nova", visibility: .team),
            entry("Дневник Raven", author: "Raven", visibility: .personal)
        ]
    }

    @Test func privacyRulesPerRole() {
        #expect(LineupStore.visible(items, for: staff).map(\.title) == ["Командный смок", "Общая флешка Nova"])
        #expect(LineupStore.visible(items, for: player).map(\.title) == ["Командный смок", "Личная заметка Nova", "Общая флешка Nova"])
        #expect(LineupStore.visible(items, for: solo).map(\.title) == ["Дневник Raven"])
        #expect(LineupStore.visible(items, for: nil).isEmpty)
    }

    @Test func scopeTypeAndSearchFilters() {
        let store = LineupStore(storage: nil, seed: items)
        #expect(store.diary(for: player, scope: .mine).count == 2)
        #expect(store.diary(for: player, scope: .team).count == 2)
        #expect(store.diary(for: player, type: .note).map(\.title) == ["Личная заметка Nova"])
        #expect(store.diary(for: player, query: "ФЛЕШ").count == 1)
        #expect(store.diary(for: player, query: "детали командный").isEmpty == false)
    }

    @Test func favoritesToggle() throws {
        let store = LineupStore(storage: nil, seed: items)
        let id = try #require(store.lineups.first?.id)
        store.toggleFavorite(id: id)
        #expect(store.lineup(id: id)?.isFavorite == true)
        store.toggleFavorite(id: id)
        #expect(store.lineup(id: id)?.isFavorite == false)
    }

    @Test func legacyEntriesDecodeWithDefaults() throws {
        let json = """
        [{"id":"\(UUID().uuidString)","title":"Старый смок","map":"mirage","side":"t","type":"smoke",
          "details":"","author":"Coach","createdAt":"2026-10-01T10:00:00Z"}]
        """
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let decoded = try decoder.decode([Lineup].self, from: Data(json.utf8))
        #expect(decoded.first?.visibility == .team)
        #expect(decoded.first?.isFavorite == false)
    }

    @Test func demoSeedsGiveEveryDemoRoleADiary() {
        let store = LineupStore(storage: nil)
        #expect(!store.diary(for: solo).isEmpty)
        #expect(store.diary(for: player).contains { $0.visibility == .personal })
        #expect(store.diary(for: staff).allSatisfy { $0.visibility == .team })
    }
}

struct StaffDataConsistencyTests {
    @Test func mealsAreAttributedToTheirAuthor() {
        let photoMeal = Meal(id: UUID(), date: .now, name: "Боул", kcal: 500, protein: 30, fat: 10, carbs: 60,
                             photo: Data([1, 2, 3]), source: .photo, author: "Blitz")
        let legacy = Meal(id: UUID(), date: .now, name: "Овсянка", kcal: 300, protein: 10, fat: 5, carbs: 50,
                          photo: nil, source: .manual)
        let entries = TeamFood.entries(deviceMeals: [photoMeal, legacy])
        #expect(entries.contains { $0.player == "Blitz" && $0.meal.photo != nil })
        #expect(entries.contains { $0.player == DemoData.players[0].nickname && $0.meal.name == "Овсянка" })
        // Players with real meals don't get demo meals mixed in.
        #expect(entries.filter { $0.player == "Blitz" }.count == 1)
        // Others still have demo diaries.
        #expect(entries.contains { $0.player == "Echo" })
    }

    @Test func soloPlayersFoodStaysPrivate() {
        let soloMeal = Meal(id: UUID(), date: .now, name: "Пицца", kcal: 800, protein: 30, fat: 35, carbs: 90,
                            photo: Data([1]), source: .photo, author: "Raven")
        #expect(!TeamFood.entries(deviceMeals: [soloMeal]).contains { $0.player == "Raven" })
        #expect(!DemoData.players.contains { $0.nickname == "Raven" })
    }

    @Test func firstPlayerSleepMatchesDeviceDemoHistory() {
        let nova = DemoData.players[0]
        #expect(DemoData.sleepSessions(for: nova).map(\.durationMinutes) == DemoData.sleepHistory().map(\.durationMinutes))
        let other = DemoData.players[1]
        #expect(DemoData.sleepSessions(for: other).count == 30)
    }

    @Test func gameStatsMatchTheMatchList() {
        let player = DemoData.players[2]
        let matches = GameData.matches(for: player, days: 30)
        let stats = GameData.stats(for: player, days: 30)
        let summary = GameSummary(matches: matches)
        #expect(stats.matches == matches.count)
        #expect(stats.wins == summary.wins)
        #expect(abs(stats.avgKD - summary.kd) < 0.0001)
        #expect(abs(stats.adr - summary.adr) < 0.0001)
        #expect(stats.roundsWon + stats.roundsLost == stats.rounds)
    }

    @Test func snapshotIsTheSingleSourceForPlayerCards() {
        let player = DemoData.players[3]
        let snapshot = TeamAnalytics.snapshot(for: player, days: 7)
        let readiness = StatisticsMath.mean(TeamAnalytics.readiness(for: player, days: 7).map(\.value)) ?? 0
        #expect(abs(snapshot.readiness - readiness) < 0.0001)
        #expect((0...10).contains(snapshot.readiness))
        #expect(snapshot.focusHighDays <= 7)
    }

    @Test func correlationMatrixShapeAndRange() {
        let matrix = TeamAnalytics.matrix(players: DemoData.players)
        #expect(matrix.count == WellbeingFactor.allCases.count)
        #expect(matrix.allSatisfy { $0.count == GameMetric.allCases.count })
        #expect(matrix.flatMap { $0 }.compactMap { $0 }.allSatisfy { (-1...1).contains($0) })
        let rows = TeamAnalytics.correlationRows(players: DemoData.players)
        #expect(rows == rows.sorted { abs($0.value) > abs($1.value) })
    }

    @Test func trendLineFitsPerfectData() throws {
        let points = (0..<5).map { ScatterPoint(player: "x", sleepHours: Double($0), kd: 2 * Double($0) + 1, won: true) }
        let line = try #require(TeamAnalytics.trendLine(points))
        #expect(abs(line.slope - 2) < 1e-9)
        #expect(abs(line.intercept - 1) < 1e-9)
    }

    @Test func teamTrendStaysWithinPlayerRange() {
        let trend = TeamAnalytics.teamReadinessTrend(days: 14)
        #expect(trend.count == 14)
        #expect(trend.allSatisfy { $0.low <= $0.average && $0.average <= $0.high })
    }
}

extension CorrelationRow: Equatable {
    public static func == (lhs: CorrelationRow, rhs: CorrelationRow) -> Bool {
        lhs.factor == rhs.factor && lhs.metric == rhs.metric && lhs.value == rhs.value
    }
}

struct SeededGeneratorTests {
    @Test func firstValuesDifferAcrossNearbySeeds() {
        let firsts = (100...110).map { seed -> Int in
            var rng = SeededGenerator(seed: UInt64(seed))
            return rng.int(in: 50...85)
        }
        #expect(Set(firsts).count > 5)
        var a = SeededGenerator(seed: 42)
        var b = SeededGenerator(seed: 42)
        #expect(a.next() == b.next())
    }
}

import Foundation
import Testing
@testable import Pulse

struct FormIndexTests {
    @Test func allInputsProduceWeightedScore() throws {
        let result = try #require(FormIndex.compute(sleepMinutes: 480, energy: 10, mood: 10, cognitive: 100, fatigue: 0))
        #expect(result.score == 100)
        #expect(result.label == "Отличная форма")
        #expect(result.components.map(\.id) == ["sleep", "energy", "cognitive", "mood", "fatigue"])
    }

    @Test func missingInputsAreRenormalized() throws {
        let onlySleep = try #require(FormIndex.compute(sleepMinutes: 240, energy: nil, mood: nil, cognitive: nil, fatigue: nil))
        #expect(onlySleep.score == 50)
        #expect(onlySleep.components.count == 1)
        #expect(FormIndex.compute(sleepMinutes: nil, energy: nil, mood: nil, cognitive: nil, fatigue: nil) == nil)
    }

    @Test func valuesAreClampedAndLabelled() throws {
        let tired = try #require(FormIndex.compute(sleepMinutes: 900, energy: 2, mood: 3, cognitive: 40, fatigue: 9))
        #expect(tired.components.allSatisfy { (0...100).contains($0.value) })
        #expect(tired.label == "Нужен отдых")
    }
}

struct GameDataTests {
    private func match(daysAgo: Int, kills: Int, deaths: Int, won: Bool, now: Date) -> GameMatch {
        GameMatch(id: UUID(), date: now.adding(days: -daysAgo).setting(hour: 18, minute: 0), map: .mirage, won: won,
                  roundsWon: won ? 13 : 8, roundsLost: won ? 8 : 13, kills: kills, deaths: deaths, assists: 3,
                  adr: 80, eloDelta: won ? 25 : -25)
    }

    @Test func summaryAggregates() {
        let now = Date.now
        let summary = GameSummary(matches: [
            match(daysAgo: 0, kills: 20, deaths: 10, won: true, now: now),
            match(daysAgo: 1, kills: 10, deaths: 20, won: false, now: now)
        ])
        #expect(summary.matches == 2)
        #expect(summary.winRate == 50)
        #expect(summary.kd == 1)
        #expect(summary.adr == 80)
        #expect(GameSummary(matches: []).kd == 0)
    }

    @Test func insightComparesRestedAndTiredDays() throws {
        let now = Date.now
        let matches = (0..<6).map { day in
            day.isMultiple(of: 2)
                ? match(daysAgo: day, kills: 24, deaths: 12, won: true, now: now)
                : match(daysAgo: day, kills: 12, deaths: 16, won: false, now: now)
        }
        let insight = try #require(GameInsight.sleepVersusKD(matches: matches) { date in
            let offset = Calendar.pulse.dateComponents([.day], from: date.startOfDay, to: now.startOfDay).day ?? 0
            return offset.isMultiple(of: 2) ? 480 : 360
        })
        #expect(insight.isPositive)
        #expect(insight.text.contains("2,00"))
        #expect(GameInsight.sleepVersusKD(matches: Array(matches.prefix(2))) { _ in 480 } == nil)
    }

    @Test func eloHistoryEndsAtCurrentElo() {
        let player = DemoData.players[0]
        let history = GameData.eloHistory(for: player, days: 14)
        #expect(history.count == 14)
        #expect(Int(history.last?.value ?? 0) == player.elo)
        let matches = GameData.matches(for: player)
        #expect(matches == matches.sorted { $0.date > $1.date })
        #expect(matches.allSatisfy { $0.date <= .now && ($0.roundsWon == 13 || $0.roundsLost == 13) })
    }

    @Test func profileFallsBackToFirstPlayer() {
        let unknown = User(id: UUID(), nickname: "Nobody", email: "n@x.gg", role: .soloPlayer, tariff: .free, team: nil, streakDays: 0)
        #expect(GameData.profile(for: unknown) == DemoData.players[0])
        let nova = User(id: UUID(), nickname: "Blitz", email: "b@x.gg", role: .teamPlayer, tariff: .pro, team: nil, streakDays: 0)
        #expect(GameData.profile(for: nova).nickname == "Blitz")
    }
}

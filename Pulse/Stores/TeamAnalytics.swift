import Foundation

/// Aggregated per-player metrics for staff screens. Every staff screen reads from here,
/// so «Аналитика игроков», карточка игрока и «Сегодня» стаффа показывают одинаковые цифры.
struct PlayerSnapshot: Identifiable {
    let player: PlayerProfile
    let readiness: Double
    let averageScore: Int
    let sleepMinutes: Int
    let fatigueHighDays: Int
    let focusHighDays: Int

    var id: UUID { player.id }

    /// Needs attention: low readiness, short sleep or frequent high fatigue.
    var needsAttention: Bool {
        readiness < 6.0 || sleepMinutes < 380 || fatigueHighDays >= TeamAnalytics.fatigueAlertDays
    }

    var attentionReason: String {
        if sleepMinutes < 380 { return "Мало сна: \(Formatters.duration(minutes: sleepMinutes))" }
        if fatigueHighDays >= TeamAnalytics.fatigueAlertDays { return "Усталость high \(fatigueHighDays) дн." }
        return "Индекс состояния \(Formatters.decimal(readiness))"
    }
}

/// Non-game factors and game metrics used by the correlation analysis.
enum WellbeingFactor: String, CaseIterable, Identifiable {
    case sleep, mood, cognitive, screen, nutrition

    var id: String { rawValue }

    var title: String {
        switch self {
        case .sleep: "Сон"
        case .mood: "Настроение"
        case .cognitive: "Когн. форма"
        case .screen: "Экран. время"
        case .nutrition: "Питание"
        }
    }
}

enum GameMetric: String, CaseIterable, Identifiable {
    case kd, adr, winRate

    var id: String { rawValue }

    var title: String {
        switch self {
        case .kd: "K/D"
        case .adr: "ADR"
        case .winRate: "Win-rate"
        }
    }

    func value(of match: GameMatch) -> Double {
        switch self {
        case .kd: match.kd
        case .adr: match.adr
        case .winRate: match.won ? 100 : 0
        }
    }
}

struct ScatterPoint: Identifiable {
    let id = UUID()
    let player: String
    let sleepHours: Double
    let kd: Double
    let won: Bool
}

enum TeamAnalytics {
    static let fatigueAlertDays = 5

    // MARK: Per-player daily series (single source for every staff screen)

    static func sleepMinutes(for player: PlayerProfile, on day: Date) -> Int? {
        let sessions = DemoData.sleepSessions(for: player).filter { $0.end.isSameDay(as: day) }
        return sessions.isEmpty ? nil : sessions.reduce(0) { $0 + $1.durationMinutes }
    }

    /// Cognitive battery score 0…100 per day.
    static func scores(for player: PlayerProfile, days: Int) -> [DailyValue] {
        DemoData.playerSeries(player, days: days, range: 55...92, salt: 1)
    }

    /// State index 0…10 per day (readiness from game tests and mood).
    static func readiness(for player: PlayerProfile, days: Int) -> [DailyValue] {
        DemoData.playerSeries(player, days: days, range: player.readinessRange, salt: 4).map { DailyValue(date: $0.date, value: $0.value / 10) }
    }

    static func factorValue(_ factor: WellbeingFactor, for player: PlayerProfile, on day: Date) -> Double? {
        switch factor {
        case .sleep: return sleepMinutes(for: player, on: day).map { Double($0) / 60 }
        case .cognitive: return scores(for: player, days: 30).first { $0.date.isSameDay(as: day) }?.value
        case .mood: return series(player, salt: 21, range: 4...9).first { $0.date.isSameDay(as: day) }?.value
        case .screen: return series(player, salt: 31, range: 180...360).first { $0.date.isSameDay(as: day) }.map { $0.value / 60 }
        case .nutrition: return series(player, salt: 41, range: 1800...3000).first { $0.date.isSameDay(as: day) }?.value
        }
    }

    private static func series(_ player: PlayerProfile, salt: UInt64, range: ClosedRange<Int>) -> [DailyValue] {
        DemoData.playerSeries(player, days: 30, range: range, salt: salt)
    }

    // MARK: Snapshots

    static func snapshot(for player: PlayerProfile, days: Int = 7) -> PlayerSnapshot {
        let today = Date.now.startOfDay
        let sleep = (0..<days).compactMap { sleepMinutes(for: player, on: today.adding(days: -$0)) }
        let scoreValues = scores(for: player, days: days).map(\.value)
        let fatigue = DemoData.playerSeries(player, days: days, range: 1...10, salt: 2).filter { $0.value >= 8 }.count
        return PlayerSnapshot(
            player: player,
            readiness: StatisticsMath.mean(readiness(for: player, days: days).map(\.value)) ?? 0,
            averageScore: Int(StatisticsMath.mean(scoreValues) ?? 0),
            sleepMinutes: Int(StatisticsMath.mean(sleep.map(Double.init)) ?? 0),
            fatigueHighDays: fatigue,
            focusHighDays: scoreValues.filter { $0 >= 80 }.count
        )
    }

    static func snapshots(days: Int = 7) -> [PlayerSnapshot] {
        DemoData.players.map { snapshot(for: $0, days: days) }
    }

    struct TrendPoint: Identifiable {
        let date: Date
        let average: Double
        let low: Double
        let high: Double

        var id: Date { date }
    }

    /// Team average readiness per day with min/max across players.
    static func teamReadinessTrend(days: Int) -> [TrendPoint] {
        let perPlayer = DemoData.players.map { readiness(for: $0, days: days) }
        return (0..<days).map { index in
            let values = perPlayer.compactMap { index < $0.count ? $0[index].value : nil }
            return TrendPoint(date: perPlayer.first?[index].date ?? .now, average: StatisticsMath.mean(values) ?? 0,
                              low: values.min() ?? 0, high: values.max() ?? 0)
        }
    }

    // MARK: Correlations (Pearson over matched days)

    private static func pairs(_ factor: WellbeingFactor, _ metric: GameMetric, player: PlayerProfile) -> [(Double, Double)] {
        GameData.matches(for: player).compactMap { match in
            factorValue(factor, for: player, on: match.date).map { ($0, metric.value(of: match)) }
        }
    }

    static func correlation(_ factor: WellbeingFactor, _ metric: GameMetric, players: [PlayerProfile]) -> Double? {
        let all = players.flatMap { pairs(factor, metric, player: $0) }
        return StatisticsMath.pearson(all.map(\.0), all.map(\.1))
    }

    /// Factor × metric matrix for one player or the whole team (pooled samples).
    static func matrix(players: [PlayerProfile]) -> [[Double?]] {
        WellbeingFactor.allCases.map { factor in
            GameMetric.allCases.map { correlation(factor, $0, players: players) }
        }
    }

    static func correlationRows(players: [PlayerProfile]) -> [CorrelationRow] {
        WellbeingFactor.allCases.flatMap { factor in
            GameMetric.allCases.compactMap { metric in
                correlation(factor, metric, players: players).map {
                    CorrelationRow(factor: factor.title, metric: metric.title, value: $0)
                }
            }
        }
        .sorted { abs($0.value) > abs($1.value) }
    }

    static func correlations(for player: PlayerProfile) -> [CorrelationRow] {
        Array(correlationRows(players: [player]).prefix(6))
    }

    static func teamCorrelations() -> [CorrelationRow] {
        Array(correlationRows(players: DemoData.players).prefix(6))
    }

    static func sleepVersusKD(players: [PlayerProfile]) -> [ScatterPoint] {
        players.flatMap { player in
            GameData.matches(for: player).compactMap { match in
                sleepMinutes(for: player, on: match.date).map {
                    ScatterPoint(player: player.nickname, sleepHours: Double($0) / 60, kd: match.kd, won: match.won)
                }
            }
        }
    }

    /// Least-squares line for the scatter plot.
    static func trendLine(_ points: [ScatterPoint]) -> (slope: Double, intercept: Double)? {
        guard points.count >= 3, let meanX = StatisticsMath.mean(points.map(\.sleepHours)),
              let meanY = StatisticsMath.mean(points.map(\.kd)) else { return nil }
        let numerator = points.reduce(0) { $0 + ($1.sleepHours - meanX) * ($1.kd - meanY) }
        let denominator = points.reduce(0) { $0 + pow($1.sleepHours - meanX, 2) }
        guard denominator > 0 else { return nil }
        let slope = numerator / denominator
        return (slope, meanY - slope * meanX)
    }
}

extension TeamAnalytics {
    /// Demo training load of a team player (28 days, oldest first). Players in a rough patch get a recent spike,
    /// so the staff view shows what an overload looks like. Real data will come from the players' apps via the backend.
    static func dailyLoad(for player: PlayerProfile, now: Date = .now) -> [DailyValue] {
        var rng = SeededGenerator(seed: player.seed &+ 404)
        let spike = player.readinessRange.upperBound < 70
        return (0..<TrainingLoadMath.chronicDays).reversed().map { offset in
            let day = now.startOfDay.adding(days: -offset)
            let trains = rng.int(in: 0...9) < 6
            let base = trains ? Double(rng.int(in: 4...7) * rng.int(in: 35...60)) : 0
            return DailyValue(date: day, value: offset < 7 && spike && trains ? base * 1.8 : base)
        }
    }
}

/// Player-side game data (demo). Matches are generated per player so ELO, K/D and win rate stay consistent.
enum GameData {
    static func profile(for user: User?) -> PlayerProfile {
        DemoData.players.first { $0.nickname == user?.nickname } ?? DemoData.players[0]
    }

    /// ~1–2 matches per day over the last `days` days, newest first.
    static func matches(for player: PlayerProfile, days: Int = 30, now: Date = .now) -> [GameMatch] {
        var rng = SeededGenerator(seed: player.seed &+ 777)
        let maps = CSMap.allCases
        // Demo only: matches after a short night (<7h in the player's demo sleep) are slightly worse,
        // so analytics show a plausible pattern. Real data skips this shaping.
        let shortNights = Set(DemoData.sleepSessions(for: player, now: now).filter { $0.durationMinutes < 420 }.map { $0.end.startOfDay })
        return (0..<days).flatMap { offset -> [GameMatch] in
            let day = now.startOfDay.adding(days: -offset)
            let count = rng.int(in: 0...2)
            return (0..<count).compactMap { index -> GameMatch? in
                let date = day.setting(hour: 16 + index * 3, minute: rng.int(in: 0...50))
                guard date <= now else { return nil }
                let tired = shortNights.contains(day)
                let kills = max(rng.int(in: 9...28) - (tired ? 3 : 0), 4)
                let deaths = rng.int(in: 10...21) + (tired ? 1 : 0)
                let won = Double(kills) / Double(deaths) + rng.double(in: -0.35...0.35) >= 1.0
                let lost = rng.int(in: 3...11)
                return GameMatch(
                    id: UUID(), date: date, map: maps[rng.int(in: 0...(maps.count - 1))], won: won,
                    roundsWon: won ? 13 : lost, roundsLost: won ? lost : 13,
                    kills: kills, deaths: deaths, assists: rng.int(in: 2...8),
                    adr: rng.double(in: 60...104) - (tired ? 6 : 0), eloDelta: won ? rng.int(in: 18...30) : -rng.int(in: 18...30)
                )
            }
        }
        .sorted { $0.date > $1.date }
    }

    /// End-of-day ELO for the last `days` days, ending at the player's current ELO.
    static func eloHistory(for player: PlayerProfile, days: Int = 14, now: Date = .now) -> [DailyValue] {
        let list = matches(for: player, days: max(days, 30), now: now)
        return (0..<days).reversed().map { offset in
            let day = now.startOfDay.adding(days: -offset)
            let later = list.filter { $0.date > day.adding(days: 1) }.reduce(0) { $0 + $1.eloDelta }
            return DailyValue(date: day, value: Double(player.elo - later))
        }
    }

    /// Daily average of a match metric (only days with matches), oldest first.
    static func daily(_ metric: GameMetric, for player: PlayerProfile, days: Int) -> [DailyValue] {
        let list = matches(for: player, days: days)
        return (0..<days).reversed().compactMap { offset in
            let day = Date.now.startOfDay.adding(days: -offset)
            let values = list.filter { $0.date.isSameDay(as: day) }.map(metric.value)
            return StatisticsMath.mean(values).map { DailyValue(date: day, value: $0) }
        }
    }

    /// Full CS2 stats: base numbers (K/D/A, ADR, matches, rounds) come from the same matches as the charts;
    /// advanced metrics (KAST, клатчи, ослепление…) need demo parsing and stay demo values.
    static func stats(for player: PlayerProfile, days: Int = 30) -> GameStats {
        let list = matches(for: player, days: days)
        let base = player.stats
        let kills = list.reduce(0) { $0 + $1.kills }
        let deaths = list.reduce(0) { $0 + $1.deaths }
        let rounds = max(list.reduce(0) { $0 + $1.roundsWon + $1.roundsLost }, 1)
        let wins = list.filter(\.won).count
        let pistol = list.count * 2
        let pistolRate = base.pistolRounds > 0 ? Double(base.pistolWon) / Double(base.pistolRounds) : 0.5
        return GameStats(
            kills: kills, deaths: deaths, assists: list.reduce(0) { $0 + $1.assists },
            adr: StatisticsMath.mean(list.map(\.adr)) ?? 0,
            kpr: Double(kills) / Double(rounds), deathsPerRound: Double(deaths) / Double(rounds),
            avgKR: Double(kills) / Double(rounds), avgKD: deaths > 0 ? Double(kills) / Double(deaths) : 0,
            kast: base.kast, firstKills: base.firstKills, firstDeaths: base.firstDeaths, udr: base.udr,
            openDuelDiff: base.openDuelDiff, avgMultikills: base.avgMultikills, clutchesWon: base.clutchesWon,
            avgFlashTime: base.avgFlashTime, matches: list.count, wins: wins, losses: list.count - wins, draws: 0,
            rounds: rounds, roundsWon: list.reduce(0) { $0 + $1.roundsWon },
            pistolRounds: pistol, pistolWon: Int((Double(pistol) * pistolRate).rounded())
        )
    }
}

/// «Питание игроков» для стаффа: приёмы пищи с фото, которые игроки логируют в приложении.
enum TeamFood {
    struct Entry: Identifiable {
        let meal: Meal
        let player: String

        var id: UUID { meal.id }
    }

    /// Device meals belong to their author (legacy meals without author → first demo player);
    /// players without real entries get demo meals.
    static func entries(deviceMeals: [Meal], days: Int = 7, now: Date = .now) -> [Entry] {
        let from = now.startOfDay.adding(days: -days)
        let defaultAuthor = DemoData.players[0].nickname
        let roster = Set(DemoData.players.map(\.nickname))
        // Only team players' meals reach staff; a solo player's food diary stays private.
        let real = deviceMeals
            .map { Entry(meal: $0, player: $0.author ?? defaultAuthor) }
            .filter { roster.contains($0.player) }
        let withRealData = Set(real.map(\.player))
        let demo = DemoData.players
            .filter { !withRealData.contains($0.nickname) }
            .flatMap { player in DemoData.playerMeals(for: player, now: now).map { Entry(meal: $0, player: player.nickname) } }
        return (real + demo)
            .filter { $0.meal.date >= from && $0.meal.date <= now }
            .sorted { $0.meal.date > $1.meal.date }
    }
}

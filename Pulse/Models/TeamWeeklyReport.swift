import SwiftUI

/// One player's week for the staff summary.
struct TeamWeeklyRow: Identifiable, Equatable {
    let id: UUID
    let nickname: String
    let readiness: Double?
    let previousReadiness: Double?
    let sleepMinutes: Int?
    let previousSleepMinutes: Int?
    let matches: Int
    let winRate: Double?
    let kd: Double?
    let previousKD: Double?
    let load: TrainingLoadMath.Ratio

    var readinessDelta: Double? { readiness.flatMap { now in previousReadiness.map { now - $0 } } }
    var sleepDelta: Int? { sleepMinutes.flatMap { now in previousSleepMinutes.map { now - $0 } } }
    var kdDelta: Double? { kd.flatMap { now in previousKD.map { now - $0 } } }

    /// Same thresholds as the rest of staff analytics: readiness < 6, sleep < 6 h 20 m, overload.
    var risks: [String] {
        var result: [String] = []
        if let readiness, readiness < 6 { result.append("готовность \(Formatters.decimal(readiness))") }
        if let sleepMinutes, sleepMinutes < 380 { result.append("сон \(Formatters.duration(minutes: sleepMinutes))") }
        if load.status == .high { result.append("перегруз (ACWR \(Formatters.decimal(load.value ?? 0, fractionDigits: 2)))") }
        return result
    }
}

struct TeamWeeklyReport: Equatable {
    let week: WeekRange
    let rows: [TeamWeeklyRow]
    let readiness: Double?
    let readinessDelta: Double?
    let sleepMinutes: Int?
    let sleepDelta: Int?
    let matches: Int
    let winRate: Double?
    let previousWinRate: Double?
    let highlights: [String]
    let risks: [String]
    let tips: [String]
}

enum TeamWeeklyBuilder {
    static func build(rows: [TeamWeeklyRow], week: WeekRange, wins: Int, previousWins: Int, previousMatches: Int) -> TeamWeeklyReport {
        let readiness = StatisticsMath.mean(rows.compactMap(\.readiness))
        let previousReadiness = StatisticsMath.mean(rows.compactMap(\.previousReadiness))
        let sleep = StatisticsMath.mean(rows.compactMap(\.sleepMinutes).map(Double.init))
        let previousSleep = StatisticsMath.mean(rows.compactMap(\.previousSleepMinutes).map(Double.init))
        let matches = rows.reduce(0) { $0 + $1.matches }

        var highlights: [String] = []
        if let best = rows.filter({ ($0.readinessDelta ?? 0) > 0.2 }).max(by: { ($0.readinessDelta ?? 0) < ($1.readinessDelta ?? 0) }),
           let delta = best.readinessDelta {
            highlights.append("Сильнее всех вырос \(best.nickname): готовность +\(Formatters.decimal(delta))")
        }
        if let shooter = rows.filter({ $0.matches >= 2 }).max(by: { ($0.kd ?? 0) < ($1.kd ?? 0) }), let kd = shooter.kd {
            highlights.append("Лучший K/D недели — \(shooter.nickname): \(Formatters.decimal(kd, fractionDigits: 2))")
        }
        if let sleeper = rows.filter({ ($0.sleepDelta ?? 0) >= 20 }).max(by: { ($0.sleepDelta ?? 0) < ($1.sleepDelta ?? 0) }),
           let delta = sleeper.sleepDelta {
            highlights.append("\(sleeper.nickname) спит на \(Formatters.duration(minutes: delta)) больше")
        }

        let risks = rows.filter { !$0.risks.isEmpty }.map { "\($0.nickname): \($0.risks.joined(separator: ", "))" }

        var tips: [String] = []
        if rows.contains(where: { $0.load.status == .high }) {
            tips.append("У игроков с перегрузом снизьте объём тренировок на 20–30% в ближайшие дни.")
        }
        if let sleep, sleep < 420 {
            tips.append("Средний сон команды меньше 7 часов — перенесите поздние праки или введите отбой.")
        }
        if let readiness, let previousReadiness, readiness < previousReadiness - 0.3 {
            tips.append("Готовность команды снизилась — проведите короткий разбор самочувствия на неделе.")
        }
        if tips.isEmpty { tips.append("Команда в хорошем ритме — сохраняйте текущую нагрузку и режим.") }

        return TeamWeeklyReport(
            week: week,
            rows: rows.sorted { ($0.readiness ?? 0) > ($1.readiness ?? 0) },
            readiness: readiness,
            readinessDelta: readiness.flatMap { now in previousReadiness.map { now - $0 } },
            sleepMinutes: sleep.map { Int($0.rounded()) },
            sleepDelta: sleep.flatMap { now in previousSleep.map { Int((now - $0).rounded()) } },
            matches: matches,
            winRate: matches > 0 ? Double(wins) / Double(matches) * 100 : nil,
            previousWinRate: previousMatches > 0 ? Double(previousWins) / Double(previousMatches) * 100 : nil,
            highlights: Array(highlights.prefix(3)),
            risks: risks,
            tips: tips
        )
    }

    /// Demo team (no server yet): rows from the same demo series as the rest of staff analytics.
    static func demo(week: WeekRange, now: Date = .now) -> TeamWeeklyReport {
        let previous = week.previous
        var wins = 0, previousWins = 0, previousMatches = 0
        let rows = DemoData.players.map { player -> TeamWeeklyRow in
            let readiness = TeamAnalytics.readiness(for: player, days: 30)
            let sleep = DemoData.sleepSessions(for: player, now: now)
            let matches = GameData.matches(for: player, days: 30, now: now)
            let thisWeek = matches.filter { week.contains($0.date) }
            let lastWeek = matches.filter { previous.contains($0.date) }
            wins += thisWeek.filter(\.won).count
            previousWins += lastWeek.filter(\.won).count
            previousMatches += lastWeek.count
            let mean = { (values: [Double]) in StatisticsMath.mean(values) }
            let load = TeamAnalytics.dailyLoad(for: player, now: min(now, week.end.adding(days: -1)))
            return TeamWeeklyRow(
                id: player.id,
                nickname: player.nickname,
                readiness: mean(readiness.filter { week.contains($0.date) }.map(\.value)),
                previousReadiness: mean(readiness.filter { previous.contains($0.date) }.map(\.value)),
                sleepMinutes: mean(sleep.filter { week.contains($0.end) }.map { Double($0.durationMinutes) }).map { Int($0.rounded()) },
                previousSleepMinutes: mean(sleep.filter { previous.contains($0.end) }.map { Double($0.durationMinutes) }).map { Int($0.rounded()) },
                matches: thisWeek.count,
                winRate: thisWeek.isEmpty ? nil : GameSummary(matches: thisWeek).winRate,
                kd: thisWeek.isEmpty ? nil : GameSummary(matches: thisWeek).kd,
                previousKD: lastWeek.isEmpty ? nil : GameSummary(matches: lastWeek).kd,
                load: TrainingLoadMath.ratio(daily: load)
            )
        }
        return build(rows: rows, week: week, wins: wins, previousWins: previousWins, previousMatches: previousMatches)
    }
}

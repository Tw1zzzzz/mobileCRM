import Foundation

/// Daily activity targets (Профиль → Цели → Фитнес).
struct FitnessGoals: Codable, Equatable {
    var activeMinutes = 60
    var steps = 10_000
    var activeKcal = 600
}

/// Personal CS2 targets. Every goal is optional: the player sets only the ones they care about.
struct GameGoals: Codable, Equatable {
    var elo: Int?
    /// ELO when the ELO goal was set — progress is measured from here, not from zero.
    var eloStart: Int?
    var kd: Double?
    var adr: Double?
    var winRate: Int?

    var isEmpty: Bool { elo == nil && kd == nil && adr == nil && winRate == nil }

    /// Demo: a realistic target so the feature is visible on first launch.
    static let demo = GameGoals(elo: 3000, eloStart: 2750, kd: 1.1)

    /// K/D, ADR and win-rate are judged on recent form, not a single match.
    static let recentMatches = 20
}

/// One row of goal progress, ready for display.
struct GoalProgress: Identifiable, Equatable {
    enum Metric: String {
        case elo, kd, adr, winRate
    }

    let metric: Metric
    let title: String
    let current: String
    let target: String
    /// 0...1
    let progress: Double
    let isReached: Bool
    let note: String

    var id: String { metric.rawValue }
}

enum GoalMath {
    static func progress(goals: GameGoals, elo: Int, recent: GameSummary) -> [GoalProgress] {
        var rows: [GoalProgress] = []
        if let target = goals.elo {
            let start = goals.eloStart ?? elo
            let reached = elo >= target
            let span = Double(target - start)
            let share = reached ? 1 : (span > 0 ? Double(elo - start) / span : 0)
            rows.append(GoalProgress(
                metric: .elo, title: "ELO", current: Formatters.grouped(elo), target: Formatters.grouped(target),
                progress: clamp(share), isReached: reached,
                note: reached ? "Цель достигнута 🎉" : "Осталось \(Formatters.grouped(target - elo)) ELO"
            ))
        }
        if let target = goals.kd {
            rows.append(ratio(.kd, "K/D", current: recent.kd, target: target, digits: 2))
        }
        if let target = goals.adr {
            rows.append(ratio(.adr, "ADR", current: recent.adr, target: target, digits: 1))
        }
        if let target = goals.winRate {
            let current = recent.winRate
            let reached = current >= Double(target)
            rows.append(GoalProgress(
                metric: .winRate, title: "Победы", current: "\(Int(current.rounded()))%", target: "\(target)%",
                progress: clamp(current / Double(max(target, 1))), isReached: reached,
                note: reached ? "Держишь планку" : "Нужно ещё \(Int((Double(target) - current).rounded(.up)))%"
            ))
        }
        return rows
    }

    private static func ratio(_ metric: GoalProgress.Metric, _ title: String, current: Double, target: Double, digits: Int) -> GoalProgress {
        let reached = current >= target
        return GoalProgress(
            metric: metric, title: title,
            current: Formatters.decimal(current, fractionDigits: digits),
            target: Formatters.decimal(target, fractionDigits: digits),
            progress: clamp(target > 0 ? current / target : 0), isReached: reached,
            note: reached ? "Держишь планку" : "Не хватает \(Formatters.decimal(target - current, fractionDigits: digits))"
        )
    }

    private static func clamp(_ value: Double) -> Double {
        min(max(value, 0), 1)
    }
}

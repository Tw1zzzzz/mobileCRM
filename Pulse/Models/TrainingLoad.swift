import SwiftUI

/// Training load by the session-RPE method: load = perceived effort (1–10) × minutes.
/// ACWR = load of the last 7 days ÷ average weekly load of the last 28 days.
enum TrainingLoadMath {
    static let acuteDays = 7
    static let chronicDays = 28

    /// Effort guess when the player did not rate the workout: from heart rate, else from the workout type.
    static func estimatedRPE(kind: WorkoutKind, averageHeartRate: Int, maxHeartRate: Double) -> Int {
        if averageHeartRate > 0, maxHeartRate > 0 {
            switch Double(averageHeartRate) / maxHeartRate {
            case ..<0.6: return 3
            case ..<0.7: return 4
            case ..<0.8: return 6
            case ..<0.9: return 8
            default: return 9
            }
        }
        switch kind {
        case .hiit: return 8
        case .strength: return 6
        case .cardio: return 5
        case .yoga, .stretch: return 2
        }
    }

    /// Daily load for the last `days` days, oldest first (days without workouts are 0).
    static func dailyLoads(_ entries: [(date: Date, load: Double)], days: Int, now: Date = .now) -> [DailyValue] {
        (0..<days).reversed().map { offset in
            let day = now.startOfDay.adding(days: -offset)
            let total = entries.filter { $0.date.isSameDay(as: day) }.reduce(0) { $0 + $1.load }
            return DailyValue(date: day, value: total)
        }
    }

    struct Ratio: Equatable {
        let acute: Double
        let chronicWeekly: Double
        /// nil until there is a chronic base to compare with.
        let value: Double?

        var status: LoadStatus { value.map(LoadStatus.init(ratio:)) ?? .unknown }
    }

    static func ratio(daily: [DailyValue]) -> Ratio {
        let lastChronic = daily.suffix(chronicDays)
        let acute = lastChronic.suffix(acuteDays).reduce(0) { $0 + $1.value }
        let chronicWeekly = lastChronic.reduce(0) { $0 + $1.value } / (Double(chronicDays) / Double(acuteDays))
        return Ratio(acute: acute, chronicWeekly: chronicWeekly, value: chronicWeekly > 0 ? acute / chronicWeekly : nil)
    }
}

enum LoadStatus: Equatable {
    case unknown, low, optimal, elevated, high

    init(ratio: Double) {
        switch ratio {
        case ..<0.8: self = .low
        case ..<1.3: self = .optimal
        case ..<1.5: self = .elevated
        default: self = .high
        }
    }

    var title: String {
        switch self {
        case .unknown: "Мало данных"
        case .low: "Недогруз"
        case .optimal: "Оптимально"
        case .elevated: "Повышенная"
        case .high: "Риск перегруза"
        }
    }

    var color: Color {
        switch self {
        case .unknown: Theme.Palette.textSecondary
        case .low: Theme.Palette.cyan
        case .optimal: Theme.Palette.green
        case .elevated: Theme.Palette.yellow
        case .high: Theme.Palette.red
        }
    }

    var advice: String {
        switch self {
        case .unknown: "Записывай тренировки неделю-другую — появится сравнение с обычной нагрузкой."
        case .low: "Нагрузка ниже обычной. Можно добавить тренировку, если самочувствие хорошее."
        case .optimal: "Нагрузка в зоне 0,8–1,3 — организм успевает адаптироваться."
        case .elevated: "Нагрузка растёт быстрее обычного. Следи за сном и восстановлением."
        case .high: "Резкий рост нагрузки — риск перетренированности и травм. Снизь объём на пару дней."
        }
    }
}

/// «Восстановление» 0–100 from the personal 30-day baselines:
/// HRV 40 %, sleep 30 %, resting pulse 20 %, self-reported energy 10 % (missing parts are skipped).
enum RecoveryMath {
    struct Component: Identifiable, Equatable {
        let id: String
        let title: String
        let value: Double
        let detail: String
    }

    struct Result: Equatable {
        let score: Int
        let components: [Component]

        var label: String {
            switch score {
            case 67...: "Готов к нагрузке"
            case 34..<67: "Умеренная нагрузка"
            default: "Нужен отдых"
            }
        }

        var color: Color {
            switch score {
            case 67...: Theme.Palette.green
            case 34..<67: Theme.Palette.yellow
            default: Theme.Palette.red
            }
        }
    }

    struct Input: Equatable {
        var hrv: Double?
        var hrvBaseline: Double?
        var restingHeartRate: Double?
        var restingBaseline: Double?
        var sleepScore: Int?
        var energy: Double?
    }

    static func compute(_ input: Input) -> Result? {
        var parts: [(Component, Double)] = []
        if let hrv = input.hrv, let base = input.hrvBaseline, base > 0 {
            // ±25 % against the personal norm spans the whole 0–100 range.
            let value = clamp(50 + 50 * (hrv / base - 1) / 0.25)
            let change = Int(((hrv / base - 1) * 100).rounded())
            parts.append((Component(id: "hrv", title: "Вариабельность пульса", value: value,
                                    detail: "\(Int(hrv.rounded())) мс · \(change >= 0 ? "+" : "")\(change)% к норме"), 0.4))
        }
        if let score = input.sleepScore {
            parts.append((Component(id: "sleep", title: "Сон", value: clamp(Double(score)), detail: "оценка \(score)"), 0.3))
        }
        if let resting = input.restingHeartRate, let base = input.restingBaseline {
            // +5 bpm above the norm → 0, −5 → 100.
            let value = clamp(50 - 50 * (resting - base) / 5)
            let delta = Int((resting - base).rounded())
            parts.append((Component(id: "rhr", title: "Пульс покоя", value: value,
                                    detail: "\(Int(resting.rounded())) уд/мин · \(delta >= 0 ? "+" : "")\(delta) к норме"), 0.2))
        }
        if let energy = input.energy {
            parts.append((Component(id: "energy", title: "Самочувствие", value: clamp(energy * 10),
                                    detail: "энергия \(Formatters.decimal(energy)) из 10"), 0.1))
        }
        let weight = parts.reduce(0) { $0 + $1.1 }
        guard weight > 0 else { return nil }
        let score = parts.reduce(0) { $0 + $1.0.value * $1.1 } / weight
        return Result(score: Int(score.rounded()), components: parts.map(\.0))
    }

    private static func clamp(_ value: Double) -> Double {
        min(max(value, 0), 100)
    }
}

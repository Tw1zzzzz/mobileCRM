import Foundation

/// Pure statistics helpers used by the analytics screens (unit-tested).
enum StatisticsMath {
    static func mean(_ values: [Double]) -> Double? {
        guard !values.isEmpty else { return nil }
        return values.reduce(0, +) / Double(values.count)
    }

    /// Pearson correlation coefficient for paired samples. Returns nil when undefined.
    static func pearson(_ xs: [Double], _ ys: [Double]) -> Double? {
        guard xs.count == ys.count, xs.count >= 3,
              let meanX = mean(xs), let meanY = mean(ys) else { return nil }
        var numerator = 0.0
        var sumX = 0.0
        var sumY = 0.0
        for (x, y) in zip(xs, ys) {
            let dx = x - meanX
            let dy = y - meanY
            numerator += dx * dy
            sumX += dx * dx
            sumY += dy * dy
        }
        let denominator = (sumX * sumY).squareRoot()
        guard denominator > 0 else { return nil }
        return numerator / denominator
    }

    /// Percent change between two values ("-15%").
    static func percentChange(from old: Double, to new: Double) -> Double? {
        guard old != 0 else { return nil }
        return (new - old) / old * 100
    }

    static func clampedProgress(_ value: Double, goal: Double) -> Double {
        guard goal > 0 else { return 0 }
        return min(max(value / goal, 0), 1)
    }
}

enum CorrelationStrength {
    case strong, moderate, weak

    init(_ value: Double) {
        switch abs(value) {
        case 0.6...: self = .strong
        case 0.3..<0.6: self = .moderate
        default: self = .weak
        }
    }

    var title: String {
        switch self {
        case .strong: "сильная"
        case .moderate: "умеренная"
        case .weak: "слабая"
        }
    }
}

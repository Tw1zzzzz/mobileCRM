import SwiftUI

/// The five tests of the daily cognitive battery.
enum CognitiveTestKind: String, Codable, CaseIterable, Identifiable {
    case reaction, goNoGo, stroop, memory, aim

    var id: String { rawValue }

    var title: String {
        switch self {
        case .reaction: "Скорость реакции"
        case .goNoGo: "Go / No-Go"
        case .stroop: "Тест Струпа"
        case .memory: "Объём памяти"
        case .aim: "Точность прицела"
        }
    }

    var measures: String {
        switch self {
        case .reaction: "Простую зрительно-моторную реакцию — насколько быстро ты отвечаешь на сигнал."
        case .goNoGo: "Тормозной контроль и импульсивность: умение не реагировать на ложный сигнал."
        case .stroop: "Когнитивную гибкость и концентрацию при конфликте информации."
        case .memory: "Объём рабочей памяти — сколько элементов ты удерживаешь в голове."
        case .aim: "Скорость и точность наведения — моторный контроль, как в игре."
        }
    }

    var instructions: String {
        switch self {
        case .reaction: "Жди, пока экран станет зелёным, и сразу нажимай. Не торопись — ранний тап не засчитывается. 5 попыток."
        case .goNoGo: "Появляются круги. Нажимай на зелёный, игнорируй красный. 20 сигналов."
        case .stroop: "Слово написано цветом. Выбери ЦВЕТ ТЕКСТА, а не смысл слова. 12 заданий."
        case .memory: "Запомни последовательность цифр и введи её. С каждым раундом длина растёт."
        case .aim: "Нажимай на появляющиеся мишени как можно быстрее. 15 мишеней."
        }
    }

    var target: String {
        switch self {
        case .reaction: "Реакция"
        case .goNoGo: "Самоконтроль"
        case .stroop: "Концентрация"
        case .memory: "Рабочая память"
        case .aim: "Моторика"
        }
    }

    var icon: String {
        switch self {
        case .reaction: "bolt"
        case .goNoGo: "hand.raised"
        case .stroop: "textformat"
        case .memory: "brain.head.profile"
        case .aim: "scope"
        }
    }

    var tint: Color {
        switch self {
        case .reaction: Theme.Palette.orange
        case .goNoGo: Theme.Palette.green
        case .stroop: Theme.Palette.purple
        case .memory: Theme.Palette.cyan
        case .aim: Theme.Palette.red
        }
    }
}

/// Outcome of a single test: normalized 0...100 score plus a human-readable raw metric.
struct CognitiveOutcome: Equatable {
    let score: Int
    let metric: String
}

struct CognitiveResult: Codable, Identifiable, Equatable {
    let id: UUID
    let kind: CognitiveTestKind
    let date: Date
    let score: Int
    let metric: String
}

/// Pre/post-training test results coming from notifications ("Игровые тесты").
enum GameTestPhase: String, Codable, CaseIterable, Identifiable {
    var id: String { rawValue }

    case beforeTraining, afterTraining

    var title: String {
        switch self {
        case .beforeTraining: "До тренировки"
        case .afterTraining: "После тренировки"
        }
    }
}

struct GameTestResult: Codable, Identifiable, Equatable {
    let id: UUID
    let date: Date
    let phase: GameTestPhase
    let focus: Int
    let fatigue: Int
    let readiness: Int
}

enum CognitiveScoring {
    /// 200 ms → 100, 500 ms → 0.
    static func reactionScore(averageMs: Double) -> Int {
        clamp(Int(((500 - averageMs) / 300) * 100))
    }

    static func accuracyScore(correct: Int, total: Int) -> Int {
        guard total > 0 else { return 0 }
        return clamp(Int((Double(correct) / Double(total)) * 100))
    }

    /// Span 3 → 0, span 9 → 100.
    static func memoryScore(span: Int) -> Int {
        clamp(Int(Double(span - 3) / 6 * 100))
    }

    /// Accuracy penalized by slow responses (target 450 ms).
    static func speedAccuracyScore(correct: Int, total: Int, averageMs: Double) -> Int {
        let accuracy = Double(accuracyScore(correct: correct, total: total))
        let speed = Double(clamp(Int(((1200 - averageMs) / 750) * 100)))
        return clamp(Int(accuracy * 0.7 + speed * 0.3))
    }

    static func clamp(_ value: Int) -> Int {
        min(max(value, 0), 100)
    }
}

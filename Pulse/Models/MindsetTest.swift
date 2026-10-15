import SwiftUI

/// Competitive-mindset traits measured by the «Игровой характер» test (self-report, not a diagnosis).
enum MindsetTrait: String, Codable, CaseIterable, Identifiable {
    case composure, resilience, focus, teamwork, leadership, drive

    var id: String { rawValue }

    var title: String {
        switch self {
        case .composure: "Хладнокровие"
        case .resilience: "Контроль тильта"
        case .focus: "Концентрация"
        case .teamwork: "Командность"
        case .leadership: "Лидерство"
        case .drive: "Дисциплина"
        }
    }

    /// Short label for the radar chart.
    var shortTitle: String {
        switch self {
        case .composure: "Хладнокр."
        case .resilience: "Антитильт"
        case .focus: "Фокус"
        case .teamwork: "Команда"
        case .leadership: "Лидерство"
        case .drive: "Дисциплина"
        }
    }

    var icon: String {
        switch self {
        case .composure: "snowflake"
        case .resilience: "shield.lefthalf.filled"
        case .focus: "scope"
        case .teamwork: "person.3"
        case .leadership: "flag"
        case .drive: "dumbbell"
        }
    }

    var tint: Color {
        switch self {
        case .composure: Theme.Palette.cyan
        case .resilience: Theme.Palette.green
        case .focus: Theme.Palette.blue
        case .teamwork: Theme.Palette.yellow
        case .leadership: Theme.Palette.orange
        case .drive: Theme.Palette.purple
        }
    }

    var meaning: String {
        switch self {
        case .composure: "Насколько спокойно принимаешь решения под давлением: клатчи, овертаймы, важные матчи."
        case .resilience: "Как быстро отпускаешь ошибки, проигранные раунды и токсичность."
        case .focus: "Умение держать внимание весь матч и не отвлекаться."
        case .teamwork: "Коммуникация, готовность играть на команду и спокойная реакция на ошибки тиммейтов."
        case .leadership: "Готовность брать решения на себя и вести команду."
        case .drive: "Регулярность тренировок, разминка, разбор демок."
        }
    }

    /// For the player when the trait is a growth zone.
    var playerTip: String {
        switch self {
        case .composure: "Перед клатчем — один медленный выдох и план из двух шагов. Дыхание «квадрат» 4-4-4-4 перед важными матчами."
        case .resilience: "Заведи ритуал сброса после смерти: выдох, «следующий раунд», взгляд на миникарту. Не читай чат в матче."
        case .focus: "Телефон — в другую комнату, перерыв 5 минут между картами. Утренняя когнитивная батарея покажет, в какие дни фокус проседает."
        case .teamwork: "Колл в формате «где, сколько, что сделал» — до 3 слов. После ошибки тиммейта — только информация, без оценок."
        case .leadership: "Начни с малого: бери колл в одном раунде на половину или предлагай план на тайм-ауте."
        case .drive: "Короткий план на неделю в календаре Pulse: 3 тренировки по 45 минут и разбор одной демки."
        }
    }

    /// For the coach when the trait is a growth zone.
    var coachTip: String {
        switch self {
        case .composure: "Тренируйте клатчи 1vX на праках и разбирайте их отдельно; перед важными матчами — короткая рутина подготовки."
        case .resilience: "Не разбирайте ошибки в течение карты; давайте конкретную задачу на следующий раунд. Следите за тильтом после серий поражений."
        case .focus: "Ставьте в важные карты пораньше в дне, делайте перерывы между картами; следите за сном и утомлением в тестах."
        case .teamwork: "Договоритесь о формате коммуникации и ролях; хвалите за полезную инфу, а не только за фраги."
        case .leadership: "Не ставьте на роль IGL без подготовки; давайте зону ответственности — например, мид-раунд колы на одной точке."
        case .drive: "Дайте понятный план с контрольными точками и отмечайте прогресс — игроку важен видимый результат."
        }
    }

    /// For the coach when the trait is a strength.
    var coachStrength: String {
        switch self {
        case .composure: "Доверяйте решающие раунды и клатч-позиции."
        case .resilience: "Опора команды после проигранных раундов — может задавать спокойный тон."
        case .focus: "Подходит для позиций, где нужна дисциплина и терпение (якорь, лерк)."
        case .teamwork: "Хорошо связывает команду: инфа, размены, поддержка."
        case .leadership: "Кандидат в IGL или второго капитана."
        case .drive: "Пример для команды по режиму и подготовке."
        }
    }
}

struct MindsetQuestion: Identifiable, Equatable {
    let id: String
    let text: String
    let trait: MindsetTrait
    /// Agreeing means a lower trait score.
    let reversed: Bool
}

/// Result of one test run; answers are 1…5 keyed by question id.
struct MindsetResult: Codable, Equatable, Identifiable {
    var id = UUID()
    var date = Date.now
    let answers: [String: Int]

    var scores: [MindsetTrait: Int] { MindsetMath.scores(answers) }
}

enum MindsetTest {
    static let answerTitles = ["Совсем не про меня", "Скорее нет", "Иногда", "Скорее да", "Точно про меня"]
    /// Suggested interval between re-takes.
    static let retakeDays = 30

    static let questions: [MindsetQuestion] = [
        .init(id: "q1", text: "В клатче 1 против 2 я думаю спокойно и по плану.", trait: .composure, reversed: false),
        .init(id: "q2", text: "После глупой смерти я быстро переключаюсь на следующий раунд.", trait: .resilience, reversed: false),
        .init(id: "q3", text: "Я держу внимание весь матч, даже если он длится больше часа.", trait: .focus, reversed: false),
        .init(id: "q4", text: "Я даю короткую и точную информацию, даже когда умираю.", trait: .teamwork, reversed: false),
        .init(id: "q5", text: "Когда раунд разваливается, я беру на себя решение, куда идти.", trait: .leadership, reversed: false),
        .init(id: "q6", text: "Я тренируюсь по плану, даже когда не хочется.", trait: .drive, reversed: false),
        .init(id: "q7", text: "При счёте 12:12 у меня начинают трястись руки.", trait: .composure, reversed: true),
        .init(id: "q8", text: "Серия проигранных раундов надолго выбивает меня из колеи.", trait: .resilience, reversed: true),
        .init(id: "q9", text: "Во время игры я отвлекаюсь на телефон, чат или посторонние мысли.", trait: .focus, reversed: true),
        .init(id: "q10", text: "Мне проще играть в соло, чем подстраиваться под команду.", trait: .teamwork, reversed: true),
        .init(id: "q11", text: "Мне некомфортно давать указания другим игрокам.", trait: .leadership, reversed: true),
        .init(id: "q12", text: "Разминку перед игрой я часто пропускаю.", trait: .drive, reversed: true),
        .init(id: "q13", text: "Важный матч или LAN не мешает мне играть на своём уровне.", trait: .composure, reversed: false),
        .init(id: "q14", text: "Токсичный тиммейт или соперник почти не влияет на мою игру.", trait: .resilience, reversed: false),
        .init(id: "q15", text: "Я слежу за звуками, таймером и экономикой соперника, не теряя прицел.", trait: .focus, reversed: false),
        .init(id: "q16", text: "Я готов сыграть неудобную для себя роль, если это нужно команде.", trait: .teamwork, reversed: false),
        .init(id: "q17", text: "Я замечаю, когда команда теряет настрой, и стараюсь её поднять.", trait: .leadership, reversed: false),
        .init(id: "q18", text: "Я разбираю свои демки и записываю, что улучшить.", trait: .drive, reversed: false),
        .init(id: "q19", text: "Под давлением я спешу и делаю необдуманные пики.", trait: .composure, reversed: true),
        .init(id: "q20", text: "После поражения я долго прокручиваю ошибки и злюсь.", trait: .resilience, reversed: true),
        .init(id: "q21", text: "К концу второй карты мои реакции заметно хуже.", trait: .focus, reversed: true),
        .init(id: "q22", text: "Когда тиммейт ошибается, я раздражаюсь и высказываю это в игре.", trait: .teamwork, reversed: true),
        .init(id: "q23", text: "Я предпочитаю, чтобы решения в игре принимал кто-то другой.", trait: .leadership, reversed: true),
        .init(id: "q24", text: "Я забрасываю тренировки, если не вижу быстрого прогресса.", trait: .drive, reversed: true)
    ]
}

/// Headline of the profile: the leading trait in plain words (no nicknames or labels).
struct MindsetArchetype: Equatable {
    /// «Сильнее всего» / «Профиль».
    let caption: String
    let title: String
    let summary: String
    let icon: String
    let tint: Color

    static func forTrait(_ trait: MindsetTrait?) -> MindsetArchetype {
        guard let trait else {
            return .init(caption: "Профиль", title: "Ровный по всем чертам",
                         summary: "Ни одна черта заметно не выделяется — нет и явных провалов.",
                         icon: "circle.hexagongrid", tint: Theme.Palette.lightBlue)
        }
        let summary = switch trait {
        case .composure: "Спокойно принимает решения под давлением: клатчи, овертаймы, важные матчи."
        case .resilience: "Быстро отпускает ошибки и проигранные раунды."
        case .focus: "Держит внимание весь матч и не отвлекается."
        case .teamwork: "Хорошая коммуникация, готов играть на команду."
        case .leadership: "Готов брать решения на себя и вести команду."
        case .drive: "Тренируется регулярно и по плану."
        }
        return .init(caption: "Сильнее всего", title: trait.title, summary: summary, icon: trait.icon, tint: trait.tint)
    }
}

/// Everything the profile screens show, computed from one result.
struct MindsetProfile: Equatable {
    let scores: [MindsetTrait: Int]
    let archetype: MindsetArchetype
    let strengths: [MindsetTrait]
    let growthZones: [MindsetTrait]

    /// Values in `MindsetTrait.allCases` order (radar chart).
    var orderedScores: [Int] { MindsetTrait.allCases.map { scores[$0] ?? 0 } }

    init(scores: [MindsetTrait: Int]) {
        self.scores = scores
        let ranked = MindsetTrait.allCases.sorted { (scores[$0] ?? 0, -$0.order) > (scores[$1] ?? 0, -$1.order) }
        let values = ranked.map { scores[$0] ?? 0 }
        let spread = (values.first ?? 0) - (values.last ?? 0)
        archetype = MindsetArchetype.forTrait(spread < MindsetMath.balancedSpread ? nil : ranked.first)
        let strong = ranked.filter { (scores[$0] ?? 0) >= MindsetMath.strengthThreshold }
        let top = Array((strong.isEmpty ? Array(ranked.prefix(1)) : strong).prefix(2))
        strengths = top
        growthZones = Array(ranked.reversed().filter { (scores[$0] ?? 0) < MindsetMath.growthThreshold && !top.contains($0) }.prefix(2))
    }
}

private extension MindsetTrait {
    var order: Int { MindsetTrait.allCases.firstIndex(of: self) ?? 0 }
}

enum MindsetMath {
    static let strengthThreshold = 70
    static let growthThreshold = 55
    /// Max − min below this means no single trait stands out.
    static let balancedSpread = 12

    /// 0…100 per trait: mean of 1…5 answers (reversed items flipped), rescaled.
    static func scores(_ answers: [String: Int], questions: [MindsetQuestion] = MindsetTest.questions) -> [MindsetTrait: Int] {
        var result: [MindsetTrait: Int] = [:]
        for trait in MindsetTrait.allCases {
            let values = questions.filter { $0.trait == trait }.compactMap { question -> Double? in
                guard let raw = answers[question.id], (1...5).contains(raw) else { return nil }
                return Double(question.reversed ? 6 - raw : raw)
            }
            guard let mean = StatisticsMath.mean(values) else { continue }
            result[trait] = Int(((mean - 1) / 4 * 100).rounded())
        }
        return result
    }

    static func isComplete(_ answers: [String: Int]) -> Bool {
        MindsetTest.questions.allSatisfy { (1...5).contains(answers[$0.id] ?? 0) }
    }

    /// Demo answers whose scores land within ~6 points of the target.
    static func answers(for target: [MindsetTrait: Int]) -> [String: Int] {
        var answers: [String: Int] = [:]
        for trait in MindsetTrait.allCases {
            let items = MindsetTest.questions.filter { $0.trait == trait }
            let score = Double(target[trait] ?? 50)
            let total = min(max(Int(((score / 100 * 4 + 1) * Double(items.count)).rounded()), items.count), items.count * 5)
            for (index, question) in items.enumerated() {
                let raw = total / items.count + (index < total % items.count ? 1 : 0)
                answers[question.id] = question.reversed ? 6 - raw : raw
            }
        }
        return answers
    }
}

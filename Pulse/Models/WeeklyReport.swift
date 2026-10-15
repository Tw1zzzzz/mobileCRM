import SwiftUI

/// A calendar week, Monday to Sunday (ru locale).
struct WeekRange: Equatable, Hashable {
    let start: Date
    /// Exclusive: the next Monday.
    let end: Date

    static func containing(_ date: Date) -> WeekRange {
        let interval = Calendar.pulse.dateInterval(of: .weekOfYear, for: date)
        let start = interval?.start ?? date.startOfDay
        return WeekRange(start: start, end: interval?.end ?? start.adding(days: 7))
    }

    var previous: WeekRange { WeekRange.containing(start.adding(days: -1)) }
    var next: WeekRange { WeekRange.containing(end.adding(minutes: 1)) }
    var days: [Date] { (0..<7).map { start.adding(days: $0) } }

    func contains(_ date: Date) -> Bool { date >= start && date < end }

    func isCurrent(now: Date = .now) -> Bool { contains(now) }

    /// Days of the week that have started by `now` (7 for past weeks, 0 for future ones).
    func elapsedDays(now: Date = .now) -> Int {
        guard now >= start else { return 0 }
        guard now < end else { return 7 }
        return (Calendar.pulse.dateComponents([.day], from: start, to: now).day ?? 0) + 1
    }

    /// «6–12 октября» or «29 сентября – 5 октября».
    var title: String {
        let last = end.adding(days: -1)
        let startDay = Calendar.pulse.component(.day, from: start)
        if Calendar.pulse.isDate(start, equalTo: last, toGranularity: .month) {
            return "\(startDay)–\(Formatters.dayMonth(last))"
        }
        return "\(Formatters.dayMonth(start)) – \(Formatters.dayMonth(last))"
    }
}

/// Everything the personal report needs, as plain values (built from the stores by the view).
struct WeeklyInputs {
    var sleep: [SleepSession] = []
    var sleepGoalMinutes = 480
    var workouts: [Workout] = []
    /// Daily training load covering at least the two compared weeks.
    var dailyLoad: [DailyValue] = []
    var screenMinutes: [DailyValue] = []
    var screenLimitMinutes = 300
    var kcal: [DailyValue] = []
    var kcalGoal = 2400
    var mood: [MoodEntry] = []
    var cognitive: [CognitiveResult] = []
    var matches: [GameMatch] = []
    var elo: [DailyValue] = []
    /// Goals from the questionnaire: matching tips go first.
    var focus: [FocusGoal] = []
}

struct WeeklyMetric: Identifiable, Equatable {
    enum Trend: Equatable { case better, worse, same, none }

    let id: String
    let title: String
    let icon: String
    let tint: Color
    let value: String
    let detail: String
    let change: String?
    let trend: Trend
}

struct WeeklyGoalCheck: Identifiable, Equatable {
    let id: String
    let title: String
    let achieved: Int
    let total: Int

    var progress: Double { total > 0 ? Double(achieved) / Double(total) : 0 }
}

struct WeeklyReport: Equatable {
    let week: WeekRange
    let score: Int?
    let previousScore: Int?
    let activeDays: Int
    /// 7 for finished weeks, fewer for the current one.
    let elapsedDays: Int
    let metrics: [WeeklyMetric]
    let goals: [WeeklyGoalCheck]
    let wins: [String]
    let concerns: [String]
    let tips: [String]

    var isEmpty: Bool { metrics.isEmpty }
}

/// Builds the personal weekly report (unit-tested; no stores here).
enum WeeklyReportBuilder {
    /// Week score 0–100 from what was logged: sleep vs goal, mood, energy, cognitive form, consistency.
    static func score(sleepAverage: Double?, sleepGoal: Int, mood: Double?, energy: Double?, cognitive: Double?,
                      activeDays: Int, elapsedDays: Int = 7) -> Int? {
        var parts: [Double] = []
        if let sleepAverage { parts.append(min(sleepAverage / Double(max(sleepGoal, 60)), 1) * 100) }
        if let mood { parts.append(mood * 10) }
        if let energy { parts.append(energy * 10) }
        if let cognitive { parts.append(cognitive) }
        guard !parts.isEmpty else { return nil }
        let days = max(elapsedDays, 1)
        parts.append(Double(min(activeDays, days)) / Double(days) * 100)
        return Int((parts.reduce(0, +) / Double(parts.count)).rounded())
    }

    static func build(_ input: WeeklyInputs, week: WeekRange, now: Date = .now) -> WeeklyReport {
        let previous = week.previous
        let current = Slice(input, week, elapsedDays: week.elapsedDays(now: now))
        let before = Slice(input, previous, elapsedDays: previous.elapsedDays(now: now))

        var metrics: [WeeklyMetric] = []
        var wins: [String] = []
        var concerns: [String] = []

        func note(_ metric: WeeklyMetric?) {
            guard let metric else { return }
            metrics.append(metric)
            guard let change = metric.change else { return }
            switch metric.trend {
            case .better: wins.append("\(metric.title): \(change)")
            case .worse: concerns.append("\(metric.title): \(change)")
            default: break
            }
        }

        note(sleepMetric(current, before, goal: input.sleepGoalMinutes))
        note(trainingMetric(current, before))
        note(loadMetric(input, week: week))
        note(screenMetric(current, before, limit: input.screenLimitMinutes))
        note(nutritionMetric(current, before, goal: input.kcalGoal))
        note(moodMetric(current, before))
        note(cognitiveMetric(current, before))
        note(gameMetric(current, before, elo: input.elo, week: week))

        let goals = goalChecks(current, input: input)
        let tips = tipsFor(current, input: input, week: week)
        return WeeklyReport(
            week: week,
            score: current.score(goal: input.sleepGoalMinutes),
            previousScore: before.score(goal: input.sleepGoalMinutes),
            activeDays: current.activeDays,
            elapsedDays: current.elapsedDays,
            metrics: metrics,
            goals: goals,
            wins: Array(wins.prefix(3)),
            concerns: Array(concerns.prefix(3)),
            tips: Array(tips.prefix(3))
        )
    }

    // MARK: One week of data

    struct Slice {
        let sleepMinutes: [Int]
        let workouts: [Workout]
        let screen: [Double]
        let kcal: [Double]
        let mood: [MoodEntry]
        let cognitive: [CognitiveResult]
        let matches: [GameMatch]
        let activeDays: Int
        let elapsedDays: Int

        init(_ input: WeeklyInputs, _ week: WeekRange, elapsedDays: Int = 7) {
            self.elapsedDays = elapsedDays
            sleepMinutes = input.sleep.filter { week.contains($0.end) }.map(\.durationMinutes)
            workouts = input.workouts.filter { week.contains($0.date) }
            screen = input.screenMinutes.filter { week.contains($0.date) && $0.value > 0 }.map(\.value)
            kcal = input.kcal.filter { week.contains($0.date) }.map(\.value)
            mood = input.mood.filter { week.contains($0.day) }
            cognitive = input.cognitive.filter { week.contains($0.date) }
            matches = input.matches.filter { week.contains($0.date) }
            let dates = input.sleep.map(\.end) + input.workouts.map(\.date) + input.kcal.map(\.date)
                + input.mood.map(\.day) + input.cognitive.map(\.date)
            activeDays = Set(dates.filter { week.contains($0) }.map(\.startOfDay)).count
        }

        var sleepAverage: Double? { StatisticsMath.mean(sleepMinutes.map(Double.init)) }
        var moodAverage: Double? { StatisticsMath.mean(mood.map { Double($0.mood) }) }
        var energyAverage: Double? { StatisticsMath.mean(mood.map { Double($0.energy) }) }
        var cognitiveAverage: Double? { StatisticsMath.mean(cognitive.map { Double($0.score) }) }

        func score(goal: Int) -> Int? {
            WeeklyReportBuilder.score(sleepAverage: sleepAverage, sleepGoal: goal, mood: moodAverage,
                                      energy: energyAverage, cognitive: cognitiveAverage,
                                      activeDays: activeDays, elapsedDays: elapsedDays)
        }
    }

    // MARK: Metrics

    private static func trend(_ now: Double, _ before: Double?, higherIsBetter: Bool, threshold: Double) -> WeeklyMetric.Trend {
        guard let before else { return .none }
        let diff = now - before
        if abs(diff) < threshold { return .same }
        return (diff > 0) == higherIsBetter ? .better : .worse
    }

    private static func signed(_ value: Double, digits: Int = 0) -> String {
        Formatters.signedDecimal(value, fractionDigits: digits)
    }

    static func sleepMetric(_ now: Slice, _ before: Slice, goal: Int) -> WeeklyMetric? {
        guard let average = now.sleepAverage else { return nil }
        let previous = before.sleepAverage
        // More sleep is better until an hour above the goal.
        let higherIsBetter = average <= Double(goal + 60)
        let diff = previous.map { Int((average - $0).rounded()) }
        return WeeklyMetric(
            id: "sleep", title: "Сон", icon: "moon", tint: Theme.Palette.purple,
            value: Formatters.duration(minutes: Int(average.rounded())),
            detail: "в среднем за ночь · \(now.sleepMinutes.count) ноч.",
            change: diff.map { "\($0 >= 0 ? "+" : "−")\(Formatters.duration(minutes: abs($0)))" },
            trend: trend(average, previous, higherIsBetter: higherIsBetter, threshold: 10)
        )
    }

    static func trainingMetric(_ now: Slice, _ before: Slice) -> WeeklyMetric? {
        guard !now.workouts.isEmpty || !before.workouts.isEmpty else { return nil }
        let minutes = now.workouts.reduce(0) { $0 + $1.minutes }
        let previous = before.workouts.reduce(0) { $0 + $1.minutes }
        return WeeklyMetric(
            id: "training", title: "Тренировки", icon: "figure.run", tint: Theme.Palette.orange,
            value: "\(now.workouts.count) · \(minutes) мин", detail: "за неделю",
            change: before.workouts.isEmpty ? nil : "\(Formatters.signed(minutes - previous)) мин",
            trend: trend(Double(minutes), before.workouts.isEmpty ? nil : Double(previous), higherIsBetter: true, threshold: 15)
        )
    }

    static func loadMetric(_ input: WeeklyInputs, week: WeekRange) -> WeeklyMetric? {
        let upToWeekEnd = input.dailyLoad.filter { $0.date < week.end }
        let thisWeek = upToWeekEnd.filter { week.contains($0.date) }.reduce(0) { $0 + $1.value }
        guard thisWeek > 0 else { return nil }
        let ratio = TrainingLoadMath.ratio(daily: Array(upToWeekEnd.suffix(TrainingLoadMath.chronicDays)))
        let status = ratio.status
        return WeeklyMetric(
            id: "load", title: "Нагрузка", icon: "bolt.heart", tint: status.color,
            value: "\(Formatters.grouped(Int(thisWeek))) ед.",
            detail: ratio.value.map { "ACWR \(Formatters.decimal($0, fractionDigits: 2)) · \(status.title.lowercased())" } ?? status.title,
            change: nil,
            trend: status == .high ? .worse : (status == .optimal ? .better : .none)
        )
    }

    static func screenMetric(_ now: Slice, _ before: Slice, limit: Int) -> WeeklyMetric? {
        guard let average = StatisticsMath.mean(now.screen) else { return nil }
        let previous = StatisticsMath.mean(before.screen)
        let overLimit = now.screen.filter { $0 > Double(limit) }.count
        let diff = previous.map { Int((average - $0).rounded()) }
        return WeeklyMetric(
            id: "screen", title: "Экранное время", icon: "iphone", tint: Theme.Palette.cyan,
            value: Formatters.duration(minutes: Int(average.rounded())),
            detail: overLimit == 0 ? "в день · лимит не превышен" : "в день · лимит превышен \(overLimit) дн.",
            change: diff.map { "\($0 >= 0 ? "+" : "−")\(Formatters.duration(minutes: abs($0)))" },
            trend: trend(average, previous, higherIsBetter: false, threshold: 15)
        )
    }

    static func nutritionMetric(_ now: Slice, _ before: Slice, goal: Int) -> WeeklyMetric? {
        guard let average = StatisticsMath.mean(now.kcal) else { return nil }
        let previous = StatisticsMath.mean(before.kcal)
        // Closer to the goal is better.
        let distance = abs(average - Double(goal))
        let previousDistance = previous.map { abs($0 - Double(goal)) }
        return WeeklyMetric(
            id: "nutrition", title: "Питание", icon: "fork.knife", tint: Theme.Palette.green,
            value: "\(Formatters.grouped(Int(average.rounded()))) ккал",
            detail: "в среднем · записано \(now.kcal.count) из 7 дней",
            change: previous.map { "\(signed(average - $0)) ккал" },
            trend: trend(-distance, previousDistance.map { -$0 }, higherIsBetter: true, threshold: 50)
        )
    }

    static func moodMetric(_ now: Slice, _ before: Slice) -> WeeklyMetric? {
        guard let mood = now.moodAverage, let energy = now.energyAverage else { return nil }
        let previous = before.moodAverage
        return WeeklyMetric(
            id: "mood", title: "Настроение", icon: "face.smiling", tint: Theme.Palette.yellow,
            value: "\(Formatters.decimal(mood)) · \(Formatters.decimal(energy))",
            detail: "настроение · энергия из 10",
            change: previous.map { signed(mood - $0, digits: 1) },
            trend: trend(mood, previous, higherIsBetter: true, threshold: 0.3)
        )
    }

    static func cognitiveMetric(_ now: Slice, _ before: Slice) -> WeeklyMetric? {
        guard let average = now.cognitiveAverage else { return nil }
        let previous = before.cognitiveAverage
        let days = Set(now.cognitive.map { $0.date.startOfDay }).count
        return WeeklyMetric(
            id: "cognitive", title: "Когнитивная форма", icon: "brain.head.profile", tint: Theme.Palette.green,
            value: "\(Int(average.rounded())) баллов", detail: "тесты в \(days) дн.",
            change: previous.map { "\(signed(average - $0)) б." },
            trend: trend(average, previous, higherIsBetter: true, threshold: 2)
        )
    }

    static func gameMetric(_ now: Slice, _ before: Slice, elo: [DailyValue], week: WeekRange) -> WeeklyMetric? {
        guard !now.matches.isEmpty else { return nil }
        let summary = GameSummary(matches: now.matches)
        let previous = before.matches.isEmpty ? nil : GameSummary(matches: before.matches)
        let weekElo = elo.filter { week.contains($0.date) }
        let eloChange = (weekElo.last?.value).flatMap { last in
            (elo.last { $0.date < week.start } ?? weekElo.first).map { Int(last - $0.value) }
        }
        var detail = "\(summary.matches) матчей · победы \(Int(summary.winRate.rounded()))%"
        if let eloChange { detail += " · \(Formatters.signed(eloChange)) ELO" }
        return WeeklyMetric(
            id: "game", title: "Игра", icon: "gamecontroller", tint: Theme.Palette.cyan,
            value: "K/D \(Formatters.decimal(summary.kd, fractionDigits: 2))", detail: detail,
            change: previous.map { "K/D \(signed(summary.kd - $0.kd, digits: 2))" },
            trend: trend(summary.kd, previous?.kd, higherIsBetter: true, threshold: 0.05)
        )
    }

    // MARK: Goals and tips

    static func goalChecks(_ now: Slice, input: WeeklyInputs) -> [WeeklyGoalCheck] {
        var checks: [WeeklyGoalCheck] = []
        if !now.sleepMinutes.isEmpty {
            checks.append(WeeklyGoalCheck(id: "sleep", title: "Ночей с нормой сна",
                                          achieved: now.sleepMinutes.filter { $0 >= input.sleepGoalMinutes - 30 }.count,
                                          total: now.sleepMinutes.count))
        }
        if !now.screen.isEmpty {
            checks.append(WeeklyGoalCheck(id: "screen", title: "Дней в лимите экрана",
                                          achieved: now.screen.filter { $0 <= Double(input.screenLimitMinutes) }.count,
                                          total: now.screen.count))
        }
        if !now.kcal.isEmpty {
            let goal = Double(input.kcalGoal)
            checks.append(WeeklyGoalCheck(id: "kcal", title: "Дней в цели по калориям (±15%)",
                                          achieved: now.kcal.filter { abs($0 - goal) <= goal * 0.15 }.count,
                                          total: now.kcal.count))
        }
        checks.append(WeeklyGoalCheck(id: "days", title: "Дней с записями", achieved: now.activeDays,
                                      total: max(now.elapsedDays, now.activeDays)))
        return checks
    }

    static func tipsFor(_ now: Slice, input: WeeklyInputs, week: WeekRange) -> [String] {
        var tips: [(goal: FocusGoal?, text: String)] = []
        if let sleep = now.sleepAverage, sleep < Double(input.sleepGoalMinutes - 30) {
            let missing = Formatters.duration(minutes: input.sleepGoalMinutes - Int(sleep))
            tips.append((.sleep, "До нормы сна не хватает \(missing) за ночь — попробуй ложиться на 30 минут раньше."))
        }
        if now.screen.filter({ $0 > Double(input.screenLimitMinutes) }).count >= 3 {
            tips.append((.screen, "Лимит экранного времени превышен 3+ дня — убери телефон за час до сна."))
        }
        if let load = loadMetric(input, week: week), load.trend == .worse {
            tips.append((.fitness, "Нагрузка растёт слишком быстро — запланируй лёгкий день и следи за восстановлением."))
        }
        if let energy = now.energyAverage, energy < 5 {
            tips.append((.energy, "Энергия ниже 5 из 10 — проверь сон, воду и перерывы между игровыми сессиями."))
        }
        if input.focus.contains(.tilt), let mood = now.moodAverage, mood < 6 {
            tips.append((.tilt, "Настроение ниже 6 из 10 — после поражений делай паузу 5 минут перед следующей игрой."))
        }
        if Set(now.cognitive.map { $0.date.startOfDay }).count < 3 {
            tips.append((.focus, "Проходи когнитивную батарею хотя бы 3 раза в неделю — так тренд будет точнее."))
        }
        if now.kcal.count < 4 {
            tips.append((.nutrition, "Записывай еду хотя бы 5 дней в неделю, чтобы видеть реальную картину питания."))
        }
        if tips.isEmpty { return ["Отличная неделя — держи режим и дальше."] }
        // Stable sort: tips for the player's own goals first, the rest keep their order.
        let prioritized = tips.enumerated().sorted { lhs, rhs in
            let left = lhs.element.goal.map(input.focus.contains) ?? false
            let right = rhs.element.goal.map(input.focus.contains) ?? false
            return left == right ? lhs.offset < rhs.offset : left
        }
        return prioritized.map(\.element.text)
    }
}

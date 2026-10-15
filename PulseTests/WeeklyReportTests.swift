import Foundation
import Testing
@testable import Pulse

struct WeekRangeTests {
    // Wednesday, 15 October 2025.
    private let wednesday = Calendar.pulse.date(from: DateComponents(year: 2025, month: 10, day: 15, hour: 12))!

    @Test func startsOnMondayAndSpansSevenDays() {
        let week = WeekRange.containing(wednesday)
        #expect(Calendar.pulse.component(.weekday, from: week.start) == 2)
        #expect(week.days.count == 7)
        #expect(week.end == week.start.adding(days: 7))
        #expect(week.contains(wednesday))
        #expect(!week.contains(week.end))
    }

    @Test func navigatesBetweenWeeks() {
        let week = WeekRange.containing(wednesday)
        #expect(week.previous.end == week.start)
        #expect(week.next.start == week.end)
        #expect(week.previous.next == week)
    }

    @Test func titleSpansMonths() {
        #expect(WeekRange.containing(wednesday).title.hasPrefix("13–19"))
        let crossing = WeekRange.containing(Calendar.pulse.date(from: DateComponents(year: 2025, month: 10, day: 1))!)
        #expect(crossing.title.contains("–"))
        #expect(crossing.title.contains(" – "))   // 29 сентября – 5 октября
    }
}

struct WeeklyReportBuilderTests {
    private let week = WeekRange.containing(Calendar.pulse.date(from: DateComponents(year: 2025, month: 10, day: 15, hour: 12))!)

    private func sleep(on day: Date, minutes: Int) -> SleepSession {
        let end = day.setting(hour: 8, minute: 0)
        return SleepSession(id: UUID(), start: end.adding(minutes: -minutes), end: end)
    }

    private func mood(_ day: Date, _ mood: Int, _ energy: Int) -> MoodEntry {
        MoodEntry(id: UUID(), day: day, slot: .morning, mood: mood, energy: energy)
    }

    private func cognitive(_ day: Date, _ score: Int) -> CognitiveResult {
        CognitiveResult(id: UUID(), kind: .reaction, date: day.setting(hour: 10, minute: 0), score: score, metric: "")
    }

    private var input: WeeklyInputs {
        var input = WeeklyInputs()
        input.sleepGoalMinutes = 480
        // Last week: 6 h nights, low mood; this week: 7 h 30 m, better mood.
        input.sleep = week.previous.days.map { sleep(on: $0, minutes: 360) } + week.days.map { sleep(on: $0, minutes: 450) }
        input.mood = week.previous.days.map { mood($0, 5, 5) } + week.days.map { mood($0, 7, 6) }
        input.cognitive = [cognitive(week.previous.start, 60), cognitive(week.start, 70), cognitive(week.start.adding(days: 2), 74)]
        input.screenLimitMinutes = 240
        input.screenMinutes = week.days.enumerated().map { DailyValue(date: $1, value: $0 < 3 ? 300 : 200) }
        return input
    }

    @Test func emptyInputGivesEmptyReport() {
        let report = WeeklyReportBuilder.build(WeeklyInputs(), week: week)
        #expect(report.isEmpty)
        #expect(report.score == nil)
        #expect(report.activeDays == 0)
    }

    @Test func comparesWithPreviousWeek() throws {
        let report = WeeklyReportBuilder.build(input, week: week)
        let sleepMetric = try #require(report.metrics.first { $0.id == "sleep" })
        #expect(sleepMetric.trend == .better)
        #expect(sleepMetric.change?.hasPrefix("+") == true)
        let moodMetric = try #require(report.metrics.first { $0.id == "mood" })
        #expect(moodMetric.trend == .better)
        #expect(report.activeDays == 7)
        let score = try #require(report.score)
        let previous = try #require(report.previousScore)
        #expect(score > previous)
        #expect(report.wins.contains { $0.hasPrefix("Сон") })
    }

    @Test func screenOverLimitBecomesTipAndGoal() throws {
        let report = WeeklyReportBuilder.build(input, week: week)
        let screenGoal = try #require(report.goals.first { $0.id == "screen" })
        #expect(screenGoal.achieved == 4)
        #expect(screenGoal.total == 7)
        #expect(report.tips.contains { $0.contains("Лимит экранного времени") })
        let sleepGoal = try #require(report.goals.first { $0.id == "sleep" })
        #expect(sleepGoal.achieved == 7)    // 7 h 30 m is within 30 minutes of the goal
    }

    @Test func tipsAreCappedAtThree() {
        var input = input
        input.sleep = week.days.map { sleep(on: $0, minutes: 300) }
        input.mood = week.days.map { mood($0, 4, 3) }
        let report = WeeklyReportBuilder.build(input, week: week)
        #expect(report.tips.count == 3)
        #expect(report.tips[0].contains("До нормы сна"))
    }

    @Test func scoreCombinesAvailableParts() {
        #expect(WeeklyReportBuilder.score(sleepAverage: nil, sleepGoal: 480, mood: nil, energy: nil, cognitive: nil, activeDays: 5) == nil)
        // sleep 100, mood 80, consistency 100 → 93
        #expect(WeeklyReportBuilder.score(sleepAverage: 480, sleepGoal: 480, mood: 8, energy: nil, cognitive: nil, activeDays: 7) == 93)
    }
}

struct TeamWeeklyBuilderTests {
    private let week = WeekRange.containing(.now)

    private func row(_ name: String, readiness: Double, previous: Double, sleep: Int, matches: Int = 3, kd: Double = 1.1) -> TeamWeeklyRow {
        TeamWeeklyRow(id: UUID(), nickname: name, readiness: readiness, previousReadiness: previous,
                      sleepMinutes: sleep, previousSleepMinutes: sleep - 30, matches: matches, winRate: 50,
                      kd: kd, previousKD: kd - 0.1, load: TrainingLoadMath.ratio(daily: []))
    }

    @Test func aggregatesTeamAndFlagsRisks() {
        let rows = [row("alpha", readiness: 8, previous: 7, sleep: 480, kd: 1.4),
                    row("bravo", readiness: 5.5, previous: 6, sleep: 350)]
        let report = TeamWeeklyBuilder.build(rows: rows, week: week, wins: 4, previousWins: 2, previousMatches: 5)
        #expect(report.readiness == 6.75)
        #expect(report.matches == 6)
        #expect(report.winRate.map { Int($0.rounded()) } == 67)
        #expect(report.previousWinRate == 40)
        #expect(report.rows.first?.nickname == "alpha")
        #expect(report.risks.count == 1)
        #expect(report.risks[0].hasPrefix("bravo"))
        #expect(report.highlights.contains { $0.contains("alpha") })
    }

    @Test func demoCoversAllPlayers() {
        let report = TeamWeeklyBuilder.demo(week: week.previous)
        #expect(report.rows.count == DemoData.players.count)
        #expect(report.readiness != nil)
        #expect(!report.tips.isEmpty)
    }
}

struct WeeklyElapsedDaysTests {
    @Test func currentWeekCountsOnlyElapsedDays() {
        let thursday = Calendar.pulse.date(from: DateComponents(year: 2025, month: 10, day: 16, hour: 9))!
        let week = WeekRange.containing(thursday)
        #expect(week.elapsedDays(now: thursday) == 4)
        #expect(week.previous.elapsedDays(now: thursday) == 7)
        #expect(week.next.elapsedDays(now: thursday) == 0)
        // 4 active days out of 4 elapsed is full consistency.
        #expect(WeeklyReportBuilder.score(sleepAverage: 480, sleepGoal: 480, mood: nil, energy: nil, cognitive: nil,
                                          activeDays: 4, elapsedDays: 4) == 100)
    }
}

struct WeeklyFocusTipsTests {
    @Test func questionnaireGoalsComeFirst() {
        let week = WeekRange.containing(Calendar.pulse.date(from: DateComponents(year: 2025, month: 10, day: 15, hour: 12))!)
        var input = WeeklyInputs()
        input.sleep = week.days.map { day in
            let end = day.setting(hour: 8, minute: 0)
            return SleepSession(id: UUID(), start: end.adding(minutes: -300), end: end)
        }
        let plain = WeeklyReportBuilder.build(input, week: week)
        #expect(plain.tips.first?.contains("До нормы сна") == true)
        input.focus = [.nutrition]
        let focused = WeeklyReportBuilder.build(input, week: week)
        #expect(focused.tips.first?.contains("Записывай еду") == true)
    }
}

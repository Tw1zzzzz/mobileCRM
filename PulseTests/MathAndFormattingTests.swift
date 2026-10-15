import Foundation
import Testing
@testable import Pulse

struct StatisticsMathTests {
    @Test func pearsonPerfectCorrelations() throws {
        let xs = [1.0, 2, 3, 4, 5]
        let positive = try #require(StatisticsMath.pearson(xs, xs.map { $0 * 2 + 1 }))
        let negative = try #require(StatisticsMath.pearson(xs, xs.map { -$0 }))
        #expect(abs(positive - 1) < 1e-9)
        #expect(abs(negative + 1) < 1e-9)
    }

    @Test func pearsonUndefinedCases() {
        #expect(StatisticsMath.pearson([1, 2], [3, 4]) == nil)
        #expect(StatisticsMath.pearson([1, 2, 3], [5, 5, 5]) == nil)
        #expect(StatisticsMath.pearson([1, 2, 3], [1, 2]) == nil)
    }

    @Test func meanAndPercentChange() {
        #expect(StatisticsMath.mean([]) == nil)
        #expect(StatisticsMath.mean([2, 4, 6]) == 4)
        #expect(StatisticsMath.percentChange(from: 0, to: 10) == nil)
        #expect(StatisticsMath.percentChange(from: 200, to: 170) == -15)
    }

    @Test func progressIsClamped() {
        #expect(StatisticsMath.clampedProgress(150, goal: 100) == 1)
        #expect(StatisticsMath.clampedProgress(-5, goal: 100) == 0)
        #expect(StatisticsMath.clampedProgress(5, goal: 0) == 0)
        #expect(StatisticsMath.clampedProgress(48, goal: 60) == 0.8)
    }

    @Test func correlationStrengthBuckets() {
        #expect(CorrelationStrength(0.75) == .strong)
        #expect(CorrelationStrength(-0.4) == .moderate)
        #expect(CorrelationStrength(0.1) == .weak)
    }
}

struct CognitiveScoringTests {
    @Test func reactionScoreBounds() {
        #expect(CognitiveScoring.reactionScore(averageMs: 200) == 100)
        #expect(CognitiveScoring.reactionScore(averageMs: 500) == 0)
        #expect(CognitiveScoring.reactionScore(averageMs: 900) == 0)
        #expect(CognitiveScoring.reactionScore(averageMs: 350) == 50)
    }

    @Test func memoryAndAccuracyScores() {
        #expect(CognitiveScoring.memoryScore(span: 3) == 0)
        #expect(CognitiveScoring.memoryScore(span: 9) == 100)
        #expect(CognitiveScoring.accuracyScore(correct: 15, total: 20) == 75)
        #expect(CognitiveScoring.accuracyScore(correct: 1, total: 0) == 0)
    }
}

struct FormattersTests {
    @Test func durations() {
        #expect(Formatters.duration(minutes: 24) == "24 мин")
        #expect(Formatters.duration(minutes: 213) == "3 ч 33 м")
        #expect(Formatters.durationParts(minutes: 462) == (7, 42))
        #expect(Formatters.timer(3725) == "01:02:05")
    }

    @Test func numbers() {
        #expect(Formatters.decimal(74.2) == "74,2")
        #expect(Formatters.signedDecimal(-0.3) == "−0,3")
        #expect(Formatters.signedDecimal(-0.02) == "0,0")
        #expect(Formatters.signed(-3) == "−3")
        #expect(Formatters.signedDecimal(0.1) == "+0,1")
        #expect(Formatters.grouped(6840) == "6 840")
        #expect(Formatters.liters(milliliters: 2500) == "2,5")
        #expect(Formatters.liters(milliliters: 0) == "0")
    }

    @Test func demoNightMatchesMockup() {
        let night = DemoData.lastNight()
        #expect(night.totalMinutes == 462)
        #expect(night.minutes(in: .awake) == 24)
        #expect(night.minutes(in: .rem) == 122)
        #expect(night.minutes(in: .deep) == 103)
        #expect(night.minutes(in: .light) == 213)
        #expect(Formatters.time(night.start) == "23:12")
        #expect(Formatters.time(night.end) == "06:54")
    }
}

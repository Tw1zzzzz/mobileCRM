import Foundation
import Testing
@testable import Pulse

struct MindsetMathTests {
    private func answers(_ value: Int) -> [String: Int] {
        Dictionary(uniqueKeysWithValues: MindsetTest.questions.map { ($0.id, value) })
    }

    @Test func questionnaireIsBalanced() {
        #expect(MindsetTest.questions.count == 24)
        #expect(Set(MindsetTest.questions.map(\.id)).count == 24)
        for trait in MindsetTrait.allCases {
            let items = MindsetTest.questions.filter { $0.trait == trait }
            #expect(items.count == 4)
            #expect(items.filter(\.reversed).count == 2)
        }
    }

    @Test func reversedItemsAreFlipped() {
        // Agreeing with everything: direct items → 5, reversed → 1, so every trait lands at 50.
        let scores = MindsetMath.scores(answers(5))
        #expect(MindsetTrait.allCases.allSatisfy { scores[$0] == 50 })
        var best: [String: Int] = [:]
        for question in MindsetTest.questions { best[question.id] = question.reversed ? 1 : 5 }
        #expect(MindsetMath.scores(best).values.allSatisfy { $0 == 100 })
    }

    @Test func ignoresMissingAndInvalidAnswers() {
        #expect(MindsetMath.scores([:]).isEmpty)
        #expect(MindsetMath.scores(["q1": 9]).isEmpty)
        #expect(MindsetMath.scores(["q1": 5])[.composure] == 100)
        #expect(!MindsetMath.isComplete(["q1": 5]))
        #expect(MindsetMath.isComplete(answers(3)))
    }

    @Test func demoAnswersRoundTrip() {
        let target: [MindsetTrait: Int] = [.composure: 81, .resilience: 44, .focus: 88, .teamwork: 63, .leadership: 31, .drive: 75]
        let scores = MindsetMath.scores(MindsetMath.answers(for: target))
        for (trait, value) in target {
            #expect(abs((scores[trait] ?? 0) - value) <= 7, "\(trait)")
        }
    }

    @Test func archetypeStrengthsAndGrowthZones() {
        let profile = MindsetProfile(scores: [.composure: 60, .resilience: 40, .focus: 72, .teamwork: 65, .leadership: 90, .drive: 50])
        #expect(profile.archetype.title == "Лидерство")
        #expect(profile.archetype.caption == "Сильнее всего")
        #expect(profile.strengths == [.leadership, .focus])
        #expect(profile.growthZones == [.resilience, .drive])
        #expect(profile.orderedScores == [60, 40, 72, 65, 90, 50])
    }

    @Test func flatProfileIsAllRounder() {
        let profile = MindsetProfile(scores: [.composure: 62, .resilience: 58, .focus: 60, .teamwork: 65, .leadership: 56, .drive: 61])
        #expect(profile.archetype.title == "Ровный по всем чертам")
        #expect(profile.strengths == [.teamwork])   // no trait ≥ 70 → the best one
        #expect(profile.growthZones.isEmpty)        // nothing below 55
    }
}

struct PlayerSurveyStoreTests {
    private func makeStore() -> (PlayerSurveyStore, JSONFileStorage<PlayerSurveyStore.State>) {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let storage = JSONFileStorage<PlayerSurveyStore.State>(filename: "player-survey.json", directory: folder)
        return (PlayerSurveyStore(storage: storage), storage)
    }

    @Test func newAccountNeedsOnboardingUntilAnsweredOrPostponed() {
        let (store, _) = makeStore()
        #expect(store.needsOnboarding)
        store.dismissOnboarding()
        #expect(!store.needsOnboarding)
        #expect(!store.isComplete)
    }

    @Test func savesSurveyAndMindsetHistory() throws {
        let (store, storage) = makeStore()
        store.saveSurvey(PlayerSurvey(goals: [.tilt, .sleep], role: .igl, level: .semiPro))
        #expect(!store.needsOnboarding)
        #expect(!store.addMindset(["q1": 3]))   // incomplete runs are not saved
        let all3 = Dictionary(uniqueKeysWithValues: MindsetTest.questions.map { ($0.id, 3) })
        #expect(store.addMindset(all3, at: .now.adding(days: -40)))
        #expect(store.addMindset(all3))
        #expect(store.isComplete)
        #expect(store.previousProfile != nil)

        let reloaded = PlayerSurveyStore(storage: storage)
        let survey = try #require(reloaded.survey)
        #expect(survey.role == .igl)
        #expect(survey.goals == [.tilt, .sleep])
        #expect(reloaded.state.mindset.count == 2)
    }

    @Test func demoProfilesExistForTheWholeTeam() {
        for player in DemoData.players {
            let state = PlayerSurveyDemo.state(for: player.nickname)
            #expect(state.survey != nil, "\(player.nickname)")
            #expect(state.mindset.count == 2)
            #expect(state.mindset.allSatisfy { MindsetMath.isComplete($0.answers) })
        }
        #expect(PlayerSurveyDemo.state(for: "Raven").survey?.role == .rifler)
        #expect(PlayerSurveyDemo.state(for: "Unknown").survey == nil)
    }

    @Test func yearsArePluralised() {
        #expect(Formatters.years(1) == "1 год")
        #expect(Formatters.years(3) == "3 года")
        #expect(Formatters.years(11) == "11 лет")
        #expect(Formatters.years(22) == "22 года")
    }
}

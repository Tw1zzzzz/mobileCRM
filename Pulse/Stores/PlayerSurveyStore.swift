import Foundation
import Observation

/// First-login questionnaire and «Игровой характер» results («player-survey.json», synced as one document).
@Observable
final class PlayerSurveyStore {
    struct State: Codable, Equatable {
        var survey: PlayerSurvey?
        /// Oldest first.
        var mindset: [MindsetResult] = []
        /// «Позже» on the welcome screen: no full-screen questionnaire, only a reminder card.
        var onboardingDismissed = false
    }

    private(set) var state: State
    private let storage: JSONFileStorage<State>?

    init(storage: JSONFileStorage<State>? = JSONFileStorage(filename: "player-survey.json")) {
        self.storage = storage
        self.state = storage?.load() ?? State()
    }

    var survey: PlayerSurvey? { state.survey }
    var latestResult: MindsetResult? { state.mindset.last }
    var profile: MindsetProfile? { latestResult.map { MindsetProfile(scores: $0.scores) } }

    var previousProfile: MindsetProfile? {
        guard state.mindset.count > 1 else { return nil }
        return MindsetProfile(scores: state.mindset[state.mindset.count - 2].scores)
    }

    /// Shown full-screen after sign-in until answered or postponed.
    var needsOnboarding: Bool { state.survey == nil && !state.onboardingDismissed }
    var isComplete: Bool { state.survey != nil && !state.mindset.isEmpty }

    func nextRetakeDate() -> Date? {
        latestResult.map { $0.date.adding(days: MindsetTest.retakeDays) }
    }

    func reload(user: User?) {
        if let saved = storage?.load() {
            state = saved
        } else if let user, user.isDemo {
            state = PlayerSurveyDemo.state(for: user.nickname)
        } else {
            state = State()
        }
    }

    func saveSurvey(_ survey: PlayerSurvey) {
        var updated = survey
        updated.updatedAt = .now
        persist(State(survey: updated, mindset: state.mindset, onboardingDismissed: state.onboardingDismissed))
    }

    /// Ignores incomplete answer sets.
    @discardableResult
    func addMindset(_ answers: [String: Int], at date: Date = .now) -> Bool {
        guard MindsetMath.isComplete(answers) else { return false }
        persist(State(survey: state.survey, mindset: state.mindset + [MindsetResult(date: date, answers: answers)],
                      onboardingDismissed: state.onboardingDismissed))
        return true
    }

    func dismissOnboarding() {
        persist(State(survey: state.survey, mindset: state.mindset, onboardingDismissed: true))
    }

    private func persist(_ newState: State) {
        state = newState
        storage?.save(newState)
    }
}

/// Demo profiles: the staff view of a player matches what that player sees in their own app.
enum PlayerSurveyDemo {
    private struct Seed {
        let survey: PlayerSurvey
        let scores: [MindsetTrait: Int]
    }

    private static let seeds: [String: Seed] = [
        "Nova": Seed(survey: PlayerSurvey(goals: [.rankUp, .consistency, .sleep], role: .awper, level: .semiPro,
                                          yearsPlaying: 6, hoursPerWeek: 28, playTime: .evening, motivation: .career),
                     scores: [.composure: 81, .resilience: 69, .focus: 88, .teamwork: 63, .leadership: 44, .drive: 75]),
        "Blitz": Seed(survey: PlayerSurvey(goals: [.tilt, .rankUp, .fitness], role: .entry, level: .semiPro,
                                           yearsPlaying: 4, hoursPerWeek: 25, playTime: .night, motivation: .winning),
                      scores: [.composure: 63, .resilience: 44, .focus: 56, .teamwork: 81, .leadership: 56, .drive: 63]),
        "Echo": Seed(survey: PlayerSurvey(goals: [.tilt, .sleep, .proTeam], role: .igl, level: .semiPro,
                                          yearsPlaying: 7, hoursPerWeek: 30, playTime: .evening, motivation: .team),
                     scores: [.composure: 50, .resilience: 44, .focus: 63, .teamwork: 75, .leadership: 88, .drive: 69]),
        "Frost": Seed(survey: PlayerSurvey(goals: [.consistency, .nutrition, .energy], role: .support, level: .semiPro,
                                           yearsPlaying: 5, hoursPerWeek: 22, playTime: .day, motivation: .team),
                      scores: [.composure: 63, .resilience: 75, .focus: 69, .teamwork: 88, .leadership: 38, .drive: 81]),
        "Ghost": Seed(survey: PlayerSurvey(goals: [.focus, .screen, .rankUp], role: .lurker, level: .semiPro,
                                           yearsPlaying: 3, hoursPerWeek: 26, playTime: .night, motivation: .growth),
                      scores: [.composure: 81, .resilience: 69, .focus: 75, .teamwork: 44, .leadership: 31, .drive: 56]),
        "Raven": Seed(survey: PlayerSurvey(goals: [.rankUp, .tilt, .screen], role: .rifler, level: .amateur,
                                           yearsPlaying: 2, hoursPerWeek: 18, playTime: .night, motivation: .growth),
                      scores: [.composure: 56, .resilience: 38, .focus: 63, .teamwork: 50, .leadership: 44, .drive: 75])
    ]

    /// Archetype from the latest demo result (staff roster).
    static func archetype(for nickname: String) -> MindsetArchetype? {
        seeds[nickname].map { MindsetProfile(scores: $0.scores).archetype }
    }

    static func state(for nickname: String, now: Date = .now) -> PlayerSurveyStore.State {
        guard let seed = seeds[nickname] else { return PlayerSurveyStore.State() }
        // An older, slightly weaker result shows the change between re-takes.
        let earlier = seed.scores.mapValues { max($0 - 6, 0) }
        return PlayerSurveyStore.State(
            survey: seed.survey,
            mindset: [
                MindsetResult(date: now.adding(days: -48), answers: MindsetMath.answers(for: earlier)),
                MindsetResult(date: now.adding(days: -12), answers: MindsetMath.answers(for: seed.scores))
            ],
            onboardingDismissed: true
        )
    }
}

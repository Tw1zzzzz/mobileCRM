package com.pulse.core.store

import com.pulse.core.demo.PlayerSurveyDemo
import com.pulse.core.demo.PlayerSurveyState
import com.pulse.core.model.GameGoals
import com.pulse.core.model.GameSummary
import com.pulse.core.model.GoalMath
import com.pulse.core.model.GoalProgress
import com.pulse.core.model.MindsetMath
import com.pulse.core.model.MindsetProfile
import com.pulse.core.model.MindsetResult
import com.pulse.core.model.MindsetTest
import com.pulse.core.model.PlayerSurvey
import com.pulse.core.model.User
import com.pulse.core.storage.JsonFileStorage
import com.pulse.core.util.addingDays
import kotlin.time.Clock
import kotlin.time.Instant

/** Personal CS2 targets (ELO, K/D, ADR, win-rate) — «game-goals.json». */
class GameGoalsStore(private val storage: JsonFileStorage<GameGoals>?) : PulseStore<GameGoals>(storage?.load() ?: GameGoals.demo) {
    val goals get() = current

    fun reload(isDemo: Boolean) {
        current = storage?.load() ?: if (isDemo) GameGoals.demo else GameGoals()
    }

    /** The ELO starting point is re-taken only when the ELO target changes, so progress counts from that moment. */
    fun update(goals: GameGoals, currentElo: Int) {
        val eloStart = when {
            goals.elo == null -> null
            goals.elo != current.elo || current.eloStart == null -> currentElo
            else -> current.eloStart
        }
        current = goals.copy(eloStart = eloStart)
        storage?.save(current)
    }

    fun progress(elo: Int, recent: GameSummary): List<GoalProgress> = GoalMath.progress(current, elo, recent)

    fun resetToDemo() {
        current = GameGoals.demo
        storage?.save(current)
    }

    companion object {
        const val FILE = "game-goals.json"
    }
}

/** First-login questionnaire and «Игровой характер» results («player-survey.json»). */
class PlayerSurveyStore(private val storage: JsonFileStorage<PlayerSurveyState>?) :
    PulseStore<PlayerSurveyState>(storage?.load() ?: PlayerSurveyState()) {

    val survey: PlayerSurvey? get() = current.survey
    val latestResult: MindsetResult? get() = current.mindset.lastOrNull()
    val profile: MindsetProfile? get() = latestResult?.let { MindsetProfile(it.scores) }
    val previousProfile: MindsetProfile?
        get() = if (current.mindset.size > 1) MindsetProfile(current.mindset[current.mindset.size - 2].scores) else null

    /** Shown full-screen after sign-in until answered or postponed. */
    val needsOnboarding: Boolean get() = current.survey == null && !current.onboardingDismissed
    val isComplete: Boolean get() = current.survey != null && current.mindset.isNotEmpty()

    fun nextRetakeDate(): Instant? = latestResult?.date?.addingDays(MindsetTest.RETAKE_DAYS)

    fun reload(user: User?) {
        current = storage?.load() ?: if (user?.isDemo == true) PlayerSurveyDemo.state(user.nickname) else PlayerSurveyState()
    }

    fun saveSurvey(survey: PlayerSurvey, now: Instant = Clock.System.now()) = persist(current.copy(survey = survey.copy(updatedAt = now)))

    /** Ignores incomplete answer sets. */
    fun addMindset(answers: Map<String, Int>, at: Instant = Clock.System.now()): Boolean {
        if (!MindsetMath.isComplete(answers)) return false
        persist(current.copy(mindset = current.mindset + MindsetResult(date = at, answers = answers)))
        return true
    }

    fun dismissOnboarding() = persist(current.copy(onboardingDismissed = true))

    private fun persist(state: PlayerSurveyState) {
        current = state
        storage?.save(state)
    }

    companion object {
        const val FILE = "player-survey.json"
    }
}

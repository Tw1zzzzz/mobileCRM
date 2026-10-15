package com.pulse.core.demo

import com.pulse.core.model.FocusGoal
import com.pulse.core.model.MindsetArchetype
import com.pulse.core.model.MindsetMath
import com.pulse.core.model.MindsetProfile
import com.pulse.core.model.MindsetResult
import com.pulse.core.model.MindsetTrait
import com.pulse.core.model.PlayTime
import com.pulse.core.model.PlayerGameRole
import com.pulse.core.model.PlayerMotivation
import com.pulse.core.model.PlayerSurvey
import com.pulse.core.model.SkillLevel
import com.pulse.core.util.addingDays
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

/** Saved questionnaire state of one account («player-survey.json», same shape as iOS). */
@Serializable
data class PlayerSurveyState(
    val survey: PlayerSurvey? = null,
    /** Oldest first. */
    val mindset: List<MindsetResult> = emptyList(),
    /** «Позже» on the welcome screen: no full-screen questionnaire, only a reminder card. */
    val onboardingDismissed: Boolean = false
)

/** Demo profiles: the staff view of a player matches what that player sees in their own app. */
object PlayerSurveyDemo {
    private data class Seed(val survey: PlayerSurvey, val scores: Map<MindsetTrait, Int>)

    private fun scores(composure: Int, resilience: Int, focus: Int, teamwork: Int, leadership: Int, drive: Int) = mapOf(
        MindsetTrait.COMPOSURE to composure, MindsetTrait.RESILIENCE to resilience, MindsetTrait.FOCUS to focus,
        MindsetTrait.TEAMWORK to teamwork, MindsetTrait.LEADERSHIP to leadership, MindsetTrait.DRIVE to drive
    )

    private val seeds: Map<String, Seed> = mapOf(
        "Nova" to Seed(
            PlayerSurvey(listOf(FocusGoal.RANK_UP, FocusGoal.CONSISTENCY, FocusGoal.SLEEP), PlayerGameRole.AWPER, SkillLevel.SEMI_PRO,
                6, 28, PlayTime.EVENING, PlayerMotivation.CAREER),
            scores(81, 69, 88, 63, 44, 75)
        ),
        "Blitz" to Seed(
            PlayerSurvey(listOf(FocusGoal.TILT, FocusGoal.RANK_UP, FocusGoal.FITNESS), PlayerGameRole.ENTRY, SkillLevel.SEMI_PRO,
                4, 25, PlayTime.NIGHT, PlayerMotivation.WINNING),
            scores(63, 44, 56, 81, 56, 63)
        ),
        "Echo" to Seed(
            PlayerSurvey(listOf(FocusGoal.TILT, FocusGoal.SLEEP, FocusGoal.PRO_TEAM), PlayerGameRole.IGL, SkillLevel.SEMI_PRO,
                7, 30, PlayTime.EVENING, PlayerMotivation.TEAM),
            scores(50, 44, 63, 75, 88, 69)
        ),
        "Frost" to Seed(
            PlayerSurvey(listOf(FocusGoal.CONSISTENCY, FocusGoal.NUTRITION, FocusGoal.ENERGY), PlayerGameRole.SUPPORT, SkillLevel.SEMI_PRO,
                5, 22, PlayTime.DAY, PlayerMotivation.TEAM),
            scores(63, 75, 69, 88, 38, 81)
        ),
        "Ghost" to Seed(
            PlayerSurvey(listOf(FocusGoal.FOCUS, FocusGoal.SCREEN, FocusGoal.RANK_UP), PlayerGameRole.LURKER, SkillLevel.SEMI_PRO,
                3, 26, PlayTime.NIGHT, PlayerMotivation.GROWTH),
            scores(81, 69, 75, 44, 31, 56)
        ),
        "Raven" to Seed(
            PlayerSurvey(listOf(FocusGoal.RANK_UP, FocusGoal.TILT, FocusGoal.SCREEN), PlayerGameRole.RIFLER, SkillLevel.AMATEUR,
                2, 18, PlayTime.NIGHT, PlayerMotivation.GROWTH),
            scores(56, 38, 63, 50, 44, 75)
        )
    )

    /** Leading trait of the latest demo result (staff roster). */
    fun archetype(nickname: String): MindsetArchetype? = seeds[nickname]?.let { MindsetProfile(it.scores).archetype }

    fun state(nickname: String, now: Instant = Clock.System.now()): PlayerSurveyState {
        val seed = seeds[nickname] ?: return PlayerSurveyState()
        // An older, slightly weaker result shows the change between re-takes.
        val earlier = seed.scores.mapValues { maxOf(it.value - 6, 0) }
        return PlayerSurveyState(
            survey = seed.survey,
            mindset = listOf(
                MindsetResult(date = now.addingDays(-48), answers = MindsetMath.answers(earlier)),
                MindsetResult(date = now.addingDays(-12), answers = MindsetMath.answers(seed.scores))
            ),
            onboardingDismissed = true
        )
    }
}

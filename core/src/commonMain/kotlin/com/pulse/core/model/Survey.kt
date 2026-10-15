@file:UseSerializers(IsoInstantSerializer::class, UuidSerializer::class)

package com.pulse.core.model

import com.pulse.core.util.Formatters
import com.pulse.core.util.IsoInstantSerializer
import com.pulse.core.util.StatisticsMath
import com.pulse.core.util.Tint
import com.pulse.core.util.UuidSerializer
import com.pulse.core.util.roundToIntSwift
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Answers of the first-login questionnaire («Цели» + «Что ты за игрок»). */
@Serializable
data class PlayerSurvey(
    val goals: List<FocusGoal> = emptyList(),
    val role: PlayerGameRole = PlayerGameRole.RIFLER,
    val level: SkillLevel = SkillLevel.AMATEUR,
    val yearsPlaying: Int = 2,
    val hoursPerWeek: Int = 15,
    val playTime: PlayTime = PlayTime.EVENING,
    val motivation: PlayerMotivation = PlayerMotivation.GROWTH,
    val updatedAt: Instant = Clock.System.now()
) {
    /** «AWPer · Полупро · 4 года в CS · 20 ч/нед». */
    val summary: String
        get() = "${role.title} · ${level.title} · ${Formatters.years(yearsPlaying)} в CS · $hoursPerWeek ч/нед"
}

data class MindsetQuestion(
    val id: String,
    val text: String,
    val trait: MindsetTrait,
    /** Agreeing means a lower trait score. */
    val reversed: Boolean
)

/** Result of one test run; answers are 1…5 keyed by question id. */
@Serializable
data class MindsetResult(
    val id: Uuid = Uuid.random(),
    val date: Instant = Clock.System.now(),
    val answers: Map<String, Int>
) {
    val scores: Map<MindsetTrait, Int> get() = MindsetMath.scores(answers)
}

object MindsetTest {
    val answerTitles = listOf("Совсем не про меня", "Скорее нет", "Иногда", "Скорее да", "Точно про меня")

    /** Suggested interval between re-takes. */
    const val RETAKE_DAYS = 30

    val questions: List<MindsetQuestion> = mindsetQuestions

    const val DISCLAIMER =
        "Это самооценка игровых качеств, а не психологическая или медицинская диагностика. Результат меняется — проходи тест раз в месяц."
}

/** Headline of the profile: the leading trait in plain words (no nicknames or labels). */
data class MindsetArchetype(
    /** «Сильнее всего» / «Профиль». */
    val caption: String,
    val title: String,
    val summary: String,
    val icon: String,
    val tint: Tint
) {
    companion object {
        fun forTrait(trait: MindsetTrait?): MindsetArchetype {
            trait ?: return MindsetArchetype(
                "Профиль", "Ровный по всем чертам",
                "Ни одна черта заметно не выделяется — нет и явных провалов.",
                "circle.hexagongrid", Tint.LIGHT_BLUE
            )
            return MindsetArchetype("Сильнее всего", trait.title, leadingTraitSummary(trait), trait.icon, trait.tint)
        }
    }
}

/** Everything the profile screens show, computed from one result. */
data class MindsetProfile(val scores: Map<MindsetTrait, Int>) {
    private val ranked = MindsetTrait.entries.sortedWith(
        compareByDescending<MindsetTrait> { scores[it] ?: 0 }.thenBy { it.ordinal }
    )

    val archetype: MindsetArchetype
    val strengths: List<MindsetTrait>
    val growthZones: List<MindsetTrait>

    init {
        val values = ranked.map { scores[it] ?: 0 }
        val spread = (values.firstOrNull() ?: 0) - (values.lastOrNull() ?: 0)
        archetype = MindsetArchetype.forTrait(if (spread < MindsetMath.BALANCED_SPREAD) null else ranked.first())
        val strong = ranked.filter { (scores[it] ?: 0) >= MindsetMath.STRENGTH_THRESHOLD }
        strengths = (strong.ifEmpty { ranked.take(1) }).take(2)
        growthZones = ranked.reversed()
            .filter { (scores[it] ?: 0) < MindsetMath.GROWTH_THRESHOLD && it !in strengths }
            .take(2)
    }

    /** Values in `MindsetTrait.entries` order (radar chart). */
    val orderedScores: List<Int> get() = MindsetTrait.entries.map { scores[it] ?: 0 }
}

object MindsetMath {
    const val STRENGTH_THRESHOLD = 70
    const val GROWTH_THRESHOLD = 55
    /** Max − min below this means no single trait stands out. */
    const val BALANCED_SPREAD = 12

    /** 0…100 per trait: mean of 1…5 answers (reversed items flipped), rescaled. */
    fun scores(answers: Map<String, Int>, questions: List<MindsetQuestion> = MindsetTest.questions): Map<MindsetTrait, Int> {
        val result = linkedMapOf<MindsetTrait, Int>()
        for (trait in MindsetTrait.entries) {
            val values = questions.filter { it.trait == trait }.mapNotNull { question ->
                val raw = answers[question.id]?.takeIf { it in 1..5 } ?: return@mapNotNull null
                (if (question.reversed) 6 - raw else raw).toDouble()
            }
            val mean = StatisticsMath.mean(values) ?: continue
            result[trait] = ((mean - 1) / 4 * 100).roundToIntSwift()
        }
        return result
    }

    fun isComplete(answers: Map<String, Int>): Boolean = MindsetTest.questions.all { (answers[it.id] ?: 0) in 1..5 }

    /** Demo answers whose scores land within ~6 points of the target. */
    fun answers(target: Map<MindsetTrait, Int>): Map<String, Int> {
        val answers = linkedMapOf<String, Int>()
        for (trait in MindsetTrait.entries) {
            val items = MindsetTest.questions.filter { it.trait == trait }
            val score = (target[trait] ?: 50).toDouble()
            val total = ((score / 100 * 4 + 1) * items.size).roundToIntSwift().coerceIn(items.size, items.size * 5)
            items.forEachIndexed { index, question ->
                val raw = total / items.size + if (index < total % items.size) 1 else 0
                answers[question.id] = if (question.reversed) 6 - raw else raw
            }
        }
        return answers
    }
}

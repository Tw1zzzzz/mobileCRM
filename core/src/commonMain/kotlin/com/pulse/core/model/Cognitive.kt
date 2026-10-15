@file:UseSerializers(IsoInstantSerializer::class, UuidSerializer::class)

package com.pulse.core.model

import com.pulse.core.util.IsoInstantSerializer
import com.pulse.core.util.Tint
import com.pulse.core.util.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The five tests of the daily cognitive battery. */
@Serializable
enum class CognitiveTestKind(
    val title: String,
    val measures: String,
    val instructions: String,
    val target: String,
    val icon: String,
    val tint: Tint
) {
    @SerialName("reaction") REACTION(
        "Скорость реакции",
        "Простую зрительно-моторную реакцию — насколько быстро ты отвечаешь на сигнал.",
        "Жди, пока экран станет зелёным, и сразу нажимай. Не торопись — ранний тап не засчитывается. 5 попыток.",
        "Реакция", "bolt", Tint.ORANGE
    ),
    @SerialName("goNoGo") GO_NO_GO(
        "Go / No-Go",
        "Тормозной контроль и импульсивность: умение не реагировать на ложный сигнал.",
        "Появляются круги. Нажимай на зелёный, игнорируй красный. 20 сигналов.",
        "Самоконтроль", "hand.raised", Tint.GREEN
    ),
    @SerialName("stroop") STROOP(
        "Тест Струпа",
        "Когнитивную гибкость и концентрацию при конфликте информации.",
        "Слово написано цветом. Выбери ЦВЕТ ТЕКСТА, а не смысл слова. 12 заданий.",
        "Концентрация", "textformat", Tint.PURPLE
    ),
    @SerialName("memory") MEMORY(
        "Объём памяти",
        "Объём рабочей памяти — сколько элементов ты удерживаешь в голове.",
        "Запомни последовательность цифр и введи её. С каждым раундом длина растёт.",
        "Рабочая память", "brain.head.profile", Tint.CYAN
    ),
    @SerialName("aim") AIM(
        "Точность прицела",
        "Скорость и точность наведения — моторный контроль, как в игре.",
        "Нажимай на появляющиеся мишени как можно быстрее. 15 мишеней.",
        "Моторика", "scope", Tint.RED
    )
}

/** Outcome of a single test: normalized 0…100 score plus a human-readable raw metric. */
data class CognitiveOutcome(val score: Int, val metric: String)

@Serializable
data class CognitiveResult(val id: Uuid, val kind: CognitiveTestKind, val date: Instant, val score: Int, val metric: String)

/** Pre/post-training test results coming from notifications («Игровые тесты»). */
@Serializable
enum class GameTestPhase(val title: String) {
    @SerialName("beforeTraining") BEFORE_TRAINING("До тренировки"),
    @SerialName("afterTraining") AFTER_TRAINING("После тренировки")
}

@Serializable
data class GameTestResult(
    val id: Uuid,
    val date: Instant,
    val phase: GameTestPhase,
    val focus: Int,
    val fatigue: Int,
    val readiness: Int
)

object CognitiveScoring {
    /** 200 ms → 100, 500 ms → 0. */
    fun reactionScore(averageMs: Double): Int = clamp(((500 - averageMs) / 300 * 100).toInt())

    fun accuracyScore(correct: Int, total: Int): Int =
        if (total <= 0) 0 else clamp((correct.toDouble() / total * 100).toInt())

    /** Span 3 → 0, span 9 → 100. */
    fun memoryScore(span: Int): Int = clamp(((span - 3) / 6.0 * 100).toInt())

    /** Accuracy penalized by slow responses (target 450 ms). */
    fun speedAccuracyScore(correct: Int, total: Int, averageMs: Double): Int {
        val accuracy = accuracyScore(correct, total).toDouble()
        val speed = clamp(((1200 - averageMs) / 750 * 100).toInt()).toDouble()
        return clamp((accuracy * 0.7 + speed * 0.3).toInt())
    }

    fun clamp(value: Int): Int = value.coerceIn(0, 100)
}

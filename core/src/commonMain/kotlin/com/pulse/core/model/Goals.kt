package com.pulse.core.model

import com.pulse.core.util.Formatters
import kotlinx.serialization.Serializable
import com.pulse.core.util.roundToIntSwift
import kotlin.math.ceil

/** Daily activity targets (Профиль → Цели → Фитнес). */
@Serializable
data class FitnessGoals(val activeMinutes: Int = 60, val steps: Int = 10_000, val activeKcal: Int = 600)

/** Personal CS2 targets. Every goal is optional: the player sets only the ones they care about. */
@Serializable
data class GameGoals(
    val elo: Int? = null,
    /** ELO when the ELO goal was set — progress is measured from here, not from zero. */
    val eloStart: Int? = null,
    val kd: Double? = null,
    val adr: Double? = null,
    val winRate: Int? = null
) {
    val isEmpty: Boolean get() = elo == null && kd == null && adr == null && winRate == null

    companion object {
        /** Demo: a realistic target so the feature is visible on first launch. */
        val demo = GameGoals(elo = 3000, eloStart = 2750, kd = 1.1)

        /** K/D, ADR and win-rate are judged on recent form, not a single match. */
        const val RECENT_MATCHES = 20
    }
}

/** One row of goal progress, ready for display. */
data class GoalProgress(
    val metric: Metric,
    val title: String,
    val current: String,
    val target: String,
    /** 0…1 */
    val progress: Double,
    val isReached: Boolean,
    val note: String
) {
    enum class Metric { ELO, KD, ADR, WIN_RATE }
}

object GoalMath {
    fun progress(goals: GameGoals, elo: Int, recent: GameSummary): List<GoalProgress> {
        val rows = mutableListOf<GoalProgress>()
        goals.elo?.let { target ->
            val start = goals.eloStart ?: elo
            val reached = elo >= target
            val span = (target - start).toDouble()
            val share = if (reached) 1.0 else if (span > 0) (elo - start) / span else 0.0
            rows += GoalProgress(
                GoalProgress.Metric.ELO, "ELO", Formatters.grouped(elo), Formatters.grouped(target),
                clamp(share), reached,
                if (reached) "Цель достигнута 🎉" else "Осталось ${Formatters.grouped(target - elo)} ELO"
            )
        }
        goals.kd?.let { rows += ratio(GoalProgress.Metric.KD, "K/D", recent.kd, it, 2) }
        goals.adr?.let { rows += ratio(GoalProgress.Metric.ADR, "ADR", recent.adr, it, 1) }
        goals.winRate?.let { target ->
            val current = recent.winRate
            val reached = current >= target
            rows += GoalProgress(
                GoalProgress.Metric.WIN_RATE, "Победы", "${current.roundSwift()}%", "$target%",
                clamp(current / maxOf(target, 1)), reached,
                if (reached) "Держишь планку" else "Нужно ещё ${ceil(target - current).toInt()}%"
            )
        }
        return rows
    }

    private fun ratio(metric: GoalProgress.Metric, title: String, current: Double, target: Double, digits: Int): GoalProgress {
        val reached = current >= target
        return GoalProgress(
            metric, title,
            Formatters.decimal(current, digits),
            Formatters.decimal(target, digits),
            clamp(if (target > 0) current / target else 0.0), reached,
            if (reached) "Держишь планку" else "Не хватает ${Formatters.decimal(target - current, digits)}"
        )
    }

    private fun Double.roundSwift(): Int = roundToIntSwift()

    private fun clamp(value: Double): Double = value.coerceIn(0.0, 1.0)
}

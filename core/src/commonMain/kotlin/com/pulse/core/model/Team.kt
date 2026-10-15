package com.pulse.core.model

import com.pulse.core.util.Formatters
import com.pulse.core.util.StatisticsMath
import com.pulse.core.util.roundToIntSwift
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** CS2 game statistics shown to staff (раздел «Экран администратора»). */
data class GameStats(
    val kills: Int,
    val deaths: Int,
    val assists: Int,
    val adr: Double,
    val kpr: Double,
    val deathsPerRound: Double,
    val avgKR: Double,
    val avgKD: Double,
    val kast: Double,
    val firstKills: Int,
    val firstDeaths: Int,
    val udr: Double,
    val openDuelDiff: Int,
    val avgMultikills: Double,
    val clutchesWon: Int,
    val avgFlashTime: Double,
    val matches: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val rounds: Int,
    val roundsWon: Int,
    val pistolRounds: Int,
    val pistolWon: Int
) {
    val roundsLost: Int get() = rounds - roundsWon
    val winRate: Double get() = if (matches > 0) wins.toDouble() / matches * 100 else 0.0
    val roundWinRate: Double get() = if (rounds > 0) roundsWon.toDouble() / rounds * 100 else 0.0
    val pistolWinRate: Double get() = if (pistolRounds > 0) pistolWon.toDouble() / pistolRounds * 100 else 0.0
    val avgRoundsWon: Double get() = if (matches > 0) roundsWon.toDouble() / matches else 0.0
    val avgRoundsLost: Double get() = if (matches > 0) roundsLost.toDouble() / matches else 0.0
}

/** A player of the team as staff sees them (demo roster until the server exists). */
data class PlayerProfile(
    val id: Uuid,
    val nickname: String,
    val realName: String,
    val gameRole: String,
    val lastActive: Instant,
    val stats: GameStats,
    val elo: Int,
    val seed: ULong,
    /** Demo: daily state-index range ×10 (a player in a rough patch gets a lower range). */
    val readinessRange: IntRange = 50..85
) {
    override fun equals(other: Any?): Boolean = other is PlayerProfile && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

data class CorrelationRow(val factor: String, val metric: String, val value: Double)

/** One player's week for the staff summary. */
data class TeamWeeklyRow(
    val id: Uuid,
    val nickname: String,
    val readiness: Double?,
    val previousReadiness: Double?,
    val sleepMinutes: Int?,
    val previousSleepMinutes: Int?,
    val matches: Int,
    val winRate: Double?,
    val kd: Double?,
    val previousKD: Double?,
    val load: TrainingLoadMath.Ratio
) {
    val readinessDelta: Double? get() = if (readiness != null && previousReadiness != null) readiness - previousReadiness else null
    val sleepDelta: Int? get() = if (sleepMinutes != null && previousSleepMinutes != null) sleepMinutes - previousSleepMinutes else null
    val kdDelta: Double? get() = if (kd != null && previousKD != null) kd - previousKD else null

    /** Same thresholds as the rest of staff analytics: readiness < 6, sleep < 6 h 20 m, overload. */
    val risks: List<String>
        get() = buildList {
            if (readiness != null && readiness < 6) add("готовность ${Formatters.decimal(readiness)}")
            if (sleepMinutes != null && sleepMinutes < 380) add("сон ${Formatters.duration(sleepMinutes)}")
            if (load.status == LoadStatus.HIGH) add("перегруз (ACWR ${Formatters.decimal(load.value ?: 0.0, 2)})")
        }
}

data class TeamWeeklyReport(
    val week: WeekRange,
    val rows: List<TeamWeeklyRow>,
    val readiness: Double?,
    val readinessDelta: Double?,
    val sleepMinutes: Int?,
    val sleepDelta: Int?,
    val matches: Int,
    val winRate: Double?,
    val previousWinRate: Double?,
    val highlights: List<String>,
    val risks: List<String>,
    val tips: List<String>
)

object TeamWeeklyBuilder {
    fun build(rows: List<TeamWeeklyRow>, week: WeekRange, wins: Int, previousWins: Int, previousMatches: Int): TeamWeeklyReport {
        val readiness = StatisticsMath.mean(rows.mapNotNull { it.readiness })
        val previousReadiness = StatisticsMath.mean(rows.mapNotNull { it.previousReadiness })
        val sleep = StatisticsMath.mean(rows.mapNotNull { it.sleepMinutes?.toDouble() })
        val previousSleep = StatisticsMath.mean(rows.mapNotNull { it.previousSleepMinutes?.toDouble() })
        val matches = rows.sumOf { it.matches }

        val highlights = mutableListOf<String>()
        rows.filter { (it.readinessDelta ?: 0.0) > 0.2 }.maxByOrNull { it.readinessDelta ?: 0.0 }?.let { best ->
            highlights += "Сильнее всех вырос ${best.nickname}: готовность +${Formatters.decimal(best.readinessDelta!!)}"
        }
        rows.filter { it.matches >= 2 }.maxByOrNull { it.kd ?: 0.0 }?.let { shooter ->
            shooter.kd?.let { highlights += "Лучший K/D недели — ${shooter.nickname}: ${Formatters.decimal(it, 2)}" }
        }
        rows.filter { (it.sleepDelta ?: 0) >= 20 }.maxByOrNull { it.sleepDelta ?: 0 }?.let { sleeper ->
            highlights += "${sleeper.nickname} спит на ${Formatters.duration(sleeper.sleepDelta!!)} больше"
        }

        val risks = rows.filter { it.risks.isNotEmpty() }.map { "${it.nickname}: ${it.risks.joinToString(", ")}" }

        val tips = mutableListOf<String>()
        if (rows.any { it.load.status == LoadStatus.HIGH }) {
            tips += "У игроков с перегрузом снизьте объём тренировок на 20–30% в ближайшие дни."
        }
        if (sleep != null && sleep < 420) {
            tips += "Средний сон команды меньше 7 часов — перенесите поздние праки или введите отбой."
        }
        if (readiness != null && previousReadiness != null && readiness < previousReadiness - 0.3) {
            tips += "Готовность команды снизилась — проведите короткий разбор самочувствия на неделе."
        }
        if (tips.isEmpty()) tips += "Команда в хорошем ритме — сохраняйте текущую нагрузку и режим."

        return TeamWeeklyReport(
            week = week,
            rows = rows.sortedByDescending { it.readiness ?: 0.0 },
            readiness = readiness,
            readinessDelta = if (readiness != null && previousReadiness != null) readiness - previousReadiness else null,
            sleepMinutes = sleep?.roundToIntSwift(),
            sleepDelta = if (sleep != null && previousSleep != null) (sleep - previousSleep).roundToIntSwift() else null,
            matches = matches,
            winRate = if (matches > 0) wins.toDouble() / matches * 100 else null,
            previousWinRate = if (previousMatches > 0) previousWins.toDouble() / previousMatches * 100 else null,
            highlights = highlights.take(3),
            risks = risks,
            tips = tips
        )
    }
}

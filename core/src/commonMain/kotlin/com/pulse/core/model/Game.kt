package com.pulse.core.model

import com.pulse.core.util.Formatters
import com.pulse.core.util.StatisticsMath
import com.pulse.core.util.roundToIntSwift
import kotlin.math.abs
import kotlin.math.min
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** One CS2 match of the player (demo data until Faceit sync / manual entry). */
data class GameMatch(
    val id: Uuid,
    val date: Instant,
    val map: CSMap,
    val won: Boolean,
    val roundsWon: Int,
    val roundsLost: Int,
    val kills: Int,
    val deaths: Int,
    val assists: Int,
    val adr: Double,
    val eloDelta: Int,
    /** Extra per-match numbers available from Faceit (null for demo matches). */
    val details: Details? = null
) {
    data class Details(
        val faceitMatchId: String?,
        val mapName: String?,
        val headshotsPercent: Double?,
        val mvps: Int?,
        val firstKills: Int?,
        val entryCount: Int,
        val entryWins: Int,
        val clutchesWon: Int,
        val multiKills: Int,
        val utilityDamage: Int?,
        val enemiesFlashed: Int?,
        val sniperKills: Int?,
        val rounds: Int
    )

    val kd: Double get() = if (deaths > 0) kills.toDouble() / deaths else kills.toDouble()
    val score: String get() = "$roundsWon:$roundsLost"
}

/** Aggregates over a list of matches. */
data class GameSummary(val matches: Int, val wins: Int, val kd: Double, val adr: Double) {
    val winRate: Double get() = if (matches > 0) wins.toDouble() / matches * 100 else 0.0

    companion object {
        fun of(list: List<GameMatch>): GameSummary {
            val kills = list.sumOf { it.kills }
            val deaths = list.sumOf { it.deaths }
            return GameSummary(
                matches = list.size,
                wins = list.count { it.won },
                kd = if (deaths > 0) kills.toDouble() / deaths else 0.0,
                adr = StatisticsMath.mean(list.map { it.adr }) ?: 0.0
            )
        }
    }
}

/**
 * «Форма дня»: one 0–100 number from sleep, energy, mood, cognitive form and fatigue.
 * Missing inputs are skipped and the remaining weights are renormalized.
 */
object FormIndex {
    data class Component(val id: String, val title: String, /** 0…100 */ val value: Double)

    data class Result(val score: Int, val components: List<Component>) {
        val label: String
            get() = when {
                score >= 80 -> "Отличная форма"
                score >= 65 -> "Хорошая форма"
                score >= 50 -> "Средняя форма"
                else -> "Нужен отдых"
            }
    }

    const val TARGET_SLEEP_MINUTES = 480.0

    fun compute(sleepMinutes: Int?, energy: Double?, mood: Double?, cognitive: Double?, fatigue: Double?): Result? {
        data class Part(val id: String, val title: String, val value: Double?, val weight: Double)
        val parts = listOf(
            Part("sleep", "Сон", sleepMinutes?.let { min(it / TARGET_SLEEP_MINUTES, 1.0) * 100 }, 0.30),
            Part("energy", "Энергия", energy?.let { it * 10 }, 0.20),
            Part("cognitive", "Когн. форма", cognitive, 0.20),
            Part("mood", "Настроение", mood?.let { it * 10 }, 0.15),
            Part("fatigue", "Свежесть", fatigue?.let { (10 - it) * 10 }, 0.15)
        )
        val available = parts.mapNotNull { part -> part.value?.let { part.copy(value = it.coerceIn(0.0, 100.0)) } }
        val totalWeight = available.sumOf { it.weight }
        if (totalWeight <= 0) return null
        val score = available.sumOf { it.value!! * it.weight } / totalWeight
        return Result(score.roundToIntSwift(), available.map { Component(it.id, it.title, it.value!!) })
    }
}

/** One human-readable insight linking recovery and game results (no tables on the dashboard). */
object GameInsight {
    data class Result(val text: String, val isPositive: Boolean)

    /** Compares K/D on days after ≥7h of sleep vs shorter nights. */
    fun sleepVersusKD(matches: List<GameMatch>, sleepMinutes: (Instant) -> Int?): Result? {
        val rested = matches.filter { (sleepMinutes(it.date) ?: 0) >= 420 }
        val tired = matches.filter { match -> sleepMinutes(match.date)?.let { it < 420 } ?: false }
        if (rested.size < 2 || tired.size < 2) return null
        val restedKD = GameSummary.of(rested).kd
        val tiredKD = GameSummary.of(tired).kd
        val diff = restedKD - tiredKD
        if (abs(diff) < 0.05) {
            return Result("Пока сон заметно не влияет на твой K/D — продолжай отмечать данные", true)
        }
        return Result(
            "После сна 7+ часов твой K/D ${Formatters.decimal(restedKD, 2)} против ${Formatters.decimal(tiredKD, 2)} после короткой ночи",
            diff > 0
        )
    }
}

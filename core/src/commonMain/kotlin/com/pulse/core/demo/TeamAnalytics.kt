package com.pulse.core.demo

import com.pulse.core.model.CSMap
import com.pulse.core.model.CorrelationRow
import com.pulse.core.model.DailyValue
import com.pulse.core.model.GameMatch
import com.pulse.core.model.GameStats
import com.pulse.core.model.GameSummary
import com.pulse.core.model.Meal
import com.pulse.core.model.PlayerProfile
import com.pulse.core.model.TeamWeeklyBuilder
import com.pulse.core.model.TeamWeeklyReport
import com.pulse.core.model.TeamWeeklyRow
import com.pulse.core.model.TrainingLoadMath
import com.pulse.core.model.User
import com.pulse.core.model.WeekRange
import com.pulse.core.util.Formatters
import com.pulse.core.util.SeededGenerator
import com.pulse.core.util.StatisticsMath
import com.pulse.core.util.addingDays
import com.pulse.core.util.isSameDay
import com.pulse.core.util.roundToIntSwift
import com.pulse.core.util.setting
import com.pulse.core.util.startOfDay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Aggregated per-player metrics for staff screens (every staff screen reads from here). */
data class PlayerSnapshot(
    val player: PlayerProfile,
    val readiness: Double,
    val averageScore: Int,
    val sleepMinutes: Int,
    val fatigueHighDays: Int,
    val focusHighDays: Int
) {
    /** Needs attention: low readiness, short sleep or frequent high fatigue. */
    val needsAttention: Boolean
        get() = readiness < 6.0 || sleepMinutes < 380 || fatigueHighDays >= TeamAnalytics.FATIGUE_ALERT_DAYS

    val attentionReason: String
        get() = when {
            sleepMinutes < 380 -> "Мало сна: ${Formatters.duration(sleepMinutes)}"
            fatigueHighDays >= TeamAnalytics.FATIGUE_ALERT_DAYS -> "Усталость high $fatigueHighDays дн."
            else -> "Индекс состояния ${Formatters.decimal(readiness)}"
        }
}

/** Non-game factors and game metrics used by the correlation analysis. */
enum class WellbeingFactor(val title: String) {
    SLEEP("Сон"), MOOD("Настроение"), COGNITIVE("Когн. форма"), SCREEN("Экран. время"), NUTRITION("Питание")
}

enum class GameMetric(val title: String) {
    KD("K/D"), ADR("ADR"), WIN_RATE("Win-rate");

    fun value(match: GameMatch): Double = when (this) {
        KD -> match.kd
        ADR -> match.adr
        WIN_RATE -> if (match.won) 100.0 else 0.0
    }
}

data class ScatterPoint(val player: String, val sleepHours: Double, val kd: Double, val won: Boolean)

object TeamAnalytics {
    const val FATIGUE_ALERT_DAYS = 5

    fun sleepMinutes(player: PlayerProfile, day: Instant): Int? {
        val sessions = DemoData.sleepSessions(player).filter { it.end.isSameDay(day) }
        return if (sessions.isEmpty()) null else sessions.sumOf { it.durationMinutes }
    }

    /** Cognitive battery score 0…100 per day. */
    fun scores(player: PlayerProfile, days: Int, now: Instant = Clock.System.now()): List<DailyValue> =
        DemoData.playerSeries(player, days, 55..92, 1uL, now)

    /** State index 0…10 per day (readiness from game tests and mood). */
    fun readiness(player: PlayerProfile, days: Int, now: Instant = Clock.System.now()): List<DailyValue> =
        DemoData.playerSeries(player, days, player.readinessRange, 4uL, now).map { DailyValue(it.date, it.value / 10) }

    fun factorValue(factor: WellbeingFactor, player: PlayerProfile, day: Instant): Double? = when (factor) {
        WellbeingFactor.SLEEP -> sleepMinutes(player, day)?.let { it / 60.0 }
        WellbeingFactor.COGNITIVE -> scores(player, 30).firstOrNull { it.date.isSameDay(day) }?.value
        WellbeingFactor.MOOD -> series(player, 21uL, 4..9).firstOrNull { it.date.isSameDay(day) }?.value
        WellbeingFactor.SCREEN -> series(player, 31uL, 180..360).firstOrNull { it.date.isSameDay(day) }?.let { it.value / 60 }
        WellbeingFactor.NUTRITION -> series(player, 41uL, 1800..3000).firstOrNull { it.date.isSameDay(day) }?.value
    }

    private fun series(player: PlayerProfile, salt: ULong, range: IntRange) = DemoData.playerSeries(player, 30, range, salt)

    fun snapshot(player: PlayerProfile, days: Int = 7): PlayerSnapshot {
        val today = Clock.System.now().startOfDay()
        val sleep = (0 until days).mapNotNull { sleepMinutes(player, today.addingDays(-it)) }
        val scoreValues = scores(player, days).map { it.value }
        val fatigue = DemoData.playerSeries(player, days, 1..10, 2uL).count { it.value >= 8 }
        return PlayerSnapshot(
            player = player,
            readiness = StatisticsMath.mean(readiness(player, days).map { it.value }) ?: 0.0,
            averageScore = (StatisticsMath.mean(scoreValues) ?: 0.0).toInt(),
            sleepMinutes = (StatisticsMath.mean(sleep.map { it.toDouble() }) ?: 0.0).toInt(),
            fatigueHighDays = fatigue,
            focusHighDays = scoreValues.count { it >= 80 }
        )
    }

    fun snapshots(days: Int = 7): List<PlayerSnapshot> = DemoData.players.map { snapshot(it, days) }

    data class TrendPoint(val date: Instant, val average: Double, val low: Double, val high: Double)

    /** Team average readiness per day with min/max across players. */
    fun teamReadinessTrend(days: Int): List<TrendPoint> {
        val perPlayer = DemoData.players.map { readiness(it, days) }
        return (0 until days).map { index ->
            val values = perPlayer.mapNotNull { it.getOrNull(index)?.value }
            TrendPoint(perPlayer.firstOrNull()?.get(index)?.date ?: Clock.System.now(),
                StatisticsMath.mean(values) ?: 0.0, values.minOrNull() ?: 0.0, values.maxOrNull() ?: 0.0)
        }
    }

    // Correlations (Pearson over matched days)

    private fun pairs(factor: WellbeingFactor, metric: GameMetric, player: PlayerProfile): List<Pair<Double, Double>> =
        GameData.matches(player).mapNotNull { match -> factorValue(factor, player, match.date)?.let { it to metric.value(match) } }

    fun correlation(factor: WellbeingFactor, metric: GameMetric, players: List<PlayerProfile>): Double? {
        val all = players.flatMap { pairs(factor, metric, it) }
        return StatisticsMath.pearson(all.map { it.first }, all.map { it.second })
    }

    /** Factor × metric matrix for one player or the whole team (pooled samples). */
    fun matrix(players: List<PlayerProfile>): List<List<Double?>> =
        WellbeingFactor.entries.map { factor -> GameMetric.entries.map { correlation(factor, it, players) } }

    fun correlationRows(players: List<PlayerProfile>): List<CorrelationRow> = WellbeingFactor.entries.flatMap { factor ->
        GameMetric.entries.mapNotNull { metric ->
            correlation(factor, metric, players)?.let { CorrelationRow(factor.title, metric.title, it) }
        }
    }.sortedByDescending { abs(it.value) }

    fun correlations(player: PlayerProfile): List<CorrelationRow> = correlationRows(listOf(player)).take(6)

    fun teamCorrelations(): List<CorrelationRow> = correlationRows(DemoData.players).take(6)

    fun sleepVersusKD(players: List<PlayerProfile>): List<ScatterPoint> = players.flatMap { player ->
        GameData.matches(player).mapNotNull { match ->
            sleepMinutes(player, match.date)?.let { ScatterPoint(player.nickname, it / 60.0, match.kd, match.won) }
        }
    }

    /** Least-squares line for the scatter plot: (slope, intercept). */
    fun trendLine(points: List<ScatterPoint>): Pair<Double, Double>? {
        if (points.size < 3) return null
        val meanX = StatisticsMath.mean(points.map { it.sleepHours }) ?: return null
        val meanY = StatisticsMath.mean(points.map { it.kd }) ?: return null
        val numerator = points.sumOf { (it.sleepHours - meanX) * (it.kd - meanY) }
        val denominator = points.sumOf { (it.sleepHours - meanX).pow(2) }
        if (denominator <= 0) return null
        val slope = numerator / denominator
        return slope to meanY - slope * meanX
    }

    /** Demo training load of a team player (28 days, oldest first); players in a rough patch get a recent spike. */
    fun dailyLoad(player: PlayerProfile, now: Instant = Clock.System.now()): List<DailyValue> {
        val rng = SeededGenerator(player.seed + 404uL)
        val spike = player.readinessRange.last < 70
        return (TrainingLoadMath.CHRONIC_DAYS - 1 downTo 0).map { offset ->
            val day = now.startOfDay().addingDays(-offset)
            val trains = rng.int(0..9) < 6
            val base = if (trains) (rng.int(4..7) * rng.int(35..60)).toDouble() else 0.0
            DailyValue(day, if (offset < 7 && spike && trains) base * 1.8 else base)
        }
    }
}

/** Player-side game data (demo). Matches are generated per player so ELO, K/D and win rate stay consistent. */
object GameData {
    fun profile(user: User?): PlayerProfile = DemoData.players.firstOrNull { it.nickname == user?.nickname } ?: DemoData.players[0]

    /** ~1–2 matches per day over the last `days` days, newest first. */
    fun matches(player: PlayerProfile, days: Int = 30, now: Instant = Clock.System.now()): List<GameMatch> {
        val rng = SeededGenerator(player.seed + 777uL)
        val maps = CSMap.entries
        // Demo only: matches after a short night (<7h) are slightly worse, so analytics show a plausible pattern.
        val shortNights = DemoData.sleepSessions(player, now).filter { it.durationMinutes < 420 }.map { it.end.startOfDay() }.toSet()
        return (0 until days).flatMap { offset ->
            val day = now.startOfDay().addingDays(-offset)
            val count = rng.int(0..2)
            (0 until count).mapNotNull { index ->
                val date = day.setting(16 + index * 3, rng.int(0..50))
                if (date > now) return@mapNotNull null
                val tired = day in shortNights
                val kills = max(rng.int(9..28) - (if (tired) 3 else 0), 4)
                val deaths = rng.int(10..21) + (if (tired) 1 else 0)
                val won = kills.toDouble() / deaths + rng.double(-0.35..0.35) >= 1.0
                val lost = rng.int(3..11)
                val map = maps[rng.int(0..maps.size - 1)]
                val assists = rng.int(2..8)
                val adr = rng.double(60.0..104.0) - (if (tired) 6 else 0)
                val eloDelta = if (won) rng.int(18..30) else -rng.int(18..30)
                GameMatch(
                    Uuid.random(), date, map, won,
                    roundsWon = if (won) 13 else lost, roundsLost = if (won) lost else 13,
                    kills = kills, deaths = deaths, assists = assists, adr = adr, eloDelta = eloDelta
                )
            }
        }.sortedByDescending { it.date }
    }

    /** End-of-day ELO for the last `days` days, ending at the player's current ELO. */
    fun eloHistory(player: PlayerProfile, days: Int = 14, now: Instant = Clock.System.now()): List<DailyValue> {
        val list = matches(player, max(days, 30), now)
        return (days - 1 downTo 0).map { offset ->
            val day = now.startOfDay().addingDays(-offset)
            val later = list.filter { it.date > day.addingDays(1) }.sumOf { it.eloDelta }
            DailyValue(day, (player.elo - later).toDouble())
        }
    }

    /** Daily average of a match metric (only days with matches), oldest first. */
    fun daily(metric: GameMetric, player: PlayerProfile, days: Int, now: Instant = Clock.System.now()): List<DailyValue> {
        val list = matches(player, days, now)
        return (days - 1 downTo 0).mapNotNull { offset ->
            val day = now.startOfDay().addingDays(-offset)
            StatisticsMath.mean(list.filter { it.date.isSameDay(day) }.map { metric.value(it) })?.let { DailyValue(day, it) }
        }
    }

    /** Full CS2 stats: base numbers come from the same matches as the charts; advanced metrics stay demo values. */
    fun stats(player: PlayerProfile, days: Int = 30, now: Instant = Clock.System.now()): GameStats {
        val list = matches(player, days, now)
        val base = player.stats
        val kills = list.sumOf { it.kills }
        val deaths = list.sumOf { it.deaths }
        val rounds = max(list.sumOf { it.roundsWon + it.roundsLost }, 1)
        val wins = list.count { it.won }
        val pistol = list.size * 2
        val pistolRate = if (base.pistolRounds > 0) base.pistolWon.toDouble() / base.pistolRounds else 0.5
        return GameStats(
            kills = kills, deaths = deaths, assists = list.sumOf { it.assists },
            adr = StatisticsMath.mean(list.map { it.adr }) ?: 0.0,
            kpr = kills.toDouble() / rounds, deathsPerRound = deaths.toDouble() / rounds,
            avgKR = kills.toDouble() / rounds, avgKD = if (deaths > 0) kills.toDouble() / deaths else 0.0,
            kast = base.kast, firstKills = base.firstKills, firstDeaths = base.firstDeaths, udr = base.udr,
            openDuelDiff = base.openDuelDiff, avgMultikills = base.avgMultikills, clutchesWon = base.clutchesWon,
            avgFlashTime = base.avgFlashTime, matches = list.size, wins = wins, losses = list.size - wins, draws = 0,
            rounds = rounds, roundsWon = list.sumOf { it.roundsWon },
            pistolRounds = pistol, pistolWon = (pistol * pistolRate).roundToIntSwift()
        )
    }
}

/** «Питание игроков» для стаффа: приёмы пищи с фото, которые игроки логируют в приложении. */
object TeamFood {
    data class Entry(val meal: Meal, val player: String)

    /** Device meals belong to their author (legacy meals → first demo player); players without real entries get demo meals. */
    fun entries(deviceMeals: List<Meal>, days: Int = 7, now: Instant = Clock.System.now()): List<Entry> {
        val from = now.startOfDay().addingDays(-days)
        val defaultAuthor = DemoData.players[0].nickname
        val roster = DemoData.players.map { it.nickname }.toSet()
        // Only team players' meals reach staff; a solo player's food diary stays private.
        val real = deviceMeals.map { Entry(it, it.author ?: defaultAuthor) }.filter { it.player in roster }
        val withRealData = real.map { it.player }.toSet()
        val demo = DemoData.players.filter { it.nickname !in withRealData }
            .flatMap { player -> DemoData.playerMeals(player, now).map { Entry(it, player.nickname) } }
        return (real + demo).filter { it.meal.date >= from && it.meal.date <= now }.sortedByDescending { it.meal.date }
    }
}

/** Demo team week (no server yet): rows from the same demo series as the rest of staff analytics. */
fun TeamWeeklyBuilder.demo(week: WeekRange, now: Instant = Clock.System.now()): TeamWeeklyReport {
    val previous = week.previous
    var wins = 0
    var previousWins = 0
    var previousMatches = 0
    val rows = DemoData.players.map { player ->
        val readiness = TeamAnalytics.readiness(player, 30, now)
        val sleep = DemoData.sleepSessions(player, now)
        val matches = GameData.matches(player, 30, now)
        val thisWeek = matches.filter { week.contains(it.date) }
        val lastWeek = matches.filter { previous.contains(it.date) }
        wins += thisWeek.count { it.won }
        previousWins += lastWeek.count { it.won }
        previousMatches += lastWeek.size
        val load = TeamAnalytics.dailyLoad(player, minOf(now, week.end.addingDays(-1)))
        TeamWeeklyRow(
            id = player.id,
            nickname = player.nickname,
            readiness = StatisticsMath.mean(readiness.filter { week.contains(it.date) }.map { it.value }),
            previousReadiness = StatisticsMath.mean(readiness.filter { previous.contains(it.date) }.map { it.value }),
            sleepMinutes = StatisticsMath.mean(sleep.filter { week.contains(it.end) }.map { it.durationMinutes.toDouble() })?.roundToIntSwift(),
            previousSleepMinutes = StatisticsMath.mean(sleep.filter { previous.contains(it.end) }.map { it.durationMinutes.toDouble() })?.roundToIntSwift(),
            matches = thisWeek.size,
            winRate = if (thisWeek.isEmpty()) null else GameSummary.of(thisWeek).winRate,
            kd = if (thisWeek.isEmpty()) null else GameSummary.of(thisWeek).kd,
            previousKD = if (lastWeek.isEmpty()) null else GameSummary.of(lastWeek).kd,
            load = TrainingLoadMath.ratio(load)
        )
    }
    return build(rows, week, wins, previousWins, previousMatches)
}

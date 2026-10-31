package com.pulse.core.feature

import com.pulse.core.feature.StaffMetrics.dayOf
import com.pulse.core.feature.StaffMetrics.number
import com.pulse.core.feature.StaffMetrics.parseInstant
import com.pulse.core.feature.StaffMetrics.phoneDayOf
import com.pulse.core.feature.StaffMetrics.records
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A value per day (`YYYY-MM-DD`, oldest first); an absent key means no data that day. */
typealias DaySeries = Map<String, Double>

/**
 * Per-day series of one player: what the staff card's charts and the statistics page draw.
 *
 * Pure (no stores, no I/O). The TypeScript twin is `server/lib/staff/series.ts`; both are checked against
 * `server/test/fixtures/staff-series.json`. Sleep, mood, energy, cognitive index and kilocalories come from
 * [StaffMetrics]'s `indexDocuments`/`inputsFromIndex` (so they equal `dailyInputs`, and the readiness series
 * equals `readiness(dailyInputs(...))`); screen time, the balance wheel, focus/fatigue and ELO are read here.
 *
 * Days: mood, energy, the balance wheel and screen time are PHONE days ([phoneDayOf]); sleep, cognitive tests,
 * meals and ELO snapshots are real instants, so they fall on the calendar day in the staff zone `tz`.
 * A day without a record has no key: a gap is never a zero.
 */
object StaffSeries {
    /** `BalanceCategory` keys of the app, in its declaration order. */
    private val WHEEL_KEYS = listOf(
        "physical", "emotional", "intellectual", "spiritual", "professional", "social", "environment", "financial",
    )

    /** `BalanceEntry.value`: a key missing from an entry counts as this. */
    private const val WHEEL_DEFAULT = 5.0
    private const val MINUTES_PER_HOUR = 60.0
    private const val MINUTES_PER_DAY = 1440.0

    @Serializable
    data class PlayerSeries(
        /** Hours of sleep sessions (at least 60 minutes) that end that day, summed. */
        val sleep: DaySeries,
        /** Day mean of the 1..10 mood entries. */
        val mood: DaySeries,
        val energy: DaySeries,
        /** Day mean of `cognitive.results[].score` (0..100). */
        val cog: DaySeries,
        /** Day sum of the meals' kilocalories. */
        val kcal: DaySeries,
        /** Hours: `screen-time.entries[].minutes / 60`. */
        val screen: DaySeries,
        /** Mean of the 8 wheel values; a missing key counts 5.0, as the app does. */
        val balance: DaySeries,
        /** Day mean of `cognitive.gameResults[].focus` (1..10). */
        val focus: DaySeries,
        val fatigue: DaySeries,
        /** `elo` of the latest `faceit.snapshots[]` entry of the day. */
        val elo: DaySeries,
        /** Plan A's `readiness(dailyInputs(...))` for every day that has a readiness input. */
        val readiness: DaySeries,
    )

    @Serializable
    data class Period(
        val from: String,
        val to: String,
        /** Oldest first, ending at [to]. */
        val days: List<String>,
    )

    /** [days] days ending at [to] (inclusive): `from = to − (days − 1)`. */
    fun periodDays(to: String, days: Int): Period {
        require(days >= 1) { "A period needs a whole number of days, got $days" }
        val list = StaffMetrics.daysEndingAt(to, days)
        return Period(from = list.first(), to = to, days = list)
    }

    private class ExtraIndex(
        val screenHours: MutableMap<String, Double> = LinkedHashMap(),
        val balance: MutableMap<String, Double> = LinkedHashMap(),
        val focus: MutableMap<String, MutableList<Double>> = LinkedHashMap(),
        val fatigue: MutableMap<String, MutableList<Double>> = LinkedHashMap(),
        val elo: MutableMap<String, Double> = LinkedHashMap(),
    )

    private fun MutableMap<String, MutableList<Double>>.push(day: String, value: Double) {
        getOrPut(day) { mutableListOf() } += value
    }

    /** Reads what `indexDocuments` does not cover; malformed records are skipped, the last record of a day wins. */
    private fun indexExtras(docs: Map<String, JsonElement>, tz: String): ExtraIndex {
        val extra = ExtraIndex()

        for (e in records(docs["screen-time"], "entries")) {
            val at = parseInstant(e["date"]) ?: continue
            val minutes = number(e["minutes"], 0.0, MINUTES_PER_DAY) ?: continue
            extra.screenHours[phoneDayOf(at)] = minutes / MINUTES_PER_HOUR
        }
        for (e in records(docs["wellbeing"], "balance")) {
            val at = parseInstant(e["day"]) ?: continue
            val values = e["values"] as? JsonObject ?: continue
            var sum = 0.0
            for (key in WHEEL_KEYS) sum += number(values[key], 0.0, 10.0) ?: WHEEL_DEFAULT
            extra.balance[phoneDayOf(at)] = sum / WHEEL_KEYS.size
        }
        for (g in records(docs["cognitive"], "gameResults")) {
            val at = parseInstant(g["date"]) ?: continue
            val day = dayOf(at, tz)
            number(g["focus"], 1.0, 10.0)?.let { extra.focus.push(day, it) }
            number(g["fatigue"], 1.0, 10.0)?.let { extra.fatigue.push(day, it) }
        }
        val latest = HashMap<String, Long>() // instant (ms) of the snapshot now held for the day
        for (s in records(docs["faceit"], "snapshots")) {
            val at = parseInstant(s["date"]) ?: continue
            val elo = number(s["elo"]) ?: continue
            val day = dayOf(at, tz)
            val ms = at.toEpochMilliseconds()
            val held = latest[day]
            if (held == null || ms >= held) {
                latest[day] = ms
                extra.elo[day] = elo
            }
        }
        return extra
    }

    /**
     * The series of one player for [from]..[to] inclusive, only days with data. [from] after [to] gives empty
     * series; a malformed day throws.
     */
    fun playerSeries(player: StaffMetrics.PlayerDocuments, from: String, to: String, tz: String): PlayerSeries {
        StaffMetrics.shiftDay(from, 0) // validates both ends
        StaffMetrics.shiftDay(to, 0)
        val index = StaffMetrics.indexDocuments(player.docs, tz)
        val extra = indexExtras(player.docs, tz)

        val sleep = LinkedHashMap<String, Double>()
        val mood = LinkedHashMap<String, Double>()
        val energy = LinkedHashMap<String, Double>()
        val cog = LinkedHashMap<String, Double>()
        val kcal = LinkedHashMap<String, Double>()
        val screen = LinkedHashMap<String, Double>()
        val balance = LinkedHashMap<String, Double>()
        val focus = LinkedHashMap<String, Double>()
        val fatigue = LinkedHashMap<String, Double>()
        val elo = LinkedHashMap<String, Double>()
        val readiness = LinkedHashMap<String, Double>()

        var day = from
        while (day <= to) {
            val inputs = StaffMetrics.inputsFromIndex(index, day)
            inputs.sleepHours?.let { sleep[day] = it }
            inputs.mood?.let { mood[day] = it }
            inputs.energy?.let { energy[day] = it }
            inputs.cog?.let { cog[day] = it }
            inputs.kcal?.let { kcal[day] = it }

            extra.screenHours[day]?.let { screen[day] = it }
            extra.balance[day]?.let { balance[day] = it }
            extra.focus[day]?.let { focus[day] = StaffMetrics.mean(it)!! }
            extra.fatigue[day]?.let { fatigue[day] = StaffMetrics.mean(it)!! }
            extra.elo[day]?.let { elo[day] = it }
            StaffMetrics.readiness(inputs)?.let { readiness[day] = it.toDouble() }

            day = StaffMetrics.shiftDay(day, 1)
        }
        return PlayerSeries(sleep, mood, energy, cog, kcal, screen, balance, focus, fatigue, elo, readiness)
    }
}

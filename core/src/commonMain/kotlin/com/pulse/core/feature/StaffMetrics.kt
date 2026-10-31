package com.pulse.core.feature

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Staff metrics: the numbers of the staff panel's «Обзор», computed from a player's synced documents.
 *
 * Pure (no stores, no I/O). The TypeScript twin is `server/lib/staff/metrics.ts`; both are checked against
 * `server/test/fixtures/staff-metrics.json`, so a metric has the same value in the panel and in the staff app.
 * Thresholds, mood categories and the readiness formula are the design boards' (`TeamOverview.dc.html`,
 * `Players.dc.html`).
 *
 * Rounding, identical in both languages: `roundHalfUp(x) = floor(x + 0.5)` (never `kotlin.math.round`, which is
 * half-even) and `round2(x) = roundHalfUp(x * 100) / 100`.
 */
object StaffMetrics {
    /** The beta's one time zone; days are calendar days in it. */
    const val STAFF_TIME_ZONE = "Europe/Moscow"

    const val WINDOW_DAYS = 7

    /** A sleep session shorter than this is «Неполная» in the app and is not counted as sleep. */
    private const val MIN_SLEEP_MS = 60L * 60 * 1000
    private const val HOUR_MS = 60.0 * 60 * 1000
    private const val READINESS_FULL_MAX = 115.0

    data class Threshold(val ok: Double, val mid: Double)

    /** Board `pill(...)` calls and every `metric(...)` call of `TeamOverview.dc.html`. */
    val PILL_THRESHOLDS: Map<String, Threshold> = mapOf(
        "mood" to Threshold(6.8, 5.2),
        "sleep" to Threshold(7.3, 6.6),
        "readiness" to Threshold(70.0, 55.0),
        "cognitive" to Threshold(66.0, 56.0),
        "nutrition" to Threshold(2100.0, 1700.0),
        // Game metrics are shown from plan C; kept so the board's numbers live in one place.
        "kd" to Threshold(1.05, 0.95),
        "winRate" to Threshold(55.0, 45.0),
    )

    // ------------------------------------------------------------ types

    /** One player's stored documents by collection name (`sleep`, `wellbeing`, `cognitive`, `nutrition`). */
    class PlayerDocuments(val id: String, val docs: Map<String, JsonElement>)

    @Serializable
    data class DailyInputs(
        val sleepHours: Double? = null,
        val mood: Double? = null,
        val energy: Double? = null,
        /** Cognitive index 0..100: average score of the day's tests (`CognitiveScoring` already scores 0..100). */
        val cog: Double? = null,
        val kcal: Double? = null,
    )

    @Serializable
    enum class Pill {
        @SerialName("good") GOOD,
        @SerialName("mid") MID,
        @SerialName("bad") BAD,
    }

    @Serializable
    enum class ReadinessStatus {
        @SerialName("ready") READY,
        @SerialName("doubt") DOUBT,
        @SerialName("risk") RISK,
    }

    @Serializable
    enum class Direction {
        @SerialName("up") UP,
        @SerialName("down") DOWN,
    }

    @Serializable
    enum class MetricKey {
        @SerialName("readiness") READINESS,
        @SerialName("cognitive") COGNITIVE,
        @SerialName("nutrition") NUTRITION,
    }

    @Serializable
    data class MoodCategory(val name: String, val range: String, val count: Int)

    @Serializable
    data class MoodCategoryShare(val name: String, val range: String, val count: Int, val percent: Int)

    @Serializable
    data class WeeklyMetric(
        val key: MetricKey,
        val value: Double?,
        val previous: Double?,
        val delta: Double?,
        val direction: Direction?,
        val pill: Pill?,
        /** One value per day of the window, null where nobody has data. */
        val series: List<Double?>,
    )

    @Serializable
    data class PlayerReadiness(val id: String, val readiness: Int?, val status: ReadinessStatus?)

    @Serializable
    data class MoodOverview(val count: Int, val average: Double?, val pill: Pill?, val categories: List<MoodCategoryShare>)

    @Serializable
    data class LowestSleep(val day: String, val hours: Double)

    @Serializable
    data class SleepOverview(val daily: List<Double?>, val average: Double?, val pill: Pill?, val lowest: LowestSleep?)

    @Serializable
    data class EnergyOverview(val daily: List<Double?>, val today: Double?, val trend: Direction?)

    @Serializable
    data class OverviewData(
        val who: String,
        val today: String,
        /** The 7 days of the window, oldest first, ending today. */
        val days: List<String>,
        /** Whole team, today; the player filter does not change it. */
        val teamReadiness: Int?,
        val teamReadinessStatus: ReadinessStatus?,
        val playerReadiness: List<PlayerReadiness>,
        val mood: MoodOverview,
        val sleep: SleepOverview,
        val energy: EnergyOverview,
        val metrics: List<WeeklyMetric>,
    )

    // ------------------------------------------------------------ numbers

    private fun roundHalfUp(x: Double): Double = floor(x + 0.5)

    private fun round2(x: Double): Double = roundHalfUp(x * 100) / 100

    private fun round2OrNull(x: Double?): Double? = x?.let(::round2)

    private fun mean(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        var sum = 0.0
        for (v in values) sum += v
        return sum / values.size
    }

    private fun meanOfKnown(values: List<Double?>): Double? = mean(values.filterNotNull())

    // ------------------------------------------------------------ readiness, categories, pills

    /**
     * Readiness 0..100 (owner's formula): `(sleep − 4.5) / 4 × 30 + mood × 3 + energy × 2.5 + cog × 0.3`. With
     * inputs missing, the sum of the available terms is scaled by `115 / (sum of their maxima)` (sleep 30,
     * mood 30, energy 25, cog 30); nothing available -> null.
     */
    fun readiness(inputs: DailyInputs): Int? {
        val terms = mutableListOf<Pair<Double, Double>>() // term to its maximum
        inputs.sleepHours?.takeIf(Double::isFinite)?.let { terms += ((it - 4.5) / 4) * 30 to 30.0 }
        inputs.mood?.takeIf(Double::isFinite)?.let { terms += it * 3 to 30.0 }
        inputs.energy?.takeIf(Double::isFinite)?.let { terms += it * 2.5 to 25.0 }
        inputs.cog?.takeIf(Double::isFinite)?.let { terms += it * 0.3 to 30.0 }
        if (terms.isEmpty()) return null

        var sum = 0.0
        var max = 0.0
        for ((term, termMax) in terms) {
            sum += term
            max += termMax
        }
        val scaled = if (max == READINESS_FULL_MAX) sum else sum * READINESS_FULL_MAX / max
        return roundHalfUp(minOf(100.0, maxOf(0.0, scaled))).toInt()
    }

    fun readinessStatus(value: Double): ReadinessStatus {
        val t = PILL_THRESHOLDS.getValue("readiness")
        return when {
            value >= t.ok -> ReadinessStatus.READY
            value >= t.mid -> ReadinessStatus.DOUBT
            else -> ReadinessStatus.RISK
        }
    }

    fun pill(value: Double, ok: Double, mid: Double): Pill = when {
        value >= ok -> Pill.GOOD
        value >= mid -> Pill.MID
        else -> Pill.BAD
    }

    private class MoodBand(val name: String, val range: String, val has: (Double) -> Boolean)

    private val MOOD_BANDS = listOf(
        MoodBand("Отличное", "8–10") { it >= 7.5 },
        MoodBand("Хорошее", "6–7") { it >= 5.5 && it < 7.5 },
        MoodBand("Среднее", "4–5") { it >= 3.5 && it < 5.5 },
        MoodBand("Плохое", "1–3") { it < 3.5 },
    )

    fun moodCategories(entries: List<Double>): List<MoodCategory> =
        MOOD_BANDS.map { band -> MoodCategory(band.name, band.range, entries.count(band.has)) }

    // ------------------------------------------------------------ days

    private val ISO_INSTANT = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$""")

    /** The calendar day (`YYYY-MM-DD`) of an instant in the given IANA zone. */
    fun dayOf(instant: Instant, tz: String): String = instant.toLocalDateTime(TimeZone.of(tz)).date.toString()

    /**
     * The calendar day of a mood entry's `day` instant. The app stores it as midnight in the PHONE's zone, which the
     * server does not know; the UTC date of (instant + 12 hours) recovers that day for every phone zone from UTC-11
     * to UTC+12, whatever zone the staff read it in.
     */
    fun phoneDayOf(instant: Instant): String = (instant + 12.hours).toLocalDateTime(TimeZone.UTC).date.toString()

    /** Shifts a `YYYY-MM-DD` day by whole calendar days. */
    fun shiftDay(day: String, delta: Int): String = LocalDate.parse(day).plus(delta, DateTimeUnit.DAY).toString()

    private fun daysEndingAt(end: String, count: Int): List<String> = List(count) { shiftDay(end, it - (count - 1)) }

    private fun parseInstant(value: JsonElement?): Instant? {
        val text = string(value) ?: return null
        if (!ISO_INSTANT.matches(text)) return null
        return try {
            Instant.parse(text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // ------------------------------------------------------------ documents -> per-day values

    private fun number(value: JsonElement?): Double? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive is JsonNull || primitive.isString) return null
        return primitive.doubleOrNull?.takeIf(Double::isFinite)
    }

    private fun number(value: JsonElement?, min: Double, max: Double): Double? = number(value)?.takeIf { it in min..max }

    private fun string(value: JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    /** The object entries of `doc[key]`; anything else (wrong shape, wrong type) yields nothing. */
    private fun records(doc: JsonElement?, key: String): List<JsonObject> =
        ((doc as? JsonObject)?.get(key) as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()

    private class DayIndex(
        val sleepMs: MutableMap<String, Long> = LinkedHashMap(),
        val mood: MutableMap<String, MutableList<Double>> = LinkedHashMap(),
        val energy: MutableMap<String, MutableList<Double>> = LinkedHashMap(),
        val cog: MutableMap<String, MutableList<Double>> = LinkedHashMap(),
        val kcal: MutableMap<String, Double> = LinkedHashMap(),
    )

    private fun MutableMap<String, MutableList<Double>>.push(day: String, value: Double) {
        getOrPut(day) { mutableListOf() } += value
    }

    /** Reads the four documents once; every value is validated, anything malformed is skipped. */
    private fun indexDocuments(docs: Map<String, JsonElement>, tz: String): DayIndex {
        val index = DayIndex()
        val zone = TimeZone.of(tz)
        fun dayOf(at: Instant): String = at.toLocalDateTime(zone).date.toString()

        for (s in records(docs["sleep"], "sessions")) {
            val start = parseInstant(s["start"]) ?: continue
            val end = parseInstant(s["end"]) ?: continue
            val ms = end.toEpochMilliseconds() - start.toEpochMilliseconds()
            if (ms >= MIN_SLEEP_MS) index.sleepMs.merge(dayOf(end), ms, Long::plus)
        }
        for (e in records(docs["wellbeing"], "mood")) {
            val day = phoneDayOf(parseInstant(e["day"]) ?: continue)
            number(e["mood"], 1.0, 10.0)?.let { index.mood.push(day, it) }
            number(e["energy"], 1.0, 10.0)?.let { index.energy.push(day, it) }
        }
        for (r in records(docs["cognitive"], "results")) {
            val at = parseInstant(r["date"]) ?: continue
            number(r["score"], 0.0, 100.0)?.let { index.cog.push(dayOf(at), it) }
        }
        for (m in records(docs["nutrition"], "meals")) {
            val at = parseInstant(m["date"]) ?: continue
            val kcal = number(m["kcal"])?.takeIf { it >= 0 } ?: continue
            index.kcal.merge(dayOf(at), kcal, Double::plus)
        }
        return index
    }

    private fun inputsFromIndex(index: DayIndex, day: String): DailyInputs = DailyInputs(
        sleepHours = index.sleepMs[day]?.let { round2(it / HOUR_MS) },
        mood = index.mood[day]?.let { mean(it) }?.let(::round2),
        energy = index.energy[day]?.let { mean(it) }?.let(::round2),
        cog = index.cog[day]?.let { mean(it) }?.let(::round2),
        kcal = index.kcal[day]?.let(::round2),
    )

    /**
     * One player's numbers for one day: sleep hours of the sessions that END that day (a night belongs to the
     * wake-up day), mood and energy as the day's average, cognitive index as the average test score, kilocalories
     * as the sum of meals. A value is null when there is no record for the day.
     */
    fun dailyInputs(docs: Map<String, JsonElement>, day: String, tz: String): DailyInputs =
        inputsFromIndex(indexDocuments(docs, tz), day)

    // ------------------------------------------------------------ overview

    private class PlayerIndex(val id: String, val index: DayIndex)

    /** Mean over players of `pick`, per day; players without a value that day are left out. */
    private fun dailySeries(players: List<PlayerIndex>, days: List<String>, pick: (DailyInputs) -> Double?): List<Double?> =
        days.map { day -> mean(players.mapNotNull { pick(inputsFromIndex(it.index, day)) }) }

    private fun weeklyMetric(
        key: MetricKey,
        thresholdName: String,
        players: List<PlayerIndex>,
        days: List<String>,
        previousDays: List<String>,
        pick: (DailyInputs) -> Double?,
    ): WeeklyMetric {
        val series = dailySeries(players, days, pick)
        val value = meanOfKnown(series)
        val previous = meanOfKnown(dailySeries(players, previousDays, pick))
        val threshold = PILL_THRESHOLDS.getValue(thresholdName)
        return WeeklyMetric(
            key = key,
            value = round2OrNull(value),
            previous = round2OrNull(previous),
            delta = if (value != null && previous != null) round2(value - previous) else null,
            direction = if (value != null && previous != null) (if (value >= previous) Direction.UP else Direction.DOWN) else null,
            pill = value?.let { pill(it, threshold.ok, threshold.mid) },
            series = series.map(::round2OrNull),
        )
    }

    /**
     * Everything «Обзор» renders. [who] is `"team"` or a player id; the selection feeds the mood, sleep, energy
     * and weekly blocks, while `teamReadiness` and `playerReadiness` always cover the whole team. Players without
     * data are skipped by every mean; a block with no data at all is null.
     */
    fun teamOverview(players: List<PlayerDocuments>, today: String, tz: String, who: String): OverviewData {
        val all = players.map { PlayerIndex(it.id, indexDocuments(it.docs, tz)) }
        val selected = if (who == "team") all else all.filter { it.id == who }
        val days = daysEndingAt(today, WINDOW_DAYS)
        val previousDays = daysEndingAt(shiftDay(today, -WINDOW_DAYS), WINDOW_DAYS)

        val playerReadiness = all.map { p ->
            val value = readiness(inputsFromIndex(p.index, today))
            PlayerReadiness(p.id, value, value?.let { readinessStatus(it.toDouble()) })
        }
        val teamReadiness = mean(playerReadiness.mapNotNull { it.readiness?.toDouble() })?.let { roundHalfUp(it).toInt() }

        val moodRecords = selected.flatMap { p -> days.flatMap { p.index.mood[it] ?: emptyList() } }
        val moodAverage = mean(moodRecords)
        val categories = moodCategories(moodRecords).map { c ->
            val percent = if (moodRecords.isEmpty()) 0 else roundHalfUp(c.count.toDouble() / moodRecords.size * 100).toInt()
            MoodCategoryShare(c.name, c.range, c.count, percent)
        }

        val sleepDaily = dailySeries(selected, days) { it.sleepHours }
        val sleepAverage = meanOfKnown(sleepDaily)
        var lowestIndex = -1
        sleepDaily.forEachIndexed { i, hours ->
            val best = if (lowestIndex < 0) null else sleepDaily[lowestIndex]
            if (hours != null && (best == null || hours < best)) lowestIndex = i
        }
        val lowestHours = if (lowestIndex < 0) null else sleepDaily[lowestIndex]

        val energyDaily = dailySeries(selected, days) { it.energy }
        val yesterdayEnergy = energyDaily[energyDaily.size - 2]
        val todayEnergy = energyDaily.last()
        val energyTrend = if (yesterdayEnergy == null || todayEnergy == null) null
        else if (todayEnergy >= yesterdayEnergy) Direction.UP else Direction.DOWN

        val mood = PILL_THRESHOLDS.getValue("mood")
        val sleep = PILL_THRESHOLDS.getValue("sleep")
        return OverviewData(
            who = who,
            today = today,
            days = days,
            teamReadiness = teamReadiness,
            teamReadinessStatus = teamReadiness?.let { readinessStatus(it.toDouble()) },
            playerReadiness = playerReadiness,
            mood = MoodOverview(
                count = moodRecords.size,
                average = round2OrNull(moodAverage),
                pill = moodAverage?.let { pill(it, mood.ok, mood.mid) },
                categories = categories,
            ),
            sleep = SleepOverview(
                daily = sleepDaily.map(::round2OrNull),
                average = round2OrNull(sleepAverage),
                pill = sleepAverage?.let { pill(it, sleep.ok, sleep.mid) },
                lowest = lowestHours?.let { LowestSleep(days[lowestIndex], round2(it)) },
            ),
            energy = EnergyOverview(energyDaily.map(::round2OrNull), round2OrNull(todayEnergy), energyTrend),
            metrics = listOf(
                weeklyMetric(MetricKey.READINESS, "readiness", selected, days, previousDays) { readiness(it)?.toDouble() },
                weeklyMetric(MetricKey.COGNITIVE, "cognitive", selected, days, previousDays) { it.cog },
                weeklyMetric(MetricKey.NUTRITION, "nutrition", selected, days, previousDays) { it.kcal },
            ),
        )
    }
}

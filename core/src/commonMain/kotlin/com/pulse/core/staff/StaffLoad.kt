package com.pulse.core.staff

import com.pulse.core.feature.StaffMetrics
import com.pulse.core.feature.StaffMetrics.number
import com.pulse.core.feature.StaffMetrics.parseInstant
import com.pulse.core.feature.StaffMetrics.records
import com.pulse.core.model.DailyValue
import com.pulse.core.model.TrainingLoadMath
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A player's training load as the staff screens show it, from the shared `fitness-state` document. Only workouts the
 * player rated count (their own RPE, or a rating given to a Health workout): the app's estimate from pulse or workout type
 * needs the player's age, which is never shared, so an unrated workout adds nothing rather than a guessed load.
 */
internal object StaffLoad {
    private const val MAX_WORKOUT_MINUTES = 1440.0
    private const val RPE_MIN = 1.0
    private const val RPE_MAX = 10.0

    private class RatedWorkout(val at: kotlin.time.Instant, val load: Double)

    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    /** The ratings the player gave to Health workouts: a flat `[id, value, id, value, …]` array, ids compared in lower case. */
    private fun ratingsOf(fitness: JsonElement?): Map<String, Int> =
        ((fitness as? JsonObject)?.get("ratings") as? JsonArray).orEmpty().chunked(2).mapNotNull { pair ->
            val id = text(pair[0]) ?: return@mapNotNull null
            val value = number(pair.getOrNull(1), RPE_MIN, RPE_MAX) ?: return@mapNotNull null
            id.lowercase() to value.toInt()
        }.toMap()

    private fun ratedWorkouts(fitness: JsonElement?): List<RatedWorkout> {
        val ratings = ratingsOf(fitness)
        return records(fitness, "workouts").mapNotNull { w ->
            val id = text(w["id"])?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val at = parseInstant(w["date"]) ?: return@mapNotNull null
            val minutes = number(w["minutes"], 0.0, MAX_WORKOUT_MINUTES) ?: return@mapNotNull null
            val effort = number(w["rpe"], RPE_MIN, RPE_MAX)?.toInt() ?: ratings[id.lowercase()]
            TrainingLoadMath.ratedLoad(effort, minutes)?.let { RatedWorkout(at, it) }
        }
    }

    /** Acute / chronic load of the 28 days ending [endDay]; null when no rated workout falls in them. */
    fun of(docs: Map<String, JsonElement>, endDay: String, tz: String): StaffViews.LoadView? {
        val perDay = HashMap<String, Double>()
        for (w in ratedWorkouts(docs["fitness-state"])) perDay.merge(StaffMetrics.dayOf(w.at, tz), w.load, Double::plus)
        val first = StaffMetrics.shiftDay(endDay, -(TrainingLoadMath.CHRONIC_DAYS - 1))
        val zone = TimeZone.of(tz)
        val days = List(TrainingLoadMath.CHRONIC_DAYS) { StaffMetrics.shiftDay(first, it) }
        if (days.none { it in perDay }) return null
        val ratio = TrainingLoadMath.ratio(days.map { DailyValue(LocalDate.parse(it).atStartOfDayIn(zone), perDay[it] ?: 0.0) })
        return StaffViews.LoadView(ratio.acute, ratio.chronicWeekly, ratio.value, ratio.status)
    }
}

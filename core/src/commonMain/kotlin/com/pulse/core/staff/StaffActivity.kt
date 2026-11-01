package com.pulse.core.staff

import com.pulse.core.feature.StaffMetrics
import com.pulse.core.feature.StaffMetrics.parseInstant
import com.pulse.core.feature.StaffMetrics.phoneDayOf
import com.pulse.core.feature.StaffMetrics.records
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.serialization.json.JsonElement
import kotlin.time.Instant

/** When a player last recorded anything in the app, for «нет данных N дн.». */
internal object StaffActivity {
    /** Where a record's time is: collection, list key, time field, and whether the time is a phone-midnight day. */
    private class Source(val collection: String, val list: String, val field: String, val phoneDay: Boolean)

    /** Every record the app keeps per day: sleep, mood and energy, the balance wheel, tests, workouts, screen time, meals. */
    private val SOURCES = listOf(
        Source("sleep", "sessions", "end", phoneDay = false),
        Source("wellbeing", "mood", "day", phoneDay = true),
        Source("wellbeing", "balance", "day", phoneDay = true),
        Source("cognitive", "results", "date", phoneDay = false),
        Source("cognitive", "gameResults", "date", phoneDay = false),
        Source("fitness-state", "workouts", "date", phoneDay = false),
        Source("screen-time", "entries", "date", phoneDay = true),
        Source("nutrition", "meals", "date", phoneDay = false),
    )

    /** Days from the player's latest record up to [today]; null when the player has no record on or before it. */
    fun daysSilent(docs: Map<String, JsonElement>, today: String, tz: String): Int? {
        val last = SOURCES.flatMap { source ->
            records(docs[source.collection], source.list).mapNotNull { record ->
                val at: Instant = parseInstant(record[source.field]) ?: return@mapNotNull null
                if (source.phoneDay) phoneDayOf(at) else StaffMetrics.dayOf(at, tz)
            }
        }.filter { it <= today }.maxOrNull() ?: return null
        return LocalDate.parse(last).daysUntil(LocalDate.parse(today))
    }
}

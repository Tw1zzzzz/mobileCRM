package com.pulse.core.model

import com.pulse.core.util.PulseCalendar
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Instant

/** Tests run in a fixed zone so day boundaries are deterministic. */
val testZone: TimeZone = TimeZone.of("Europe/Moscow")

fun pinTestZone() {
    PulseCalendar.timeZone = testZone
}

fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Instant =
    LocalDateTime(year, month, day, hour, minute).toInstant(testZone)

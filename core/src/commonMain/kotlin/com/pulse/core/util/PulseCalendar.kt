package com.pulse.core.util

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Gregorian calendar with Monday as the first day of the week — the Kotlin twin of iOS `Calendar.pulse`.
 * Every day-based helper works in [timeZone] (the device zone; tests pin it).
 */
object PulseCalendar {
    var timeZone: TimeZone = TimeZone.currentSystemDefault()
}

fun Instant.localDateTime(): LocalDateTime = toLocalDateTime(PulseCalendar.timeZone)

fun Instant.localDate(): LocalDate = localDateTime().date

fun LocalDate.startInstant(): Instant = atStartOfDayIn(PulseCalendar.timeZone)

fun Instant.startOfDay(): Instant = localDate().startInstant()

/** Calendar days: keeps the wall-clock time across DST changes, like `Calendar.date(byAdding: .day)`. */
fun Instant.addingDays(days: Int): Instant {
    val local = localDateTime()
    val date = local.date.plus(days, DateTimeUnit.DAY)
    return LocalDateTime(date, local.time).toInstant(PulseCalendar.timeZone)
}

fun Instant.addingMinutes(minutes: Int): Instant = this + minutes.minutes

fun Instant.isSameDay(other: Instant): Boolean = localDate() == other.localDate()

/** Monday of the week containing this instant (start of day). */
fun Instant.startOfWeek(): Instant {
    val date = localDate()
    return date.minus(date.dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber, DateTimeUnit.DAY).startInstant()
}

fun Instant.setting(hour: Int, minute: Int): Instant =
    LocalDateTime(localDate(), LocalTime(hour, minute)).toInstant(PulseCalendar.timeZone)

/** Whole calendar days from `this` to [other] (by local dates). */
fun Instant.daysUntil(other: Instant): Int = (other.localDate().toEpochDays() - localDate().toEpochDays()).toInt()

package com.pulse.core.util

import kotlin.math.abs
import kotlin.time.Instant

/** Fixed-point formatting with printf semantics (exact binary value, round half to even). */
internal expect fun formatFixed(value: Double, fractionDigits: Int): String

/** Russian-locale formatting helpers ("6 840", "74,2", "7 ч 42 м", "23:12") — same output as iOS `Formatters`. */
object Formatters {
    private val monthsGenitive = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря"
    )
    private val monthsShort = listOf(
        "янв.", "февр.", "марта", "апр.", "мая", "июня",
        "июля", "авг.", "сент.", "окт.", "нояб.", "дек."
    )
    private val monthsStandalone = listOf(
        "январь", "февраль", "март", "апрель", "май", "июнь",
        "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"
    )
    private val weekdaysShort = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
    private val weekdaysFull = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")

    private fun two(value: Int) = value.toString().padStart(2, '0')

    fun time(instant: Instant): String {
        val local = instant.localDateTime()
        return "${two(local.hour)}:${two(local.minute)}"
    }

    /** Stable day id like "2026-10-14" (not for display). */
    fun dayKey(instant: Instant): String = instant.localDate().toString()

    fun dayMonth(instant: Instant): String {
        val date = instant.localDate()
        return "${date.day} ${monthsGenitive[date.month.ordinal]}"
    }

    fun dayMonthShort(instant: Instant): String {
        val date = instant.localDate()
        return "${date.day} ${monthsShort[date.month.ordinal]}"
    }

    fun dayMonthTime(instant: Instant): String = "${dayMonth(instant)}, ${time(instant)}"

    fun weekdayShort(instant: Instant): String = weekdaysShort[instant.localDate().dayOfWeek.ordinal].capitalizedFirst()

    fun weekdayFull(instant: Instant): String = weekdaysFull[instant.localDate().dayOfWeek.ordinal].capitalizedFirst()

    fun monthYear(instant: Instant): String {
        val date = instant.localDate()
        return "${monthsStandalone[date.month.ordinal]} ${date.year}".capitalizedFirst()
    }

    fun dayMonthWeekday(instant: Instant): String =
        "${dayMonth(instant)}, ${weekdaysFull[instant.localDate().dayOfWeek.ordinal]}".capitalizedFirst()

    data class DurationParts(val hours: Int, val minutes: Int)

    fun durationParts(minutes: Int): DurationParts {
        val safe = maxOf(minutes, 0)
        return DurationParts(safe / 60, safe % 60)
    }

    /** "3 ч 33 м" or "24 мин" when under an hour. */
    fun duration(minutes: Int): String {
        val parts = durationParts(minutes)
        if (parts.hours == 0) return "${parts.minutes} мин"
        return "${parts.hours} ч ${parts.minutes} м"
    }

    /** Live timer "07:42:05". */
    fun timer(seconds: Double): String {
        val total = maxOf(seconds.toInt(), 0)
        return "${two(total / 3600)}:${two((total % 3600) / 60)}:${two(total % 60)}"
    }

    /** "6 840", "-2 500". */
    fun grouped(value: Int): String {
        val digits = abs(value.toLong()).toString()
        val groups = digits.reversed().chunked(3).joinToString(" ").reversed()
        return if (value < 0) "-$groups" else groups
    }

    fun decimal(value: Double, fractionDigits: Int = 1): String =
        formatFixed(value, fractionDigits).replace('.', ',')

    /** «+0,3», «−1,2», «0,0» (no sign when the rounded value is zero). */
    fun signedDecimal(value: Double, fractionDigits: Int = 1): String {
        val text = decimal(abs(value), fractionDigits)
        if (text.replace(',', '.').toDouble() == 0.0) return text
        return (if (value > 0) "+" else "−") + text
    }

    /** «+12», «−3», «0». */
    fun signed(value: Int): String = when {
        value == 0 -> "0"
        value > 0 -> "+$value"
        else -> "−${abs(value)}"
    }

    fun liters(milliliters: Int): String =
        decimal(milliliters / 1000.0, if (milliliters % 1000 == 0) 0 else 1)

    /** «1 год», «3 года», «7 лет». */
    fun years(value: Int): String = plural(value, "год", "года", "лет")

    /** Russian plural: «1 вариация», «3 вариации», «5 вариаций», «11 вариаций», «21 вариация». */
    fun plural(value: Int, one: String, few: String, many: String): String {
        val mod10 = value % 10
        val mod100 = value % 100
        val word = when {
            mod10 == 1 && mod100 != 11 -> one
            mod10 in 2..4 && mod100 !in 12..14 -> few
            else -> many
        }
        return "$value $word"
    }
}

fun String.capitalizedFirst(): String = if (isEmpty()) this else this[0].uppercase() + substring(1)

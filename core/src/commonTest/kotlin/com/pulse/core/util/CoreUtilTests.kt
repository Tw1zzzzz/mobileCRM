package com.pulse.core.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** Expected values were printed by the iOS code (Swift) — the Android core must match them exactly. */
class SeededGeneratorTests {
    @Test
    fun rawSequenceMatchesSwift() {
        val generator = SeededGenerator(101uL)
        assertEquals(
            listOf(13862237289739664249uL, 5349496680585294671uL, 14655467343821870441uL, 1204176353374683395uL),
            List(4) { generator.next() }
        )
        assertEquals(7377219508542733812uL, SeededGenerator(0uL).next())
    }

    @Test
    fun intRangeMatchesSwift() {
        val generator = SeededGenerator(202uL)
        assertEquals(listOf(70, 70, 68, 62, 85, 76, 61, 67), List(8) { generator.int(50..85) })
    }

    @Test
    fun doubleRangeMatchesSwift() {
        val generator = SeededGenerator(303uL)
        assertEquals(
            listOf(0.7186848499365279, 0.5254742904262046, 1.0509384286016903, 1.2867574683778025, 0.9509300940908323),
            List(5) { generator.double(0.5..1.5) }
        )
    }

    @Test
    fun thousandSeedsMatchSwiftBitForBit() {
        var hash = 1469598103934665603uL
        fun mix(value: ULong) {
            hash = (hash xor value) * 1099511628211uL
        }
        for (seed in 1..1000) {
            val generator = SeededGenerator(seed.toULong())
            mix(generator.double(-0.2..0.2).toRawBits().toULong())
            mix(generator.double(360.0..540.0).toRawBits().toULong())
            mix(generator.int(-40..100).toLong().toULong())
        }
        assertEquals(12745100079131826935uL, hash)
    }

    @Test
    fun mixedCallsMatchSwift() {
        val generator = SeededGenerator(404uL)
        assertEquals(3, generator.int(-3..3))
        assertEquals(428.6738782214779, generator.double(360.0..540.0))
        assertEquals(1, generator.int(0..1))
        assertEquals(-0.16842097944567744, generator.double(-0.2..0.2))
    }
}

class FormattersTests {
    private val zone = TimeZone.of("Europe/Moscow")

    @BeforeTest
    fun pinZone() {
        PulseCalendar.timeZone = zone
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Instant =
        LocalDateTime(year, month, day, hour, minute).toInstant(zone)

    @Test
    fun datesMatchIos() {
        assertEquals(
            "5 янв.|5 февр.|5 марта|5 апр.|5 мая|5 июня|5 июля|5 авг.|5 сент.|5 окт.|5 нояб.|5 дек.",
            (1..12).joinToString("|") { Formatters.dayMonthShort(at(2026, it, 5)) }
        )
        assertEquals(
            "5 января|5 февраля|5 марта|5 апреля|5 мая|5 июня|5 июля|5 августа|5 сентября|5 октября|5 ноября|5 декабря",
            (1..12).joinToString("|") { Formatters.dayMonth(at(2026, it, 5)) }
        )
        assertEquals("Май 2026", Formatters.monthYear(at(2026, 5, 5)))
        assertEquals("Пн|Вт|Ср|Чт|Пт|Сб|Вс", (12..18).joinToString("|") { Formatters.weekdayShort(at(2026, 10, it)) })
        assertEquals("Понедельник|Вторник|Среда|Четверг|Пятница|Суббота|Воскресенье",
            (12..18).joinToString("|") { Formatters.weekdayFull(at(2026, 10, it)) })
        assertEquals("15 октября, четверг", Formatters.dayMonthWeekday(at(2026, 10, 15)))
        assertEquals("15 октября, 09:05", Formatters.dayMonthTime(at(2026, 10, 15, 9, 5)))
        assertEquals("2026-10-15", Formatters.dayKey(at(2026, 10, 15)))
    }

    @Test
    fun numbersMatchIos() {
        assertEquals("6 840 1 234 567 -2 500 999",
            listOf(6840, 1234567, -2500, 999).joinToString(" ") { Formatters.grouped(it) })
        assertEquals("74,2 0,1 2,67 0,33 -0,0", listOf(
            Formatters.decimal(74.25), Formatters.decimal(0.05), Formatters.decimal(2.675, 2),
            Formatters.decimal(1.0 / 3, 2), Formatters.decimal(-0.04)
        ).joinToString(" "))
        assertEquals("+0,3 −1,2 0,0 −3 0", listOf(
            Formatters.signedDecimal(0.3), Formatters.signedDecimal(-1.25), Formatters.signedDecimal(-0.04),
            Formatters.signed(-3), Formatters.signed(0)
        ).joinToString(" "))
        assertEquals("3 ч 33 м|24 мин|0 мин|07:42:05|2,5|2", listOf(
            Formatters.duration(213), Formatters.duration(24), Formatters.duration(-5),
            Formatters.timer(27725.0), Formatters.liters(2500), Formatters.liters(2000)
        ).joinToString("|"))
        assertEquals("1 год|3 года|11 лет|22 года", listOf(1, 3, 11, 22).joinToString("|") { Formatters.years(it) })
    }

    @Test
    fun calendarHelpers() {
        val thursday = at(2026, 10, 15, 21, 30)
        assertEquals(at(2026, 10, 12, 0, 0), thursday.startOfWeek())
        assertEquals(at(2026, 10, 15, 0, 0), thursday.startOfDay())
        assertEquals(at(2026, 10, 16, 21, 30), thursday.addingDays(1))
        assertEquals(at(2026, 10, 15, 8, 0), thursday.setting(8, 0))
        assertEquals(LocalDate(2026, 10, 15), thursday.localDate())
        assertEquals(3, thursday.daysUntil(at(2026, 10, 18, 1, 0)))
    }
}

class StatisticsMathTests {
    @Test
    fun meanAndPearson() {
        assertNull(StatisticsMath.mean(emptyList()))
        assertEquals(2.0, StatisticsMath.mean(listOf(1.0, 2.0, 3.0)))
        assertEquals(1.0, StatisticsMath.pearson(listOf(1.0, 2.0, 3.0), listOf(2.0, 4.0, 6.0))!!, 1e-9)
        assertNull(StatisticsMath.pearson(listOf(1.0, 2.0), listOf(1.0, 2.0)))
        assertNull(StatisticsMath.pearson(listOf(1.0, 1.0, 1.0), listOf(1.0, 2.0, 3.0)))
        assertEquals(CorrelationStrength.MODERATE, CorrelationStrength.of(-0.45))
    }
}

class IsoInstantSerializerTests {
    @Serializable
    private data class Box(@Serializable(with = IsoInstantSerializer::class) val at: Instant)

    @Test
    fun writesWholeSecondsUtcLikeIos() {
        val json = Json.encodeToString(Box.serializer(), Box(Instant.fromEpochMilliseconds(1_792_090_800_123)))
        assertEquals("""{"at":"2026-10-15T19:00:00Z"}""", json)
        assertEquals(Instant.fromEpochSeconds(1_792_090_800), Json.decodeFromString(Box.serializer(), json).at)
    }
}

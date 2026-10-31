package com.pulse.core.feature

import com.pulse.core.util.PulseJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the Kotlin [StaffSeries] over `server/test/fixtures/staff-series.json`, the file the TypeScript test of the
 * staff panel reads too: the app and the panel must draw the same per-day series for the same data.
 */
class StaffSeriesParityTest {
    private val fixture: JsonObject = loadFixture()

    @Test
    fun fixtureHasAtLeastTwelveCases() {
        assertTrue(cases("series").size + cases("periodDays").size >= 12)
    }

    @Test
    fun sleepNightEndsOnTheDay() {
        assertEquals(mapOf("2026-10-15" to 10.0, "2026-10-17" to 7.0), run("sleepNightEndsOnTheDay").sleep)
    }

    @Test
    fun shortSleepIgnored() {
        assertEquals(mapOf("2026-10-16" to 1.0), run("shortSleepIgnored").sleep)
    }

    @Test
    fun moodUsesPhoneDay() {
        val s = run("moodUsesPhoneDay")
        assertEquals(mapOf("2026-10-30" to 7.5, "2026-10-31" to 6.5), s.mood)
        assertEquals(mapOf("2026-10-30" to 5.5, "2026-10-31" to 5.5), s.energy)
    }

    @Test
    fun balanceMeanOfEightMissingKeyIsFive() {
        val s = run("balanceMeanOfEightMissingKeyIsFive")
        assertEquals(6.75, s.balance["2026-10-30"])
        assertEquals(5.625, s.balance["2026-10-31"])
        assertEquals(5.0, s.balance["2026-11-01"])
        assertEquals(5.0, s.balance["2026-11-02"])
        assertEquals(4, s.balance.size)
    }

    @Test
    fun balanceZeroSpokeCountsAsZero() {
        val s = run("balanceZeroSpokeCountsAsZero")
        assertEquals(30.0 / 8, s.balance["2026-10-30"])
        assertEquals(70.0 / 8, s.balance["2026-10-31"])
        assertEquals(0.0, s.balance["2026-11-01"])
    }

    @Test
    fun readinessSeriesMatchesDailyInputs() {
        val c = case("readinessSeriesMatchesDailyInputs")
        val docs = c.getValue("docs").jsonObject
        val tz = c.getValue("tz").jsonPrimitive.content
        val expected = LinkedHashMap<String, Double>()
        var day = c.getValue("from").jsonPrimitive.content
        val to = c.getValue("to").jsonPrimitive.content
        while (day <= to) {
            StaffMetrics.readiness(StaffMetrics.dailyInputs(docs, day, tz))?.let { expected[day] = it.toDouble() }
            day = StaffMetrics.shiftDay(day, 1)
        }
        assertEquals(expected, run("readinessSeriesMatchesDailyInputs").readiness)
    }

    @Test
    fun periodDaysEndsToday() {
        val p = StaffSeries.periodDays("2026-10-31", 7)
        assertEquals("2026-10-25", p.from)
        assertEquals("2026-10-31", p.to)
        assertEquals(7, p.days.size)
        assertEquals("2026-10-25", p.days.first())
        assertFailsWith<IllegalArgumentException> { StaffSeries.periodDays("2026-10-31", 0) }
    }

    @Test
    fun playerSeriesMatchesFixture() {
        for (c in cases("series")) {
            val actual = StaffSeries.playerSeries(
                StaffMetrics.PlayerDocuments("p", c.getValue("docs").jsonObject),
                c.getValue("from").jsonPrimitive.content,
                c.getValue("to").jsonPrimitive.content,
                c.getValue("tz").jsonPrimitive.content,
            )
            val expected = c.getValue("expected").jsonObject
            val byKey = mapOf(
                "sleep" to actual.sleep, "mood" to actual.mood, "energy" to actual.energy, "cog" to actual.cog,
                "kcal" to actual.kcal, "screen" to actual.screen, "balance" to actual.balance, "focus" to actual.focus,
                "fatigue" to actual.fatigue, "elo" to actual.elo, "readiness" to actual.readiness,
            )
            assertEquals(expected.keys, byKey.keys, "${name(c)}: series keys")
            for ((key, got) in byKey) assertDaySeries(expected.getValue(key), got, "${name(c)}.$key")
        }
    }

    @Test
    fun periodDaysMatchFixture() {
        for (c in cases("periodDays")) {
            val actual = StaffSeries.periodDays(c.getValue("to").jsonPrimitive.content, c.getValue("days").jsonPrimitive.int)
            val expected = c.getValue("expected").jsonObject
            assertEquals(expected.getValue("from").jsonPrimitive.content, actual.from, name(c))
            assertEquals(expected.getValue("to").jsonPrimitive.content, actual.to, name(c))
            assertEquals(expected.getValue("days").jsonArray.map { it.jsonPrimitive.content }, actual.days, name(c))
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun cases(section: String): List<JsonObject> = fixture.getValue(section).jsonArray.map { it.jsonObject }

    private fun name(case: JsonObject): String = case.getValue("name").jsonPrimitive.content

    private fun case(name: String): JsonObject = cases("series").first { name(it) == name }

    private fun run(name: String): StaffSeries.PlayerSeries {
        val c = case(name)
        return StaffSeries.playerSeries(
            StaffMetrics.PlayerDocuments("p", c.getValue("docs").jsonObject),
            c.getValue("from").jsonPrimitive.content,
            c.getValue("to").jsonPrimitive.content,
            c.getValue("tz").jsonPrimitive.content,
        )
    }

    /** Same days in the same order; values equal within 1e-9. */
    private fun assertDaySeries(expected: JsonElement, actual: Map<String, Double>, label: String) {
        val want = expected.jsonObject
        assertEquals(want.keys.toList(), actual.keys.toList(), "$label: days")
        for ((day, value) in want) {
            val diff = abs(value.jsonPrimitive.double - actual.getValue(day))
            if (diff > 1e-9) fail("$label.$day: expected ${value.jsonPrimitive.double}, got ${actual.getValue(day)}")
        }
    }

    companion object {
        private const val RELATIVE = "server/test/fixtures/staff-series.json"

        /** Gradle runs tests in `core/`; look in `../server/...` first, then walk up until the file exists. */
        private fun loadFixture(): JsonObject {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            val sibling = File(start, "../$RELATIVE")
            val file = if (sibling.isFile) sibling else generateSequence(start) { it.parentFile }
                .map { File(it, RELATIVE) }
                .firstOrNull { it.isFile }
                ?: fail("Cannot find $RELATIVE from $start or any parent directory")
            return PulseJson.parseToJsonElement(file.readText()).jsonObject
        }
    }
}

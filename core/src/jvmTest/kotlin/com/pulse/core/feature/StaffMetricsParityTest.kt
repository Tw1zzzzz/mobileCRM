package com.pulse.core.feature

import com.pulse.core.util.PulseJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the Kotlin [StaffMetrics] over `server/test/fixtures/staff-metrics.json`, the file the TypeScript test
 * of the staff panel reads too: the app and the panel must give the same number for the same data.
 */
class StaffMetricsParityTest {
    private val fixture: JsonObject = loadFixture()
    private val wire = Json { explicitNulls = true; encodeDefaults = true }

    @Test
    fun fixtureHasAtLeastTwelveCases() {
        val sections = listOf("readiness", "readinessStatus", "moodCategories", "pill", "dailyInputs", "teamOverview")
        assertTrue(sections.sumOf { cases(it).size } >= 12)
    }

    @Test
    fun readinessMatchesFixture() {
        for (c in cases("readiness")) {
            val inputs = PulseJson.decodeFromJsonElement(StaffMetrics.DailyInputs.serializer(), c.getValue("inputs"))
            val expected = c.getValue("expected")
            val actual = StaffMetrics.readiness(inputs)
            if (expected is JsonNull) assertNull(actual, name(c)) else assertEquals(expected.jsonPrimitive.double, actual?.toDouble(), name(c))
        }
    }

    @Test
    fun readinessStatusMatchesFixture() {
        for (c in cases("readinessStatus")) {
            val actual = wire.encodeToJsonElement(StaffMetrics.readinessStatus(c.getValue("value").jsonPrimitive.double))
            assertJson(c.getValue("expected"), actual, name(c))
        }
    }

    @Test
    fun moodCategoriesMatchFixture() {
        for (c in cases("moodCategories")) {
            val entries = c.getValue("entries").jsonArray.map { it.jsonPrimitive.double }
            assertJson(c.getValue("expected"), wire.encodeToJsonElement(StaffMetrics.moodCategories(entries)), name(c))
        }
    }

    @Test
    fun pillMatchesFixture() {
        for (c in cases("pill")) {
            val actual = StaffMetrics.pill(
                c.getValue("value").jsonPrimitive.double,
                c.getValue("ok").jsonPrimitive.double,
                c.getValue("mid").jsonPrimitive.double,
            )
            assertJson(c.getValue("expected"), wire.encodeToJsonElement(actual), name(c))
        }
    }

    @Test
    fun pillThresholdsFromBoard() {
        val expected = fixture.getValue("thresholds").jsonObject
        assertEquals(expected.keys, StaffMetrics.PILL_THRESHOLDS.keys)
        for ((key, t) in StaffMetrics.PILL_THRESHOLDS) {
            assertEquals(expected.getValue(key).jsonObject.getValue("ok").jsonPrimitive.double, t.ok, key)
            assertEquals(expected.getValue(key).jsonObject.getValue("mid").jsonPrimitive.double, t.mid, key)
        }
    }

    @Test
    fun dailyInputsMatchFixture() {
        for (c in cases("dailyInputs")) {
            val docs = c.getValue("docs").jsonObject
            val actual = StaffMetrics.dailyInputs(docs, c.getValue("day").jsonPrimitive.content, c.getValue("tz").jsonPrimitive.content)
            assertJson(c.getValue("expected"), wire.encodeToJsonElement(actual), name(c))
        }
    }

    @Test
    fun dailyInputsUsesStaffTimeZone() {
        val docs = PulseJson.parseToJsonElement(
            """{"sleep":{"sessions":[{"id":"a","start":"2026-10-14T16:00:00Z","end":"2026-10-14T23:30:00Z"}]}}""",
        ).jsonObject
        assertEquals(7.5, StaffMetrics.dailyInputs(docs, "2026-10-15", StaffMetrics.STAFF_TIME_ZONE).sleepHours)
        assertNull(StaffMetrics.dailyInputs(docs, "2026-10-14", StaffMetrics.STAFF_TIME_ZONE).sleepHours)
        assertEquals("Europe/Moscow", StaffMetrics.STAFF_TIME_ZONE)
    }

    @Test
    fun teamOverviewMatchesFixture() {
        val rosters = fixture.getValue("rosters").jsonObject
        for (c in cases("teamOverview")) {
            val roster = rosters.getValue(c.getValue("roster").jsonPrimitive.content).jsonArray.map { it.jsonObject }
            val ids = c.getValue("ids").let { if (it is JsonNull) null else it.jsonArray.map { id -> id.jsonPrimitive.content } }
            val chosen = if (ids == null) roster else ids.map { id -> roster.first { it.getValue("id").jsonPrimitive.content == id } }
            val players = chosen.map {
                StaffMetrics.PlayerDocuments(it.getValue("id").jsonPrimitive.content, it.getValue("docs").jsonObject)
            }
            val actual = StaffMetrics.teamOverview(
                players,
                c.getValue("today").jsonPrimitive.content,
                c.getValue("tz").jsonPrimitive.content,
                c.getValue("who").jsonPrimitive.content,
            )
            assertJson(c.getValue("expected"), wire.encodeToJsonElement(actual), name(c))
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun cases(section: String): List<JsonObject> = fixture.getValue(section).jsonArray.map { it.jsonObject }

    private fun name(case: JsonObject): String = case.getValue("name").jsonPrimitive.content

    /** Objects: a key with null equals an absent key. Arrays keep their nulls. Numbers compare as doubles. */
    private fun assertJson(expected: JsonElement, actual: JsonElement, label: String) {
        val diff = firstDifference(withoutNullKeys(expected), withoutNullKeys(actual), label)
        if (diff != null) fail(diff)
    }

    private fun withoutNullKeys(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterValues { it !is JsonNull }.mapValues { withoutNullKeys(it.value) })
        is JsonArray -> JsonArray(e.map { withoutNullKeys(it) })
        else -> e
    }

    private fun firstDifference(expected: JsonElement, actual: JsonElement, path: String): String? = when {
        expected is JsonObject && actual is JsonObject -> {
            val missing = (expected.keys - actual.keys).firstOrNull()
            val extra = (actual.keys - expected.keys).firstOrNull()
            when {
                missing != null -> "$path: missing key '$missing'"
                extra != null -> "$path: unexpected key '$extra' = ${actual.getValue(extra)}"
                else -> expected.keys.firstNotNullOfOrNull { firstDifference(expected.getValue(it), actual.getValue(it), "$path.$it") }
            }
        }
        expected is JsonArray && actual is JsonArray ->
            if (expected.size != actual.size) "$path: size ${expected.size} != ${actual.size}"
            else expected.indices.firstNotNullOfOrNull { firstDifference(expected[it], actual[it], "$path[$it]") }
        expected is JsonNull && actual is JsonNull -> null
        expected is JsonPrimitive && actual is JsonPrimitive && !expected.isString && !actual.isString -> {
            val a = expected.doubleOrNull
            val b = actual.doubleOrNull
            when {
                a != null && b != null -> if (abs(a - b) <= 1e-9) null else "$path: expected $a, got $b"
                expected.contentOrNull == actual.contentOrNull -> null
                else -> "$path: expected $expected, got $actual"
            }
        }
        expected is JsonPrimitive && actual is JsonPrimitive ->
            if (expected.isString == actual.isString && expected.content == actual.content) null else "$path: expected $expected, got $actual"
        else -> "$path: expected $expected, got $actual"
    }

    companion object {
        private const val RELATIVE = "server/test/fixtures/staff-metrics.json"

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

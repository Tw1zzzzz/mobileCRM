package com.pulse.core.feature

import com.pulse.core.util.PulseJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the Kotlin [StaffStatistics] over `server/test/fixtures/staff-statistics.json`, the file the TypeScript test of
 * the staff panel reads too: the app and the panel must compute the same correlations for the same data.
 */
class StaffStatisticsParityTest {
    private val fixture: JsonObject = loadFixture()

    @Test
    fun fixtureHasAtLeastTwelveCases() {
        val total = listOf("pearson", "pairByDay", "correlate", "pooled", "medianSplit", "keyLinks").sumOf { cases(it).size }
        assertTrue(total >= 12)
    }

    @Test
    fun pearsonFixtureCases() {
        for (c in cases("pearson")) {
            assertClose(StaffStatistics.pearson(doubles(c, "xs"), doubles(c, "ys")), c.nullableDouble("expected"), name(c))
        }
    }

    @Test
    fun pearsonPerfect() {
        assertClose(StaffStatistics.pearson(listOf(1.0, 2.0, 3.0, 4.0, 5.0), listOf(3.0, 5.0, 7.0, 9.0, 11.0)), 1.0, "direct")
        assertClose(StaffStatistics.pearson(listOf(1.0, 2.0, 3.0, 4.0), listOf(8.0, 6.0, 4.0, 2.0)), -1.0, "inverse")
    }

    @Test
    fun pearsonTooFewPairsIsNull() {
        assertNull(StaffStatistics.pearson(listOf(1.0, 2.0), listOf(3.0, 4.0)))
        assertNull(StaffStatistics.pearson(emptyList(), emptyList()))
        assertNull(StaffStatistics.pearson(listOf(1.0, 2.0, 3.0, 4.0), listOf(1.0, 2.0, 3.0)))
    }

    @Test
    fun pearsonZeroVarianceIsNull() {
        assertNull(StaffStatistics.pearson(listOf(5.0, 5.0, 5.0, 5.0), listOf(1.0, 2.0, 3.0, 4.0)))
        assertNull(StaffStatistics.pearson(listOf(1.0, 2.0, 3.0, 4.0), listOf(7.0, 7.0, 7.0, 7.0)))
    }

    @Test
    fun pairByDayFixtureCases() {
        for (c in cases("pairByDay")) {
            val paired = StaffStatistics.pairByDay(series(c, "a"), series(c, "b"))
            val expected = c.getValue("expected").jsonObject
            assertEquals(doubles(expected, "xs"), paired.xs, name(c))
            assertEquals(doubles(expected, "ys"), paired.ys, name(c))
        }
    }

    @Test
    fun strengthLabelsEdges() {
        for (c in cases("strength")) {
            val s = StaffStatistics.strength(c.nullableDouble("r"))
            assertEquals(c.getValue("label").jsonPrimitive.content, s.label, "label of ${c["r"]}")
            val direction = c.getValue("direction").let { if (it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals(direction, s.direction, "direction of ${c["r"]}")
        }
    }

    @Test
    fun correlateFixtureCases() {
        for (c in cases("correlate")) {
            assertClose(StaffStatistics.correlate(series(c, "a"), series(c, "b")), c.nullableDouble("expected"), name(c))
        }
    }

    @Test
    fun pooledFixtureCases() {
        for (c in cases("pooled")) {
            val pairs = c.getValue("pairs").jsonArray.map { series(it.jsonObject, "a") to series(it.jsonObject, "b") }
            assertClose(StaffStatistics.correlatePooled(pairs), c.nullableDouble("expected"), name(c))
        }
    }

    @Test
    fun pooledUsesAllPlayers() {
        val c = cases("pooled").first { name(it) == "pooledUsesAllPlayers" }
        val pairs = c.getValue("pairs").jsonArray.map { series(it.jsonObject, "a") to series(it.jsonObject, "b") }
        assertNull(StaffStatistics.correlate(pairs[0].first, pairs[0].second))
        assertNull(StaffStatistics.correlate(pairs[1].first, pairs[1].second))
        assertNotNull(StaffStatistics.correlatePooled(pairs))
    }

    @Test
    fun medianSplitFixtureCases() {
        for (c in cases("medianSplit")) {
            val actual = StaffStatistics.medianSplit(doubles(c, "xs"), doubles(c, "ys"))
            val expected = c.getValue("expected")
            if (expected is JsonNull) {
                assertNull(actual, name(c))
            } else {
                val e = expected.jsonObject
                assertNotNull(actual, name(c))
                assertEquals(e.getValue("median").jsonPrimitive.double, actual.median, name(c))
                assertClose(actual.hiMean, e.getValue("hiMean").jsonPrimitive.double, "${name(c)} hi")
                assertClose(actual.loMean, e.getValue("loMean").jsonPrimitive.double, "${name(c)} lo")
            }
        }
    }

    @Test
    fun keyLinksFixtureCases() {
        for (c in cases("keyLinks")) {
            val metrics = c.getValue("metrics").jsonArray.map {
                StaffStatistics.LinkMetric(it.jsonObject.getValue("id").jsonPrimitive.content, it.jsonObject.getValue("group").jsonPrimitive.content)
            }
            val matrix = c.getValue("matrix").jsonObject.mapValues { (_, row) ->
                row.jsonObject.mapValues { (_, v) -> if (v is JsonNull) null else v.jsonPrimitive.double }
            }
            val actual = StaffStatistics.keyLinks(metrics, matrix)
            val expected = c.getValue("expected").jsonArray.map { it.jsonObject }
            assertEquals(
                expected.map { Triple(it.getValue("a").jsonPrimitive.content, it.getValue("b").jsonPrimitive.content, it.getValue("cross").jsonPrimitive.content.toBoolean()) },
                actual.map { Triple(it.a, it.b, it.cross) },
                name(c),
            )
            expected.forEachIndexed { i, e -> assertClose(actual[i].r, e.getValue("r").jsonPrimitive.double, "${name(c)}[$i]") }
        }
    }

    @Test
    fun keyLinksPrefersCrossPairsAndSwapsGameFirst() {
        val metrics = listOf("kd" to "game", "sleep" to "state", "elo" to "game", "mood" to "state")
            .map { StaffStatistics.LinkMetric(it.first, it.second) }
        val matrix = mapOf(
            "kd" to mapOf("sleep" to -0.8, "elo" to 0.99, "mood" to 0.2),
            "sleep" to mapOf("elo" to 0.4, "mood" to 0.99),
        )
        val links = StaffStatistics.keyLinks(metrics, matrix)
        assertEquals(listOf("sleep-kd", "sleep-elo", "mood-kd"), links.map { "${it.a}-${it.b}" })
        assertTrue(links.all { it.cross })
    }

    private fun cases(section: String): List<JsonObject> = fixture.getValue(section).jsonArray.map { it.jsonObject }

    private fun name(c: JsonObject): String = c["name"]?.jsonPrimitive?.content ?: "case"

    private fun doubles(c: JsonObject, key: String): List<Double> = c.getValue(key).jsonArray.map { it.jsonPrimitive.double }

    private fun series(c: JsonObject, key: String): DaySeries =
        c.getValue(key).jsonObject.mapValues { (_, v) -> v.jsonPrimitive.double }

    private fun JsonObject.nullableDouble(key: String): Double? =
        getValue(key).let { if (it is JsonNull) null else it.jsonPrimitive.double }

    private fun assertClose(actual: Double?, expected: Double?, label: String) {
        if (expected == null) {
            assertNull(actual, label)
        } else {
            assertNotNull(actual, label)
            assertTrue(abs(actual - expected) < 1e-9, "$label: expected $expected, got $actual")
        }
    }

    private companion object {
        const val RELATIVE = "server/test/fixtures/staff-statistics.json"

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

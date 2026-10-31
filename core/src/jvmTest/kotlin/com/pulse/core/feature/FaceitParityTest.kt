package com.pulse.core.feature

import com.pulse.core.model.FaceitMapper
import com.pulse.core.model.GameMatch
import com.pulse.core.model.GameSummary
import com.pulse.core.util.PulseJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant

/**
 * Runs the app's own `FaceitMapper.match` and summary code (`GameSummary`, the sums `TeamAnalytics.stats` takes) over
 * the raw Faceit items of `server/test/fixtures/faceit-matches.json`, the file the TypeScript test of the staff panel
 * reads too: the panel must map a Faceit payload, and total it up, the way the app does.
 *
 * Only what the app can compute is compared: mapped fields, and the tiles that exist in the app's summary code over the
 * whole match list. Day windows and the time zone exist only in the panel (covered by its TypeScript tests).
 */
class FaceitParityTest {
    private val fixture: JsonObject = loadFixture()
    private val now = Instant.parse(fixture.getValue("now").jsonPrimitive.content)
    private val expected: List<JsonObject> = fixture.getValue("mapped").jsonArray.map { it.jsonObject }
    private val tiles: JsonObject = fixture.getValue("tiles").jsonObject

    /** The app's mapping of every raw item; items without kills/deaths (or that are not objects) are skipped. */
    private val matches: List<GameMatch> = fixture.getValue("raw").jsonArray.mapNotNull { item ->
        val stats = (item as? JsonObject)?.get("stats") as? JsonObject
        stats?.let { FaceitMapper.match(it, now) }
    }

    @Test
    fun fixtureCoversTheCasesTheBriefNames() {
        val raw = fixture.getValue("raw").jsonArray
        assertTrue(raw.size > expected.size, "the fixture needs skipped items")
        assertTrue(expected.any { !it.getValue("won").jsonPrimitive.boolean }, "the fixture needs a loss")
        assertTrue(raw.toString().contains("\"87,25\""), "the fixture needs comma decimals")
        assertTrue(raw.toString().contains("1760000000000") && raw.toString().contains("1759900000"), "milliseconds and seconds")
    }

    @Test
    fun mapperKeepsTheSameItemsAsTheServer() {
        assertEquals(expected.size, matches.size)
    }

    @Test
    fun mappedFieldsEqualTheServerExpectations() {
        for ((i, want) in expected.withIndex()) {
            val got = matches[i]
            val label = "match $i"
            assertEquals(want.getValue("id").jsonPrimitive.content, got.id.toString(), "$label id")
            assertEquals(want.getValue("map").jsonPrimitive.content, got.details?.mapName ?: "", "$label map")
            assertEquals(want.getValue("won").jsonPrimitive.boolean, got.won, "$label won")
            assertEquals(want.getValue("scoreOwn").jsonPrimitive.int, got.roundsWon, "$label scoreOwn")
            assertEquals(want.getValue("scoreOther").jsonPrimitive.int, got.roundsLost, "$label scoreOther")
            assertEquals(want.getValue("kills").jsonPrimitive.int, got.kills, "$label kills")
            assertEquals(want.getValue("deaths").jsonPrimitive.int, got.deaths, "$label deaths")
            assertEquals(want.getValue("assists").jsonPrimitive.int, got.assists, "$label assists")
            near(want.getValue("adr").jsonPrimitive.double, got.adr, "$label adr")
            assertEquals(want.getValue("rounds").jsonPrimitive.int, got.details?.rounds, "$label rounds")
            assertEquals(Instant.parse(want.getValue("finishedAt").jsonPrimitive.content), got.date, "$label finishedAt")
            // The app's details default to 0 where the panel leaves a field out.
            assertEquals(want.optInt("firstKills"), got.details?.firstKills, "$label firstKills")
            assertEquals(want.optInt("entryCount") ?: 0, got.details?.entryCount, "$label entryCount")
            assertEquals(want.optInt("entryWins") ?: 0, got.details?.entryWins, "$label entryWins")
            assertEquals(want.optInt("clutchWins") ?: 0, got.details?.clutchesWon, "$label clutchWins")
            assertEquals(multiKillRounds(want), got.details?.multiKills, "$label multiKills")
            assertEquals(want.optInt("utilityDamage"), got.details?.utilityDamage, "$label utilityDamage")
            assertEquals(want.optInt("enemiesFlashed"), got.details?.enemiesFlashed, "$label enemiesFlashed")
        }
    }

    @Test
    fun appSummaryEqualsTheServerTiles() {
        val summary = GameSummary.of(matches)
        assertEquals(tile("matches").toInt(), summary.matches)
        assertEquals(tile("wins").toInt(), summary.wins)
        near(tile("winRate"), summary.winRate, "winRate")
        near(tile("kd"), summary.kd, "kd")
        near(tile("adr"), summary.adr, "adr")
    }

    @Test
    fun baseSumsEqualTheServerTiles() {
        val kills = matches.sumOf { it.kills }
        val deaths = matches.sumOf { it.deaths }
        // TeamAnalytics.stats: rounds = sum of the matches' rounds (their score sum for Faceit matches), at least 1.
        val rounds = maxOf(matches.sumOf { it.details?.rounds ?: 0 }, 1)
        assertEquals(tile("kills").toInt(), kills)
        assertEquals(tile("deaths").toInt(), deaths)
        assertEquals(tile("assists").toInt(), matches.sumOf { it.assists })
        assertEquals(tile("losses").toInt(), matches.count { !it.won })
        near(tile("kpr"), kills.toDouble() / rounds, "kpr")
        near(tile("avgKR"), kills.toDouble() / rounds, "avgKR")
        near(tile("deathsPerRound"), deaths.toDouble() / rounds, "deathsPerRound")
        near(tile("avgKD"), matches.map { it.kd }.average(), "avgKD")
    }

    @Test
    fun roundTilesCountOnlyMatchesWithAScore() {
        val scored = matches.filter { it.roundsWon + it.roundsLost > 0 }
        val won = scored.sumOf { it.roundsWon }
        val total = scored.sumOf { it.details?.rounds ?: 0 }
        assertEquals(tile("roundsWon").toInt(), won)
        assertEquals(tile("rounds").toInt(), total)
        near(tile("roundWinRate"), won.toDouble() / total * 100, "roundWinRate")
        near(tile("avgRoundsWon"), won.toDouble() / scored.size, "avgRoundsWon")
        near(tile("avgRoundsLost"), (total - won).toDouble() / scored.size, "avgRoundsLost")
    }

    @Test
    fun detailTilesCountOnlyMatchesThatHaveTheField() {
        // "Has the field" is read from the server expectations, which the mapped-fields test checks against the app.
        val has = { key: String -> expected.indices.filter { expected[it].containsKey(key) } }
        val firstKills = has("firstKills")
        assertEquals(tile("firstKills").toInt(), firstKills.sumOf { matches[it].details?.firstKills ?: 0 })

        val entry = has("entryCount")
        assertEquals(
            tile("openDuelDiff").toInt(),
            entry.sumOf { 2 * (matches[it].details?.entryWins ?: 0) - (matches[it].details?.entryCount ?: 0) }
        )
        assertEquals(tile("clutchesWon").toInt(), has("clutchWins").sumOf { matches[it].details?.clutchesWon ?: 0 })

        val multi = has("multiKills")
        near(tile("avgMultikills"), multi.sumOf { matches[it].details?.multiKills ?: 0 }.toDouble() / multi.size, "avgMultikills")

        val damage = has("utilityDamage")
        val damageRounds = damage.sumOf { matches[it].details?.rounds ?: 0 }
        near(tile("udr"), damage.sumOf { matches[it].details?.utilityDamage ?: 0 }.toDouble() / damageRounds, "udr")
    }

    @Test
    fun tilesTheAppCannotComputeAreNotInTheFixture() {
        for (key in listOf("kast", "firstDeaths", "avgFlashTime", "draws", "pistolWon", "pistolRounds", "pistolWinRate")) {
            assertTrue(!tiles.containsKey(key), "$key must not be a fixture tile")
        }
    }

    private fun tile(key: String): Double = tiles.getValue(key).jsonPrimitive.double

    private fun multiKillRounds(want: JsonObject): Int =
        (want["multiKills"] as? JsonObject)?.values?.sumOf { it.jsonPrimitive.int } ?: 0

    private fun JsonObject.optInt(key: String): Int? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int

    private fun near(want: Double, got: Double, label: String) {
        if (abs(want - got) > 1e-9) fail("$label: expected $want, got $got")
    }

    companion object {
        private const val RELATIVE = "server/test/fixtures/faceit-matches.json"

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

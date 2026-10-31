package com.pulse.core.feature

import com.pulse.core.model.MindsetArchetype
import com.pulse.core.model.MindsetMath
import com.pulse.core.model.MindsetProfile
import com.pulse.core.model.MindsetTrait
import com.pulse.core.util.PulseJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Feeds the score sets of `server/test/fixtures/staff-archetype.json` (the file the staff panel's TypeScript test
 * reads too) to the app's real `MindsetProfile` / `MindsetArchetype` and compares title, summary and tags: the panel
 * must name the same archetype the player sees in the app.
 *
 * Tags are compared with the short titles of `MindsetProfile.strengths` as they are: the traits at 70 or more,
 * strongest first, two at most, the top trait when none reaches 70.
 */
class StaffArchetypeParityTest {
    private val fixture: JsonObject = loadFixture()

    @Test
    fun fixtureHasAtLeastTenCases() {
        assertTrue(cases().size >= 10)
    }

    @Test
    fun traitTextsAreTheOnesInCore() {
        val rows = fixture.getValue("traits").jsonArray.map { it.jsonObject }
        assertEquals(MindsetTrait.entries.map(::fixtureKey), rows.map { it.str("trait") })
        for (row in rows) {
            val trait = trait(row.str("trait"))
            assertEquals(row.str("title"), trait.title, row.str("trait"))
            assertEquals(row.str("shortTitle"), trait.shortTitle, row.str("trait"))
            assertEquals(row.str("summary"), MindsetArchetype.forTrait(trait).summary, row.str("trait"))
            assertEquals(row.str("title"), MindsetArchetype.forTrait(trait).title, row.str("trait"))
        }
    }

    @Test
    fun balancedTextsAreTheOnesInCore() {
        val balanced = MindsetArchetype.forTrait(null)
        val case = cases().first { it.str("name") == "spread11IsBalanced" }.getValue("expected").jsonObject
        assertEquals(case.str("title"), balanced.title)
        assertEquals(case.str("summary"), balanced.summary)
    }

    @Test
    fun archetypeFixtureCases() {
        for (case in cases()) {
            val name = case.str("name")
            val scores = case.getValue("scores")
            val expected = case.getValue("expected")
            if (scores is JsonNull) {
                // no finished run: the app shows no archetype at all
                assertTrue(expected is JsonNull, name)
                continue
            }
            val expectedObject = expected.jsonObject
            val profile = MindsetProfile(scoreMap(scores.jsonObject))
            assertEquals(expectedObject.str("title"), profile.archetype.title, "$name title")
            assertEquals(expectedObject.str("summary"), profile.archetype.summary, "$name summary")
            val tags = expectedObject.getValue("tags").jsonArray.map { it.jsonPrimitive.content }
            assertTags(name, profile, tags)
        }
    }

    @Test
    fun spreadBelowTwelveIsBalancedAndTwelveIsNot() {
        assertEquals(12, MindsetMath.BALANCED_SPREAD)
        assertEquals(70, MindsetMath.STRENGTH_THRESHOLD)
        val eleven = MindsetProfile(mapOf(
            MindsetTrait.COMPOSURE to 80, MindsetTrait.RESILIENCE to 75, MindsetTrait.FOCUS to 70,
            MindsetTrait.TEAMWORK to 72, MindsetTrait.LEADERSHIP to 69, MindsetTrait.DRIVE to 69,
        ))
        assertEquals("Ровный по всем чертам", eleven.archetype.title)
        val twelve = MindsetProfile(mapOf(
            MindsetTrait.COMPOSURE to 81, MindsetTrait.RESILIENCE to 75, MindsetTrait.FOCUS to 70,
            MindsetTrait.TEAMWORK to 72, MindsetTrait.LEADERSHIP to 69, MindsetTrait.DRIVE to 69,
        ))
        assertEquals("Хладнокровие", twelve.archetype.title)
    }

    private fun assertTags(name: String, profile: MindsetProfile, tags: List<String>) {
        // the panel shows exactly what the app shows: `strengths` (traits at 70+, two at most, else the top trait)
        assertEquals(profile.strengths.map { it.shortTitle }, tags, "$name tags")
    }

    private fun cases(): List<JsonObject> = fixture.getValue("cases").jsonArray.map { it.jsonObject }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    private fun fixtureKey(trait: MindsetTrait): String = trait.name.lowercase()

    private fun trait(key: String): MindsetTrait =
        MindsetTrait.entries.firstOrNull { fixtureKey(it) == key } ?: fail("Unknown trait $key")

    private fun scoreMap(scores: JsonObject): Map<MindsetTrait, Int> =
        scores.entries.associate { (key, value) -> trait(key) to value.jsonPrimitive.int }

    private companion object {
        const val RELATIVE = "server/test/fixtures/staff-archetype.json"

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

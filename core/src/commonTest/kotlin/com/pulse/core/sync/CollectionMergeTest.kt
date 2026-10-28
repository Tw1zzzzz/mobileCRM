package com.pulse.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollectionMergeTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun sleep(vararg ids: String, extra: String = ""): JsonElement =
        json("""{"sessions":[${ids.joinToString(",") { """{"id":"$it","start":"s-$it","end":"e-$it"}""" }}]$extra}""")

    private fun ids(document: JsonElement, field: String = "sessions"): List<String> =
        document.jsonObject.getValue(field).jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }

    @Test
    fun bothSidesAddDifferentRecords() {
        val merged = CollectionMerge.merge("sleep", sleep("a", "b"), sleep("a", "c"), mapOf("sessions" to setOf("a")), localIsNewer = true)
        assertEquals(listOf("a", "b", "c"), ids(merged))
    }

    @Test
    fun remoteDeletionIsNotResurrected() {
        val merged = CollectionMerge.merge("sleep", sleep("a", "b"), sleep("a"), mapOf("sessions" to setOf("a", "b")), localIsNewer = true)
        assertEquals(listOf("a"), ids(merged))
    }

    @Test
    fun localDeletionSurvivesConflict() {
        val merged = CollectionMerge.merge("sleep", sleep("a"), sleep("a", "b"), mapOf("sessions" to setOf("a", "b")), localIsNewer = false)
        assertEquals(listOf("a"), ids(merged))
    }

    @Test
    fun withoutBaseEveryRecordIsKept() {
        val merged = CollectionMerge.merge("sleep", sleep("a", "b"), sleep("b", "c"), emptyMap(), localIsNewer = false)
        assertEquals(listOf("a", "b", "c"), ids(merged))
    }

    @Test
    fun sameRecordLocalWins() {
        val local = json("""{"meals":[{"id":"a","kcal":500}]}""")
        val remote = json("""{"meals":[{"id":"a","kcal":300}]}""")
        val merged = CollectionMerge.merge("nutrition", local, remote, mapOf("meals" to setOf("a")), localIsNewer = false)
        val meal = merged.jsonObject.getValue("meals").jsonArray.single().jsonObject
        assertEquals(500, meal.getValue("kcal").jsonPrimitive.content.toInt())
    }

    @Test
    fun rootListCollections() {
        for (collection in listOf("calendar", "lineups")) {
            val local = json("""[{"id":"a","title":"mine"},{"id":"b"}]""")
            val remote = json("""[{"id":"a","title":"theirs"},{"id":"c"},{"id":"d"}]""")
            val merged = CollectionMerge.merge(collection, local, remote, mapOf("" to setOf("a", "d")), localIsNewer = false)
            val expected = json("""[{"id":"a","title":"mine"},{"id":"b"},{"id":"c"}]""")
            assertEquals(expected, merged, collection)
        }
    }

    @Test
    fun keyedByDate() {
        val screen = CollectionMerge.merge(
            "screen-time",
            json("""{"entries":[{"date":"2026-10-27","minutes":10},{"date":"2026-10-28","minutes":20}],"limitMinutes":300}"""),
            json("""{"entries":[{"date":"2026-10-27","minutes":99},{"date":"2026-10-26","minutes":5}],"limitMinutes":240}"""),
            mapOf("entries" to setOf("2026-10-27")),
            localIsNewer = true
        )
        val entries = screen.jsonObject.getValue("entries").jsonArray
        assertEquals(listOf("2026-10-27", "2026-10-28", "2026-10-26"), entries.map { it.jsonObject.getValue("date").jsonPrimitive.content })
        assertEquals("10", entries[0].jsonObject.getValue("minutes").jsonPrimitive.content)
        assertEquals("300", screen.jsonObject.getValue("limitMinutes").jsonPrimitive.content)

        val faceit = CollectionMerge.merge(
            "faceit",
            json("""{"snapshots":[{"date":"d1","elo":1}]}"""),
            json("""{"snapshots":[{"date":"d1","elo":2},{"date":"d2","elo":3}]}"""),
            emptyMap(),
            localIsNewer = true
        )
        assertEquals(listOf("d1", "d2"), faceit.jsonObject.getValue("snapshots").jsonArray.map { it.jsonObject.getValue("date").jsonPrimitive.content })
    }

    @Test
    fun mapsUnionLocalWins() {
        val local = json("""{"meals":[],"water":{"2026-10-27":500}}""")
        val remote = json("""{"meals":[],"water":{"2026-10-27":300,"2026-10-28":200}}""")
        val merged = CollectionMerge.merge("nutrition", local, remote, emptyMap(), localIsNewer = false)
        assertEquals(json("""{"2026-10-27":500,"2026-10-28":200}"""), merged.jsonObject.getValue("water"))
    }

    @Test
    fun ratingsKeptAsFlatPairArrayUnionLocalWins() {
        // FitnessStore writes `ratings` as [id, rating, id, rating, ...], not as an object.
        val local = json("""{"workouts":[],"ratings":["A",5,"B",4]}""")
        val remote = json("""{"workouts":[],"ratings":["A",1,"C",3]}""")
        val merged = CollectionMerge.merge("fitness-state", local, remote, emptyMap(), localIsNewer = false)
        assertEquals(json("""["A",5,"B",4,"C",3]"""), merged.jsonObject.getValue("ratings"))
    }

    @Test
    fun scalarsFollowNewerSide() {
        val local = json("""{"goal":1,"nested":{"x":1}}""")
        val remote = json("""{"goal":2,"nested":{"x":2}}""")
        assertEquals(remote, CollectionMerge.merge("game-goals", local, remote, emptyMap(), localIsNewer = false))
        assertEquals(local, CollectionMerge.merge("game-goals", local, remote, emptyMap(), localIsNewer = true))

        val localSleep = sleep("a", extra = ""","window":{"from":22}""")
        val remoteSleep = sleep("a", extra = ""","window":{"from":23}""")
        val newerRemote = CollectionMerge.merge("sleep", localSleep, remoteSleep, emptyMap(), localIsNewer = false)
        val newerLocal = CollectionMerge.merge("sleep", localSleep, remoteSleep, emptyMap(), localIsNewer = true)
        assertEquals(json("""{"from":23}"""), newerRemote.jsonObject.getValue("window"))
        assertEquals(json("""{"from":22}"""), newerLocal.jsonObject.getValue("window"))
    }

    @Test
    fun fieldAbsentOnNewerSideStaysAbsent() {
        // `activeStart` is omitted when null (explicitNulls = false): stopping the timer must not be undone.
        val local = sleep("a")
        val remote = sleep("a", extra = ""","activeStart":"2026-10-28T01:00:00Z"""")
        val merged = CollectionMerge.merge("sleep", local, remote, emptyMap(), localIsNewer = true)
        assertTrue("activeStart" !in merged.jsonObject)
    }

    @Test
    fun mergeKeepsUnknownKeys() {
        val local = sleep("a", extra = ""","localOnly":1""")
        val remote = json(
            """{"sessions":[{"id":"a","start":"s","end":"e"},{"id":"z","futureField":{"k":[1,2]}}],"remoteOnly":2}"""
        )
        val newerRemote = CollectionMerge.merge("sleep", local, remote, emptyMap(), localIsNewer = false)
        val kept = newerRemote.jsonObject.getValue("sessions").jsonArray.last().jsonObject
        assertEquals(json("""{"k":[1,2]}"""), kept.getValue("futureField"))
        assertEquals(JsonPrimitive(2), newerRemote.jsonObject.getValue("remoteOnly"))

        val newerLocal = CollectionMerge.merge("sleep", local, remote, emptyMap(), localIsNewer = true)
        assertEquals(JsonPrimitive(1), newerLocal.jsonObject.getValue("localOnly"))
    }

    @Test
    fun wrongShapeDoesNotThrow() {
        val remote = json("""{"sessions":"oops","window":{"from":23}}""")
        val toLocalNewer = CollectionMerge.merge("sleep", sleep("a"), remote, mapOf("sessions" to setOf("a")), localIsNewer = true)
        assertEquals(listOf("a"), ids(toLocalNewer))
        val toRemoteNewer = CollectionMerge.merge("sleep", sleep("a"), remote, emptyMap(), localIsNewer = false)
        assertEquals(JsonPrimitive("oops"), toRemoteNewer.jsonObject.getValue("sessions"))

        assertEquals(JsonPrimitive("x"), CollectionMerge.merge("sleep", sleep("a"), JsonPrimitive("x"), emptyMap(), localIsNewer = false))
        assertEquals(sleep("a"), CollectionMerge.merge("calendar", sleep("a"), json("[]"), emptyMap(), localIsNewer = true))
        assertEquals(json("[]"), CollectionMerge.merge("lineups", json("""{"x":1}"""), json("[]"), emptyMap(), localIsNewer = false))
        assertEquals(sleep("a"), CollectionMerge.merge("no-such-collection", sleep("a"), json("{}"), emptyMap(), localIsNewer = true))
    }

    @Test
    fun missingListOnOneSideIsEmpty() {
        val merged = CollectionMerge.merge("sleep", sleep("a"), json("{}"), emptyMap(), localIsNewer = true)
        assertEquals(listOf("a"), ids(merged))
        val deleted = CollectionMerge.merge("sleep", sleep("a"), json("{}"), mapOf("sessions" to setOf("a")), localIsNewer = true)
        assertEquals(emptyList(), ids(deleted))
    }

    @Test
    fun resultDoesNotDependOnKeyOrderOrNumberFormatting() {
        val local = json("""{"meals":[{"kcal":500,"id":"a"}],"water":{"b":2,"a":1}}""")
        val remote = json("""{"water":{"a":1.0},"meals":[{"id":"a","kcal":500.0},{"id":"c","kcal":1E2}]}""")
        val merged = CollectionMerge.merge("nutrition", local, remote, mapOf("meals" to setOf("a")), localIsNewer = true)
        assertEquals(listOf("a", "c"), ids(merged, "meals"))
        assertEquals(json("""{"id":"a","kcal":500}"""), merged.jsonObject.getValue("meals").jsonArray[0])
    }

    @Test
    fun recordsWithoutKeyDoNotThrowAndDoNotDuplicate() {
        val local = json("""{"sessions":[{"start":"x"},{"id":"a"}]}""")
        val remote = json("""{"sessions":[{"start":"x"},{"start":"y"},{"id":"a"}]}""")
        val merged = CollectionMerge.merge("sleep", local, remote, emptyMap(), localIsNewer = true)
        assertEquals(json("""[{"start":"x"},{"id":"a"},{"start":"y"}]"""), merged.jsonObject.getValue("sessions"))
    }

    @Test
    fun recordKeysListsEveryField() {
        val document = json("""{"results":[{"id":"r1"},{"id":"r2"}],"gameResults":[{"id":"g1"}]}""")
        assertEquals(
            mapOf("results" to setOf("r1", "r2"), "gameResults" to setOf("g1")),
            CollectionMerge.recordKeys("cognitive", document)
        )
        assertEquals(mapOf("" to setOf("a")), CollectionMerge.recordKeys("calendar", json("""[{"id":"a"},{"title":"no key"}]""")))
        assertEquals(mapOf("sessions" to emptySet()), CollectionMerge.recordKeys("sleep", json("""{"sessions":"oops"}""")))
        assertEquals(emptyMap(), CollectionMerge.recordKeys("game-goals", json("{}")))
        assertEquals(emptyMap(), CollectionMerge.recordKeys("unknown", json("{}")))
    }

    @Test
    fun specsMatchTheStoreDocuments() {
        assertEquals(
            setOf("sleep", "nutrition", "wellbeing", "cognitive", "calendar", "fitness-state", "screen-time", "lineups", "game-goals", "faceit", "player-survey", "body-profile"),
            CollectionMerge.specs.keys
        )
        assertEquals(mapOf("results" to "id", "gameResults" to "id"), CollectionMerge.specs.getValue("cognitive").lists)
        assertEquals(setOf("water"), CollectionMerge.specs.getValue("nutrition").maps)
        assertEquals(setOf("ratings"), CollectionMerge.specs.getValue("fitness-state").maps)
        assertEquals(mapOf("entries" to "date"), CollectionMerge.specs.getValue("screen-time").lists)
        assertEquals(mapOf("snapshots" to "date"), CollectionMerge.specs.getValue("faceit").lists)
        assertEquals(mapOf("mindset" to "id"), CollectionMerge.specs.getValue("player-survey").lists)
        assertEquals(mapOf("" to "id"), CollectionMerge.specs.getValue("calendar").lists)
        assertEquals(mapOf("" to "id"), CollectionMerge.specs.getValue("lineups").lists)
    }
}

package com.pulse.android.ui.staff

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Faceit matches of a whole team: at most three requests at a time, each player asked once for the screen's life. */
class TeamMatchesTest {
    private val links = (1..8).associate { "player$it" to "faceit$it" }

    @Test fun neverMoreThanThreeRequestsAtOnce() = runBlocking {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val matches = TeamMatches({ _ ->
            peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
            delay(20)
            running.decrementAndGet()
            emptyList()
        })
        assertEquals(links.keys, matches.of(links).keys)
        assertTrue(peak.get() in 2..3, "peak ${peak.get()}")
    }

    @Test fun eachPlayerIsAskedOnceForTheLifeOfTheHolder() = runBlocking {
        val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
        val matches = TeamMatches({ id -> asked += id; emptyList() })
        matches.of(links)
        matches.of(links)
        assertEquals(links.values.toSet(), asked.toSet())
        assertEquals(links.size, asked.size)
    }

    @Test fun aPlayerFaceitDidNotAnswerForIsMissingAndAskedAgainNextTime() = runBlocking {
        val attempts = AtomicInteger()
        val matches = TeamMatches({ id ->
            if (id == "faceit1" && attempts.incrementAndGet() == 1) null else emptyList()
        })
        assertEquals(links.keys - "player1", matches.of(links).keys)
        assertEquals(links.keys, matches.of(links).keys)
        assertEquals(2, attempts.get())
    }

    @Test fun onlyLinkedPlayersAreAsked() = runBlocking {
        val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
        val matches = TeamMatches({ id -> asked += id; emptyList() })
        matches.of(emptyMap())
        assertEquals(emptyList(), asked)
    }

    @Test fun linksComeFromTheFaceitDocumentsOnly() {
        fun doc(text: String): JsonElement = Json.parseToJsonElement(text)
        val team = com.pulse.core.staff.TeamSnapshot(
            teamName = "T",
            players = listOf("a", "b", "c", "d").map { com.pulse.core.staff.StaffPlayer(it, it) },
            documents = mapOf(
                "a" to mapOf("faceit" to doc("""{"playerId":"f-a"}""")),
                "b" to mapOf("faceit" to doc("""{"playerId":"  "}""")),
                "c" to mapOf("faceit" to doc("""{"nickname":"x"}""")),
                "d" to emptyMap()
            ),
            fetchedAt = kotlin.time.Clock.System.now()
        )
        assertEquals(mapOf("a" to "f-a"), faceitLinks(team))
    }
}

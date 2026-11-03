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
        assertEquals(links.keys, matches.of(links).matches.keys)
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
        val first = matches.of(links)
        assertEquals(links.keys - "player1", first.matches.keys)
        assertEquals(setOf("player1"), first.failed, "the result says whom Faceit did not answer for")
        val second = matches.of(links)
        assertEquals(links.keys, second.matches.keys)
        assertEquals(emptySet(), second.failed)
        assertEquals(2, attempts.get())
    }

    @Test fun everyAskReRequestsTheFailedPlayersOnly() = runBlocking {
        val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
        val matches = TeamMatches({ id -> asked += id; if (id == "faceit2") null else emptyList() })
        matches.of(links)
        asked.clear()
        val again = matches.of(links)
        assertEquals(listOf("faceit2"), asked.toList(), "answered players come from the cache, the failed one is asked again")
        assertEquals(setOf("player2"), again.failed)
    }

    @Test fun theFailedTextCountsPlayers() {
        assertEquals(null, faceitFailedText(0))
        assertEquals("Faceit не ответил для 1 игрока", faceitFailedText(1))
        assertEquals("Faceit не ответил для 2 игроков", faceitFailedText(2))
        assertEquals("Faceit не ответил для 11 игроков", faceitFailedText(11))
        assertEquals("Faceit не ответил для 21 игрока", faceitFailedText(21))
    }

    @Test fun refreshingAsksTheFailedPlayersAgain() {
        val matches = TeamMatches({ emptyList() })
        val before = matches.generation
        matches.refresh()
        assertEquals(before + 1, matches.generation)
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

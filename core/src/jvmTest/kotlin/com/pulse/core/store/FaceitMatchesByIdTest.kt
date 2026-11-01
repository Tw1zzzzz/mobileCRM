package com.pulse.core.store

import com.pulse.core.platform.MemorySecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A player's matches by Faceit player id: what the staff card draws for a player of the team (no nickname, no state change). */
class FaceitMatchesByIdTest {
    private val faceitId = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val items = """{"items":[
        {"stats":{"Kills":"20","Deaths":"10","Assists":"3","Result":"1","Score":"13 / 7","Map":"de_mirage","ADR":"88.5","Match Finished At":1760000000000}},
        {"stats":{"Kills":"9","Deaths":"18","Assists":"1","Result":"0","Score":"5 / 13","Map":"de_inferno","ADR":"55","Match Finished At":1759900000000}}]}"""

    private fun client(requests: MutableList<String>, status: HttpStatusCode = HttpStatusCode.OK, body: String = items) =
        HttpClient(MockEngine { request ->
            requests += request.url.encodedPath + " " + request.headers[HttpHeaders.Authorization]
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })

    @Test fun clientMapsTheMatchesNewestFirst() = runTest {
        val requests = mutableListOf<String>()
        val matches = FaceitClient("key", client(requests)).matches(faceitId)
        assertEquals(listOf(20, 9), matches.map { it.kills })
        assertEquals(listOf(true, false), matches.map { it.won })
        assertEquals(listOf("/data/v4/players/$faceitId/games/cs2/stats Bearer key"), requests)
    }

    @Test fun clientRefusesAnIdThatIsNotAFaceitPlayerIdWithoutAskingFaceit() = runTest {
        val requests = mutableListOf<String>()
        val faceit = FaceitClient("key", client(requests))
        for (bad in listOf("", "../players", "$faceitId/../x", "nick name", "$faceitId?x=1")) {
            val error = runCatching { faceit.matches(bad) }.exceptionOrNull()
            assertEquals(FaceitException.Kind.PLAYER_NOT_FOUND, (error as? FaceitException)?.kind, bad)
        }
        assertEquals(emptyList(), requests)
    }

    @Test fun storeLoadsMatchesWithTheBuiltInKeyAndLeavesItsOwnStateAlone() = runTest {
        var used: String? = null
        val store = GameStore(null, MemorySecretStore(), builtInKey = "app-key") { key -> used = key; FaceitClient(key, client(mutableListOf())) }
        val before = store.state.value
        assertEquals(2, store.matchesOf(faceitId)?.size)
        assertEquals("app-key", used)
        assertEquals(before, store.state.value)
    }

    @Test fun storeAnswersNullWithoutAKeyOrWhenFaceitFails() = runTest {
        val noKey = GameStore(null, MemorySecretStore()) { FaceitClient(it, client(mutableListOf())) }
        assertNull(noKey.matchesOf(faceitId))
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.TooManyRequests, HttpStatusCode.InternalServerError)) {
            val store = GameStore(null, MemorySecretStore(), builtInKey = "k") { FaceitClient(it, client(mutableListOf(), status, "{}")) }
            assertNull(store.matchesOf(faceitId), "$status")
        }
        val unreadable = GameStore(null, MemorySecretStore(), builtInKey = "k") { FaceitClient(it, client(mutableListOf(), body = "not json")) }
        assertNull(unreadable.matchesOf(faceitId))
    }

    @Test fun aProviderWithoutLookupByIdAnswersNull() = runTest {
        val store = GameStore(null, MemorySecretStore(), builtInKey = "k") {
            object : FaceitProvider { override suspend fun profile(nickname: String) = error("unused") }
        }
        assertNull(store.matchesOf(faceitId))
    }
}

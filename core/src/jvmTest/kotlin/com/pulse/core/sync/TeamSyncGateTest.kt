package com.pulse.core.sync

import com.pulse.core.PulseCore
import com.pulse.core.api.Backend
import com.pulse.core.model.CalendarEvent
import com.pulse.core.model.EventTag
import com.pulse.core.model.UserRole
import com.pulse.core.platform.JvmFileSystem
import com.pulse.core.platform.MemorySecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Who talks to the team feeds: team members of real accounts only, never demo or solo accounts. */
class TeamSyncGateTest {
    private val requests = mutableListOf<String>()
    private val backgroundJob = SupervisorJob()
    private var refuseAllWrites = false

    private fun userJson(role: String, team: String?) =
        """{"id":"6f1b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b","nickname":"Nova","email":"nova@team.gg","role":"$role","tariff":"free",""" +
            """"team":${team?.let { "\"$it\"" }},"team_id":${team?.let { "\"0c1d2e3f-4a5b-4e5f-8a9b-6f1b6a2e1c2d\"" }},"invite_code":null,"streak_days":0,"faceit_url":null}"""

    private fun core(role: String, team: String?): PulseCore {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath.removePrefix("/v1/")
            requests += "${request.method.value} $path"
            val ok = HttpStatusCode.OK
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                path == "auth/login" ->
                    respond("""{"success":true,"data":{"user":${userJson(role, team)},"access_token":"A","refresh_token":"R"},"error":null}""", ok, json)
                path == "sync" -> respond("""{"success":true,"data":[],"error":null}""", ok, json)
                path == "calendar" && request.method.value == "GET" ->
                    respond("""{"success":true,"data":{"team_id":"0c1d2e3f-4a5b-4e5f-8a9b-6f1b6a2e1c2d","events":[],"players":[]},"error":null}""", ok, json)
                path == "lineups" && request.method.value == "GET" -> respond("""{"success":true,"data":{"lineups":[]},"error":null}""", ok, json)
                (path.startsWith("calendar") || path.startsWith("lineups")) && refuseAllWrites ->
                    respond("""{"success":false,"data":null,"error":{"code":"forbidden","message":null}}""", HttpStatusCode.Forbidden, json)
                else -> respond("""{"success":true,"data":{},"error":null}""", ok, json)
            }
        }
        return PulseCore(
            JvmFileSystem(Files.createTempDirectory("pulse-team-gate").toFile()), MemorySecretStore(), HttpClient(engine),
            CoroutineScope(Dispatchers.Unconfined + backgroundJob), null, backend = Backend.Remote("https://api.test/v1/"), ui = Dispatchers.Unconfined
        )
    }

    private suspend fun settle() = backgroundJob.children.toList().joinAll()
    private fun teamFeedCalls() = requests.filter { it.endsWith(" calendar") || it.endsWith(" lineups") || "calendar/" in it || "lineups/" in it }

    @Test fun demoAccountsNeverCallTheTeamFeeds() = runTest {
        for (role in listOf(UserRole.STAFF, UserRole.TEAM_PLAYER, UserRole.SOLO_PLAYER)) {
            val core = core("teamPlayer", "Pulse")
            core.session.switchToDemo(role)
            settle()
            core.calendar.upsert(CalendarEvent(Uuid.random(), "Демо", "", Instant.parse("2026-10-27T10:00:00Z"), Instant.parse("2026-10-27T11:00:00Z"), emptyList(), EventTag.Team))
            core.syncNow()
            core.logout()
            settle()
        }
        assertEquals(emptyList(), requests, "a demo account touches no server at all")
    }

    @Test fun soloPlayersNeverCallTheTeamFeeds() = runTest {
        val core = core("soloPlayer", null)
        core.session.login("nova@team.gg", "secret123")
        settle()

        val result = core.syncNow()

        assertFalse(result.failed)
        assertTrue("GET sync" in requests, "the ordinary sync still runs")
        assertEquals(emptyList(), teamFeedCalls())
        assertTrue(result.downloaded.none { it == "calendar" || it == "team-lineups" })
    }

    @Test fun teamMembersReadBothFeeds() = runTest {
        for ((role, team) in listOf("teamPlayer" to "Pulse", "staff" to "Pulse")) {
            requests.clear()
            val core = core(role, team)
            core.session.login("nova@team.gg", "secret123")
            settle()

            core.syncNow()

            assertTrue("GET calendar" in requests, "$role reads the team calendar")
            assertTrue("GET lineups" in requests, "$role reads the team lineups")
        }
    }

    @Test fun aRefusedChangeReachesTheBellInboxOnce() = runTest {
        val core = core("staff", "Pulse")
        core.session.login("nova@team.gg", "secret123")
        settle()
        core.calendar.upsert(CalendarEvent(Uuid.random(), "Сбор", "", Instant.parse("2026-10-27T10:00:00Z"), Instant.parse("2026-10-27T11:00:00Z"), emptyList(), EventTag.Team))
        refuseAllWrites = true

        val result = core.syncNow()

        assertFalse(result.failed, "a refusal is not a failed round")
        val notice = core.inboxItems().single { it.id.startsWith("team-refused-") }
        assertEquals("Не удалось сохранить «Сбор»: нет прав", notice.title)
        assertEquals(emptyMap(), core.calendar.pendingSharedChanges())
        assertEquals(emptyList(), core.calendar.events.filter { it.title == "Сбор" })

        // the next round does not repeat the refusal
        core.syncNow()
        assertEquals(1, core.inboxItems().count { it.id.startsWith("team-refused-") })
    }
}

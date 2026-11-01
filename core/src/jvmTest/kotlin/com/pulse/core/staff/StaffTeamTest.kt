package com.pulse.core.staff

import com.pulse.core.PulseCore
import com.pulse.core.api.ApiClient
import com.pulse.core.api.AuthTokens
import com.pulse.core.api.Backend
import com.pulse.core.api.TokenStore
import com.pulse.core.platform.FileSystem
import com.pulse.core.platform.JvmFileSystem
import com.pulse.core.platform.MemorySecretStore
import com.pulse.core.storage.DataScope
import com.pulse.core.storage.SyncLedger
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val COACH = "coach@team.gg"
private const val OTHER_COACH = "coach@rivals.gg"
private const val PLAYER = "nova@team.gg"
private const val PASSWORD = "secret123"
private const val P1 = "11111111-1111-4111-8111-111111111111"
private const val P2 = "22222222-2222-4222-8222-222222222222"
private const val P3 = "33333333-3333-4333-8333-333333333333"

private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

/** The Pulse server as the staff screens see it: sign-in, one team per coach, the staff endpoints. */
private class StaffServer {
    class Member(val id: String, val nickname: String, val lastActivity: String? = null)
    class Team(
        var name: String,
        var inviteCode: String?,
        val players: MutableList<Member>,
        /** Stored documents: player id → collection → document. */
        val documents: MutableMap<String, Map<String, JsonElement>> = ConcurrentHashMap()
    )

    /** Coach e-mail → team. */
    val teams = ConcurrentHashMap<String, Team>()
    /** Sign-in answers: e-mail → (user id, role). */
    val accounts = ConcurrentHashMap<String, Pair<String, String>>().apply {
        put(COACH, "6f1b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b" to "staff")
        put(OTHER_COACH, "7a2c7b3f-2d3e-4f60-9bac-1d2e3f4a5b6c" to "staff")
        put(PLAYER, "8b3d8c40-3e4f-4071-8cbd-2e3f4a5b6c7d" to "teamPlayer")
    }
    val photos = ConcurrentHashMap<String, ByteArray>()

    /** `"GET staff/team"`, `"GET staff/players/<id>/documents"`… in arrival order. */
    val requests = ConcurrentLinkedQueue<String>()
    /** The `collections` query of every documents request. */
    val collectionQueries = ConcurrentLinkedQueue<String>()
    /** The staff endpoints fail as if there were no network (sign-in and sync still work). */
    @Volatile var staffOffline = false
    /** Every staff request is answered 401 (the refresh of the session fails too). */
    @Volatile var rejectTokens = false
    /** `GET staff/team` fails with this status (when not 200). */
    @Volatile var teamStatus = 200
    /** Documents of these players fail with 500. */
    @Volatile var failDocuments: Set<String> = emptySet()
    /** Answers every stored document, also ones that were not asked for (a misbehaving server). */
    @Volatile var returnEverything = false
    /** `GET staff/team` waits for this before answering. */
    @Volatile var teamGate: CompletableDeferred<Unit>? = null
    @Volatile var teamArrived = CompletableDeferred<Unit>()
    /** Time each documents request takes. */
    @Volatile var documentsDelayMillis = 0L
    private val active = AtomicInteger()
    val maxActive = AtomicInteger()

    fun count(request: String) = requests.count { it == request }

    fun engine() = MockEngine { request ->
        val path = request.url.encodedPath.removePrefix("/v1/")
        requests += "${request.method.value} $path"
        val email = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer token-")
        val answer: Answer = when {
            path == "auth/login" -> login((request.body as TextContent).text)
            path == "auth/refresh" -> Answer.Json(401, fail("unauthorized"))
            path == "auth/logout" -> Answer.Json(200, ok("{}"))
            path == "sync" -> Answer.Json(200, ok("[]"))
            path.startsWith("staff/") -> {
                if (staffOffline) throw IOException("offline")
                if (rejectTokens) return@MockEngine respond("""{"success":false,"data":null,"error":{"code":"unauthorized","message":null}}""", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
                staff(path, email, request.url.parameters["collections"])
            }
            else -> Answer.Json(404, fail("not_found"))
        }
        when (answer) {
            is Answer.Json -> respond(answer.body, HttpStatusCode.fromValue(answer.status), headersOf(HttpHeaders.ContentType, "application/json"))
            is Answer.Bytes -> respond(answer.bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
        }
    }

    private sealed interface Answer {
        class Json(val status: Int, val body: String) : Answer
        class Bytes(val bytes: ByteArray) : Answer
    }

    private fun login(body: String): Answer {
        val email = json(body).jsonObject.getValue("email").jsonPrimitive.content
        val (id, role) = accounts[email] ?: return Answer.Json(401, fail("invalid_credentials"))
        val user = """{"id":"$id","nickname":"Coach","email":"$email","role":"$role","tariff":"free","team":"Pulse","team_id":"0c1d2e3f-4a5b-4e5f-8a9b-6f1b6a2e1c2d",""" +
            """"invite_code":null,"streak_days":0,"faceit_url":null}"""
        return Answer.Json(200, ok("""{"user":$user,"access_token":"token-$email","refresh_token":"refresh-$email"}"""))
    }

    private suspend fun staff(path: String, email: String?, collections: String?): Answer {
        val team = email?.let(teams::get) ?: return Answer.Json(403, fail("forbidden"))
        if (path == "staff/team") {
            if (teamStatus != 200) return Answer.Json(teamStatus, fail(if (teamStatus == 429) "rate_limited" else "server_error"))
            teamArrived.complete(Unit)
            teamGate?.await()
            val players = team.players.joinToString(",") { member ->
                val activity = member.lastActivity?.let { "\"$it\"" } ?: "null"
                """{"id":"${member.id}","nickname":"${member.nickname}","last_activity":$activity}"""
            }
            val invite = team.inviteCode?.let { "\"$it\"" } ?: "null"
            return Answer.Json(200, ok("""{"team":{"name":"${team.name}","invite_code":$invite},"players":[$players]}"""))
        }
        val parts = path.removePrefix("staff/players/").split('/')
        val player = parts[0]
        if (team.players.none { it.id == player }) return Answer.Json(404, fail("not_found"))
        if (parts[1] == "photos") return photos["$player/${parts[2]}"]?.let(Answer::Bytes) ?: Answer.Json(404, fail("not_found"))
        requests += "documents $player"
        collectionQueries += collections.orEmpty()
        val now = active.incrementAndGet()
        maxActive.updateAndGet { maxOf(it, now) }
        try {
            if (documentsDelayMillis > 0) delay(documentsDelayMillis)
        } finally {
            active.decrementAndGet()
        }
        if (player in failDocuments) return Answer.Json(500, fail("server_error"))
        val asked = collections.orEmpty().split(',').toSet()
        val stored = team.documents[player].orEmpty().filterKeys { returnEverything || it in asked }
        return Answer.Json(200, ok(JsonObject(mapOf("documents" to JsonObject(stored))).toString()))
    }

    private fun ok(data: String) = """{"success":true,"data":$data,"error":null}"""
    private fun fail(code: String) = """{"success":false,"data":null,"error":{"code":"$code","message":null}}"""
}

private fun staffTeamOf(vararg members: StaffServer.Member) = StaffServer.Team("Pulse Esports", "PLAY-1234", members.toMutableList())

/** A phone whose `delete` silently does nothing, and whose writes of the team copy can be held back. */
private class AwkwardFiles(private val disk: FileSystem) : FileSystem by disk {
    @Volatile var deletes = true
    @Volatile var holdCopyWrite: CompletableDeferred<Unit>? = null
    /** The thread that last wrote the team copy. */
    @Volatile var copyWriter: String? = null
    val copyWriteStarted = CompletableDeferred<Unit>()

    override fun delete(path: String) {
        if (deletes) disk.delete(path)
    }

    override fun writeText(path: String, text: String) {
        val hold = holdCopyWrite
        if (path.endsWith(StaffTeam.FILE)) copyWriter = Thread.currentThread().name
        if (hold != null && path.endsWith(StaffTeam.FILE)) {
            copyWriteStarted.complete(Unit)
            runBlocking { hold.await() }
        }
        disk.writeText(path, text)
    }
}

/** The staff app's copy of the team: download, offline copy, one copy per account. */
class StaffTeamTest {
    private val server = StaffServer()
    private val disk = JvmFileSystem(Files.createTempDirectory("pulse-staff-team").toFile())
    private val scope = DataScope(disk).also { it.current = "COACH" }
    private var clock = Instant.parse("2026-11-01T10:00:00Z")
    private val backgroundJob = SupervisorJob()
    private val url = "https://api.test/v1/"

    private val nova = StaffServer.Member(P1, "Nova", "2026-11-01T09:30:00.000Z")
    private val raven = StaffServer.Member(P2, "Raven")
    private val sleep = json("""{"sessions":[{"id":"s1","start":"2026-10-31T22:00:00Z","end":"2026-11-01T06:00:00Z"}]}""")
    private val mood = json("""{"entries":[{"mood":4,"energy":3}]}""")

    private fun staffTeam(email: String = COACH, dataScope: DataScope = scope): StaffTeam {
        val tokens = TokenStore(MemorySecretStore()).also { it.tokens = AuthTokens("token-$email", "refresh-$email") }
        return StaffTeam(ApiClient(HttpClient(server.engine()), url, tokens), dataScope) { clock }
    }

    private fun core(backend: Backend = Backend.Remote(url)) = PulseCore(
        disk, MemorySecretStore(), HttpClient(server.engine()), CoroutineScope(Dispatchers.Unconfined + backgroundJob), null,
        backend = backend, ui = Dispatchers.Unconfined
    )

    /** First loads and refreshes run on the core's background scope. */
    private suspend fun awaitBackground() = backgroundJob.children.toList().joinAll()

    private fun StaffTeam.ready() = assertIs<StaffTeamState.Ready>(state.value)

    private fun seedTeam(email: String = COACH) {
        server.teams[email] = staffTeamOf(nova, raven).also {
            it.documents[P1] = mapOf("sleep" to sleep, "wellbeing" to mood)
            it.documents[P2] = mapOf("sleep" to sleep)
        }
    }

    @Test fun refreshDownloadsRosterAndDocuments() = runTest {
        seedTeam()
        val team = staffTeam()
        team.activate(true)
        assertEquals(StaffTeamState.Idle, team.state.value)

        team.refresh()

        val ready = team.ready()
        assertFalse(ready.stale)
        val snapshot = ready.team
        assertEquals("Pulse Esports", snapshot.teamName)
        assertEquals("PLAY-1234", snapshot.inviteCode)
        assertEquals(listOf(P1, P2), snapshot.players.map { it.id })
        assertEquals(listOf("Nova", "Raven"), snapshot.players.map { it.nickname })
        assertEquals(Instant.parse("2026-11-01T09:30:00Z"), snapshot.players[0].lastActivity)
        assertNull(snapshot.players[1].lastActivity)
        assertEquals(mapOf("sleep" to sleep, "wellbeing" to mood), snapshot.documents[P1])
        assertEquals(mapOf("sleep" to sleep), snapshot.documents[P2])
        assertEquals(clock, snapshot.fetchedAt)
        assertTrue(snapshot.missing.isEmpty())

        assertEquals("GET staff/team", server.requests.first(), "the roster comes first")
        assertEquals(listOf(P1, P2).map { "documents $it" }.toSet(), server.requests.filter { it.startsWith("documents") }.toSet())
        assertEquals(setOf("sleep,wellbeing,cognitive,nutrition,fitness-state,screen-time,faceit,player-survey"), server.collectionQueries.toSet())
        assertTrue(disk.exists("accounts/COACH/staff-team.json"))

        val documents = team.players()
        assertEquals(listOf(P1, P2), documents.map { it.id })
        assertEquals(mapOf("sleep" to sleep, "wellbeing" to mood), documents[0].docs)
    }

    @Test fun refreshWorksOnItsOwnDispatcherWhateverTheCallersThread() {
        seedTeam()
        val files = AwkwardFiles(disk)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "staff-worker") }
        try {
            val team = StaffTeam(
                ApiClient(HttpClient(server.engine()), url, TokenStore(MemorySecretStore()).also { it.tokens = AuthTokens("token-$COACH", "refresh-$COACH") }),
                DataScope(files).also { it.current = "COACH" }, worker.asCoroutineDispatcher()
            )
            team.activate(true)
            runBlocking(CoroutineName("caller")) { team.refresh() }
            assertEquals("staff-worker", files.copyWriter?.substringBefore(" @"), "the copy is encoded and written off the caller's thread")
            assertIs<StaffTeamState.Ready>(team.state.value)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun documentsOutsideTheRequestAreDropped() = runTest {
        seedTeam()
        server.teams.getValue(COACH).documents[P1] = mapOf("sleep" to sleep, "body-profile" to json("""{"weight":70}"""), "lineups" to json("[]"))
        server.returnEverything = true
        val team = staffTeam()
        team.activate(true)

        team.refresh()

        assertEquals(setOf("sleep"), team.ready().team.documents.getValue(P1).keys)
    }

    @Test fun playerWithoutDocumentsStaysInTheRoster() = runTest {
        server.teams[COACH] = staffTeamOf(nova, raven)
        val team = staffTeam()
        team.activate(true)

        team.refresh()

        assertEquals(listOf(P1, P2), team.ready().team.players.map { it.id })
        assertEquals(listOf(P1, P2), team.players().map { it.id })
        assertTrue(team.players().all { it.docs.isEmpty() })
    }

    @Test fun onePlayerFailureKeepsTheRest() = runTest {
        seedTeam()
        server.failDocuments = setOf(P2)
        val team = staffTeam()
        team.activate(true)

        team.refresh()

        val first = team.ready()
        assertFalse(first.stale, "the roster is fresh")
        assertEquals(setOf(P2), first.team.missing)
        assertEquals(listOf(P1, P2), first.team.players.map { it.id })
        assertEquals(mapOf("sleep" to sleep, "wellbeing" to mood), first.team.documents[P1])
        assertTrue(first.team.documents[P2].orEmpty().isEmpty())

        // Raven's documents come back: nothing is missing any more.
        server.failDocuments = emptySet()
        team.refresh()
        assertTrue(team.ready().team.missing.isEmpty())
        assertEquals(mapOf("sleep" to sleep), team.ready().team.documents[P2])

        // Raven fails again after being downloaded once: the earlier documents stay, the others are renewed.
        val newer = json("""{"sessions":[]}""")
        server.teams.getValue(COACH).documents[P1] = mapOf("sleep" to newer)
        server.failDocuments = setOf(P2)
        team.refresh()
        val last = team.ready().team
        assertEquals(setOf(P2), last.missing)
        assertEquals(mapOf("sleep" to sleep), last.documents[P2], "the previous copy of the failed player is kept")
        assertEquals(mapOf("sleep" to newer), last.documents[P1])
    }

    @Test fun offlineShowsCachedCopyAsStale() = runTest {
        seedTeam()
        val first = staffTeam()
        first.activate(true)
        first.refresh()
        val downloaded = first.ready().team

        // The app starts again without a network: the copy on disk is shown at once, marked as old.
        server.staffOffline = true
        clock = Instant.parse("2026-11-01T12:00:00Z")
        val second = staffTeam()
        second.activate(true)
        assertEquals(StaffTeamState.Ready(downloaded, stale = true), second.state.value)
        assertEquals(listOf(P1, P2), second.players().map { it.id })

        // A failed refresh keeps it; it never throws.
        second.refresh()
        assertEquals(StaffTeamState.Ready(downloaded, stale = true), second.state.value)
        assertEquals(Instant.parse("2026-11-01T10:00:00Z"), second.ready().team.fetchedAt, "«Обновлено» still shows the old time")

        // Back online: fresh.
        server.staffOffline = false
        second.refresh()
        assertFalse(second.ready().stale)
        assertEquals(clock, second.ready().team.fetchedAt)
    }

    @Test fun offlineWithoutACopyFailsAndRecovers() = runTest {
        seedTeam()
        server.staffOffline = true
        val team = staffTeam()
        team.activate(true)

        team.refresh()
        assertEquals(StaffTeamState.Failed, team.state.value)
        assertTrue(team.players().isEmpty())

        server.staffOffline = false
        team.refresh()
        assertFalse(team.ready().stale)
    }

    @Test fun serverRefusalIsNotAnExceptionEither() = runTest {
        // Not a staff member of any team: 403 forbidden, nothing to show.
        val team = staffTeam()
        team.activate(true)

        team.refresh()

        assertEquals(StaffTeamState.Idle, team.state.value)
    }

    @Test fun removedStaffLosesCachedTeam() = runTest {
        seedTeam()
        val team = staffTeam()
        team.activate(true)
        team.refresh()
        assertTrue(disk.exists("accounts/COACH/staff-team.json"))

        // The coach is no longer staff of a team: 403 forbidden.
        server.teams.remove(COACH)
        team.refresh()

        assertEquals(StaffTeamState.Idle, team.state.value)
        assertTrue(team.players().isEmpty())
        assertFalse(disk.exists("accounts/COACH/staff-team.json"), "nothing of the team stays on the phone")
        val restarted = staffTeam()
        restarted.activate(true)
        assertEquals(StaffTeamState.Idle, restarted.state.value)
    }

    @Test fun unauthorizedSessionLosesCachedTeam() = runTest {
        seedTeam()
        val team = staffTeam()
        team.activate(true)
        team.refresh()

        server.rejectTokens = true
        team.refresh()

        assertEquals(StaffTeamState.Idle, team.state.value)
        assertFalse(disk.exists("accounts/COACH/staff-team.json"))
    }

    @Test fun otherErrorsKeepTheCachedTeam() = runTest {
        seedTeam()
        val team = staffTeam()
        team.activate(true)
        team.refresh()
        val downloaded = team.ready().team

        for (status in listOf(500, 429)) {
            server.teamStatus = status
            team.refresh()
            assertEquals(StaffTeamState.Ready(downloaded, stale = true), team.state.value, "HTTP $status")
            assertTrue(disk.exists("accounts/COACH/staff-team.json"))
        }
    }

    @Test fun leftoverCopyIsDeletedWhenTheAccountIsNoLongerStaff() = runTest {
        disk.writeText("accounts/COACH/staff-team.json", """{"teamName":"Old"}""")
        val team = staffTeam()

        team.activate(false)

        assertEquals(StaffTeamState.Idle, team.state.value)
        assertFalse(disk.exists("accounts/COACH/staff-team.json"))
        assertTrue(server.requests.isEmpty())
    }

    @Test fun copyThatCannotBeDeletedIsEmptiedWhenAccessIsRevoked() = runTest {
        seedTeam()
        val files = AwkwardFiles(disk)
        val awkward = DataScope(files).also { it.current = "COACH" }
        val messages = ConcurrentLinkedQueue<String>()
        val team = staffTeam(dataScope = awkward).also { it.log = { message -> messages += message } }
        team.activate(true)
        team.refresh()
        assertTrue(assertNotNull(disk.readText("accounts/COACH/staff-team.json")).contains(P1))

        files.deletes = false
        server.teams.remove(COACH)
        team.refresh()

        assertEquals(StaffTeamState.Idle, team.state.value)
        assertFalse(assertNotNull(disk.readText("accounts/COACH/staff-team.json")).contains(P1), "the team's data is gone from the file")
        assertTrue(messages.any { it.contains("not deleted") }, "${messages.toList()}")
        val restarted = staffTeam(dataScope = awkward)
        restarted.activate(true)
        assertEquals(StaffTeamState.Idle, restarted.state.value)
    }

    @Test fun switchingAccountDoesNotWaitForARunningWrite() = runTest {
        seedTeam()
        val files = AwkwardFiles(disk)
        val slow = DataScope(files).also { it.current = "COACH" }
        val release = CompletableDeferred<Unit>()
        files.holdCopyWrite = release
        val team = staffTeam(dataScope = slow)
        team.activate(true)

        val refreshing = async(Dispatchers.Default) { team.refresh() }
        files.copyWriteStarted.await()
        // The refresh is inside the write of a (large) copy; the UI thread switching account must not queue behind it.
        val switching = async(Dispatchers.Default) { team.activate(false) }
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { switching.await() } }
        assertEquals(StaffTeamState.Idle, team.state.value)

        release.complete(Unit)
        refreshing.await()
        assertEquals(StaffTeamState.Idle, team.state.value, "the download of the previous account is not published")
    }

    @Test fun removedPlayerLeavesCacheOnRefresh() = runTest {
        seedTeam()
        val team = staffTeam()
        team.activate(true)
        team.refresh()
        assertEquals(listOf(P1, P2), team.ready().team.players.map { it.id })

        server.teams.getValue(COACH).players.removeAll { it.id == P2 }
        team.refresh()

        val snapshot = team.ready().team
        assertEquals(listOf(P1), snapshot.players.map { it.id })
        assertFalse(P2 in snapshot.documents, "the removed player's documents are gone")
        assertEquals(listOf(P1), team.players().map { it.id })
        // …and from the disk copy: a restart does not bring Raven back.
        val restarted = staffTeam()
        restarted.activate(true)
        assertEquals(listOf(P1), restarted.ready().team.players.map { it.id })
        assertFalse(P2 in restarted.ready().team.documents)
    }

    @Test fun anUnreadableCopyOnDiskIsIgnored() = runTest {
        disk.writeText("accounts/COACH/staff-team.json", "{ not json")
        val team = staffTeam()
        team.activate(true)
        assertEquals(StaffTeamState.Idle, team.state.value)
    }

    @Test fun downloadsAtMostThreePlayersAtOnce() = runTest {
        val members = (1..8).map { StaffServer.Member("aaaaaaaa-0000-4000-8000-00000000000$it", "P$it") }
        server.teams[COACH] = staffTeamOf(*members.toTypedArray())
        server.documentsDelayMillis = 60
        val team = staffTeam()
        team.activate(true)

        team.refresh()

        assertEquals(8, team.ready().team.players.size)
        assertEquals(8, server.requests.count { it.startsWith("documents") })
        assertTrue(server.maxActive.get() in 2..3, "parallel, but bounded: ${server.maxActive.get()}")
    }

    @Test fun concurrentRefreshesShareOneDownload() = runTest {
        seedTeam()
        val gate = CompletableDeferred<Unit>()
        server.teamGate = gate
        val team = staffTeam()
        team.activate(true)

        val first = async(start = CoroutineStart.UNDISPATCHED) { team.refresh() }
        server.teamArrived.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { team.refresh() }
        gate.complete(Unit)
        awaitAll(first, second)

        assertEquals(1, server.count("GET staff/team"))
        assertFalse(team.ready().stale)
    }

    @Test fun loadingShowsOnlyWithoutACopy() = runTest {
        seedTeam()
        val gate = CompletableDeferred<Unit>()
        server.teamGate = gate
        val team = staffTeam()
        team.activate(true)
        val first = async(start = CoroutineStart.UNDISPATCHED) { team.refresh() }
        server.teamArrived.await()
        assertEquals(StaffTeamState.Loading, team.state.value)
        gate.complete(Unit)
        first.await()

        // With a copy on screen a refresh does not blank it.
        server.teamArrived = CompletableDeferred()
        val held = CompletableDeferred<Unit>()
        server.teamGate = held
        val second = async(start = CoroutineStart.UNDISPATCHED) { team.refresh() }
        server.teamArrived.await()
        assertIs<StaffTeamState.Ready>(team.state.value)
        held.complete(Unit)
        second.await()
    }

    @Test fun photoIsDownloadedOrNull() = runTest {
        seedTeam()
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        server.photos["$P1/meal-1"] = bytes
        val team = staffTeam()
        team.activate(true)

        assertContentEquals(bytes, team.photo(P1, "meal-1"))
        assertNull(team.photo(P1, "no-such-meal"), "404: the meal has no photo")
        assertNull(team.photo("99999999-9999-4999-8999-999999999999", "meal-1"), "not in this team")
        server.staffOffline = true
        assertNull(team.photo(P1, "meal-1"), "offline")
    }

    @Test fun demoAccountNeverCallsServer() = runTest {
        seedTeam("staff@pulse.app")
        val core = core()
        core.session.login("staff@pulse.app", "pulse2026")
        awaitBackground()

        assertEquals(StaffTeamState.Idle, core.staffTeam.state.value)
        core.staffTeam.refresh()
        assertNull(core.staffTeam.photo(P1, "meal-1"))
        assertEquals(StaffTeamState.Idle, core.staffTeam.state.value)
        assertTrue(core.staffTeam.players().isEmpty())
        assertTrue(server.requests.isEmpty(), "demo accounts stay on the phone: ${server.requests.toList()}")
    }

    @Test fun localBuildNeverCallsServer() = runTest {
        seedTeam("staff@pulse.app")
        val core = core(Backend.Local)
        core.session.login("staff@pulse.app", "pulse2026")
        awaitBackground()
        core.staffTeam.refresh()
        assertEquals(StaffTeamState.Idle, core.staffTeam.state.value)

        // An unconfigured client never reaches the network even if the caller turns the repository on.
        val unconfigured = StaffTeam(ApiClient(HttpClient(server.engine()), null, TokenStore(MemorySecretStore())), scope)
        unconfigured.activate(true)
        unconfigured.refresh()
        assertEquals(StaffTeamState.Idle, unconfigured.state.value)
        assertTrue(server.requests.isEmpty(), "${server.requests.toList()}")
    }

    @Test fun playerAccountNeverAsksForTheTeam() = runTest {
        seedTeam(PLAYER)
        val core = core()
        core.session.login(PLAYER, PASSWORD)
        awaitBackground()

        core.staffTeam.refresh()

        assertEquals(StaffTeamState.Idle, core.staffTeam.state.value)
        assertTrue(server.requests.none { it.startsWith("GET staff") }, "${server.requests.toList()}")
    }

    @Test fun signingInAsStaffDownloadsTheTeam() = runTest {
        seedTeam()
        val core = core()

        core.session.login(COACH, PASSWORD)
        awaitBackground()

        val ready = assertIs<StaffTeamState.Ready>(core.staffTeam.state.value)
        assertFalse(ready.stale)
        assertEquals(listOf(P1, P2), ready.team.players.map { it.id })
        assertEquals(listOf(P1, P2), core.staffTeam.players().map { it.id })
    }

    @Test fun cacheIsPerAccount() = runTest {
        server.teams[COACH] = staffTeamOf(nova).also { it.documents[P1] = mapOf("sleep" to sleep) }
        server.teams[OTHER_COACH] = StaffServer.Team("Rivals", "RIVAL-9999", mutableListOf(StaffServer.Member(P3, "Viper")))
        val core = core()
        core.session.login(COACH, PASSWORD)
        awaitBackground()
        assertEquals(listOf(P1), core.staffTeam.players().map { it.id })
        core.session.logout()
        assertEquals(StaffTeamState.Idle, core.staffTeam.state.value, "signed out: nothing of the team is on screen")

        // The next coach never sees the previous one's players, not even while the first download runs.
        val gate = CompletableDeferred<Unit>()
        server.teamGate = gate
        server.teamArrived = CompletableDeferred()
        core.session.login(OTHER_COACH, PASSWORD)
        server.teamArrived.await()
        assertEquals(StaffTeamState.Loading, core.staffTeam.state.value)
        assertTrue(core.staffTeam.players().isEmpty())
        server.teamGate = null
        gate.complete(Unit)
        awaitBackground()
        val rivals = assertIs<StaffTeamState.Ready>(core.staffTeam.state.value).team
        assertEquals("Rivals", rivals.teamName)
        assertEquals(listOf(P3), rivals.players.map { it.id })

        // Each account keeps its own file.
        val folders = listOf("6F1B6A2E-1C2D-4E5F-8A9B-0C1D2E3F4A5B", "7A2C7B3F-2D3E-4F60-9BAC-1D2E3F4A5B6C")
        assertTrue(folders.all { disk.exists("accounts/$it/${StaffTeam.FILE}") })
        assertTrue(assertNotNull(disk.readText("accounts/${folders[0]}/${StaffTeam.FILE}")).contains(P1))
        assertFalse(assertNotNull(disk.readText("accounts/${folders[1]}/${StaffTeam.FILE}")).contains(P1))

        // Back to the first coach, offline: their own copy, marked old.
        core.session.logout()
        server.staffOffline = true
        core.session.login(COACH, PASSWORD)
        awaitBackground()
        val back = assertIs<StaffTeamState.Ready>(core.staffTeam.state.value)
        assertTrue(back.stale)
        assertEquals("Pulse Esports", back.team.teamName)
        assertEquals(listOf(P1), back.team.players.map { it.id })
    }

    @Test fun refreshOfThePreviousAccountNeverLandsInTheNext() = runTest {
        server.teams[COACH] = staffTeamOf(nova)
        server.teams[OTHER_COACH] = StaffServer.Team("Rivals", null, mutableListOf(StaffServer.Member(P3, "Viper")))
        val gate = CompletableDeferred<Unit>()
        server.teamGate = gate
        val core = core()
        core.session.login(COACH, PASSWORD)
        server.teamArrived.await()
        assertEquals(StaffTeamState.Loading, core.staffTeam.state.value)

        // The coach signs out while the download is still on its way; another one signs in.
        core.session.logout()
        server.teamGate = null
        core.session.login(OTHER_COACH, PASSWORD)
        gate.complete(Unit)
        awaitBackground()

        val ready = assertIs<StaffTeamState.Ready>(core.staffTeam.state.value)
        assertEquals(listOf(P3), ready.team.players.map { it.id })
        assertFalse(disk.exists("accounts/6F1B6A2E-1C2D-4E5F-8A9B-0C1D2E3F4A5B/${StaffTeam.FILE}"), "the first coach's download was dropped")
    }

    @Test fun cacheFileNeverSynced() = runTest {
        seedTeam()
        val core = core()
        var changes = 0
        core.onLocalChange = { changes++ }

        core.session.login(COACH, PASSWORD)
        awaitBackground()
        val folder = core.scope.folder()
        assertTrue(disk.exists("$folder/${StaffTeam.FILE}"))
        core.staffTeam.refresh()
        core.syncNow()

        assertEquals(0, changes, "writing the copy is not a change of the account's data")
        assertFalse(StaffTeam.FILE in SyncLedger(core.scope).entries(), "never marked changed")
        assertTrue(SyncLedger(core.scope).pending().isEmpty())
        assertTrue(server.requests.none { it.startsWith("PUT") || it.contains("staff-team") }, "${server.requests.toList()}")
        assertNotNull(disk.readText("$folder/${StaffTeam.FILE}"))
    }
}

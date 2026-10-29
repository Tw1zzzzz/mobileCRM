package com.pulse.core.sync

import com.pulse.core.PulseCore
import com.pulse.core.SyncStatus
import com.pulse.core.api.Backend
import com.pulse.core.model.RegistrationForm
import com.pulse.core.model.SleepSession
import com.pulse.core.model.UserRole
import com.pulse.core.platform.FileSystem
import com.pulse.core.platform.JvmFileSystem
import com.pulse.core.platform.MemorySecretStore
import com.pulse.core.model.BodyProfile
import com.pulse.core.model.FaceitProfile
import com.pulse.core.model.HealthDaySummary
import com.pulse.core.model.HealthSnapshot
import com.pulse.core.model.SleepInterval
import com.pulse.core.model.SleepStage
import com.pulse.core.model.Workout
import com.pulse.core.model.WorkoutKind
import com.pulse.core.storage.DataScope
import com.pulse.core.storage.JsonFileStorage
import com.pulse.core.store.FaceitProvider
import com.pulse.core.store.FitnessStore
import com.pulse.core.store.GameStore
import com.pulse.core.store.HealthDataProvider
import com.pulse.core.store.SleepStore
import com.pulse.core.store.WellbeingStore
import com.pulse.core.util.PulseJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The Pulse server as the core's sync triggers see it: sign-in, and the sync documents of one account. */
private class TriggerServer {
    data class Stored(val version: Int, val updatedAt: Instant, val document: JsonElement)

    val documents = ConcurrentHashMap<String, Stored>()
    /** `"GET sync"`, `"PUT sync/sleep"`… in arrival order. */
    val requests = ConcurrentLinkedQueue<String>()
    /** Every request fails as if there were no network. */
    @Volatile var offline = false
    /** Only sync requests fail (sign-in still works). */
    @Volatile var syncOffline = false
    /** Sync requests never answer. */
    @Volatile var hang = false
    /** `GET sync` waits for this before answering. */
    @Volatile var gate: CompletableDeferred<Unit>? = null
    /** Completed when a `GET sync` arrives. */
    @Volatile var listed = CompletableDeferred<Unit>()
    /** Runs before a document is sent to the phone (e.g. the app saves meanwhile). */
    @Volatile var beforeGet: ((String) -> Unit)? = null
    /** Runs before a document write is checked (e.g. another phone writes first). */
    @Volatile var beforePut: ((String) -> Unit)? = null
    /** Completed when a document write arrives. */
    val putArrived = CompletableDeferred<Unit>()
    /** Document writes wait for this before answering. */
    @Volatile var putGate: CompletableDeferred<Unit>? = null
    /** Document writes fail with 500. */
    @Volatile var failPuts = false
    private val active = AtomicInteger()
    /** Most sync requests the server was answering at the same time. */
    val maxActive = AtomicInteger()

    fun write(collection: String, document: JsonElement) {
        val version = (documents[collection]?.version ?: 0) + 1
        documents[collection] = Stored(version, Clock.System.now(), document)
    }

    fun count(request: String) = requests.count { it == request }

    fun engine() = MockEngine { request ->
        if (offline) throw IOException("offline")
        val path = request.url.encodedPath.removePrefix("/v1/")
        requests += "${request.method.value} $path"
        val (status, body) = when {
            path == "auth/login" -> 200 to ok("""{"user":$USER_JSON,"access_token":"A1","refresh_token":"R1"}""")
            path == "auth/logout" -> 200 to ok("{}")
            else -> {
                if (syncOffline) throw IOException("offline")
                if (hang) awaitCancellation()
                val now = active.incrementAndGet()
                maxActive.updateAndGet { maxOf(it, now) }
                try {
                    sync(path, request.method, request.body)
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private suspend fun sync(path: String, method: HttpMethod, body: Any): Pair<Int, String> = when {
        path == "sync" -> {
            listed.complete(Unit)
            gate?.await()
            200 to ok(JsonArray(documents.toSortedMap().map { (name, stored) ->
                buildJsonObject { put("collection", name); put("version", stored.version); put("updated_at", stored.updatedAt.toString()) }
            }))
        }
        method == HttpMethod.Get -> documents[path.removePrefix("sync/")].also { beforeGet?.invoke(path.removePrefix("sync/")) }?.let { stored ->
            200 to ok(buildJsonObject {
                put("collection", path.removePrefix("sync/"))
                put("version", stored.version)
                put("updated_at", stored.updatedAt.toString())
                put("document", stored.document)
            })
        } ?: (404 to fail("not_found"))
        else -> put(path.removePrefix("sync/"), Json.parseToJsonElement((body as TextContent).text).jsonObject)
    }

    private suspend fun put(collection: String, body: JsonObject): Pair<Int, String> {
        putArrived.complete(Unit)
        putGate?.await()
        if (failPuts) return 500 to fail("server_error")
        beforePut?.invoke(collection)
        val base = body.getValue("base_version").let { if (it is JsonNull) null else it.jsonPrimitive.content.toInt() }
        val current = documents[collection]
        if (base != current?.version) {
            return 409 to """{"success":false,"data":null,"error":{"code":"version_conflict","message":null},"meta":{"current_version":${current?.version ?: 0}}}"""
        }
        write(collection, body.getValue("document"))
        val stored = documents.getValue(collection)
        return 200 to ok(buildJsonObject { put("version", stored.version); put("updated_at", stored.updatedAt.toString()) })
    }

    private fun ok(data: Any) = """{"success":true,"data":$data,"error":null}"""
    private fun fail(code: String) = """{"success":false,"data":null,"error":{"code":"$code","message":null}}"""

    companion object {
        const val USER_JSON = """{"id":"6f1b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b","nickname":"Nova","email":"nova@team.gg","role":"teamPlayer",""" +
            """"tariff":"free","team":"Pulse","team_id":"0c1d2e3f-4a5b-4e5f-8a9b-6f1b6a2e1c2d","invite_code":null,"streak_days":0,"faceit_url":null}"""
    }
}

/** The phone's disk, which can refuse to write some files. */
private class FlakyFiles(private val disk: FileSystem) : FileSystem by disk {
    @Volatile var refuses: (String) -> Boolean = { false }

    override fun writeText(path: String, text: String) {
        if (refuses(path)) throw IOException("disk full")
        disk.writeText(path, text)
    }
}

/** When sync runs: after sign-in, on local changes, before logout; one round at a time; stores follow the merged files. */
class SyncTriggersTest {
    private val server = TriggerServer()
    private val files = FlakyFiles(JvmFileSystem(Files.createTempDirectory("pulse-sync-triggers").toFile()))
    private val backgroundJob = SupervisorJob()
    private val folder = "accounts/6F1B6A2E-1C2D-4E5F-8A9B-0C1D2E3F4A5B"

    private fun core(
        backend: Backend = Backend.Remote("https://api.test/v1/"),
        health: HealthDataProvider? = null,
        background: CoroutineScope = CoroutineScope(Dispatchers.Unconfined + backgroundJob)
    ) = PulseCore(files, MemorySecretStore(), HttpClient(server.engine()), background, health, backend = backend, ui = Dispatchers.Unconfined)

    /** Health Connect with one night of sleep and resting pulse, the same on every read. */
    private class FakeHealth(night: SleepSession) : HealthDataProvider {
        val snapshot = HealthSnapshot(
            today = HealthDaySummary(4_000, 3.0, 200, 25), weeklyActiveMinutes = emptyList(), workouts = emptyList(),
            heartRateZones = null, averageHeartRate = null, restingHeartRate = 58, hrv = 70, fetchedAt = night.end,
            sleepIntervals = listOf(SleepInterval(SleepStage.LIGHT, night.start, night.end))
        )
        override val name = "Health Connect"
        override val isAvailable = true
        override suspend fun requestAuthorization() {}
        override suspend fun snapshot(now: Instant, body: BodyProfile?) = snapshot
    }

    /** First loads and fire-and-forget requests run on the core's background scope. */
    private suspend fun awaitBackground() = backgroundJob.children.toList().joinAll()

    private suspend fun PulseCore.signIn() = session.login("nova@team.gg", "secret123")

    private fun night(day: Int) = SleepSession(
        Uuid.random(), Instant.parse("2026-10-${day - 1}T23:00:00Z"), Instant.parse("2026-10-${day}T07:00:00Z")
    )

    private fun sleepDocument(vararg sessions: SleepSession): JsonElement =
        PulseJson.encodeToJsonElement(SleepStore.Saved.serializer(), SleepStore.Saved(sessions.toList()))

    private fun serverSleepIds(): Set<String> = assertNotNull(server.documents["sleep"]).document.jsonObject.getValue("sessions").jsonArray
        .map { it.jsonObject.getValue("id").jsonPrimitive.content.lowercase() }.toSet()

    private fun PulseCore.recordNight(): SleepSession {
        val now = Clock.System.now()
        sleep.startSleep(now - 8.hours)
        return assertNotNull(sleep.stopSleep(now))
    }

    @Test fun signInTriggersFirstLoadAndReloadsStores() = runTest {
        val night = night(27)
        server.write("sleep", sleepDocument(night))
        val gate = CompletableDeferred<Unit>()
        server.gate = gate
        val core = core()
        core.signIn()
        assertEquals(SyncStatus.FirstLoad, core.syncStatus.value)
        assertTrue(core.sleep.sessions.isEmpty())

        gate.complete(Unit)
        awaitBackground()
        assertEquals(SyncStatus.Idle, core.syncStatus.value)
        assertEquals(listOf(night.id), core.sleep.sessions.map { it.id })
    }

    @Test fun firstLoadOfflineFailsSoftly() = runTest {
        val local = night(26)
        files.writeText("$folder/sleep.json", PulseJson.encodeToString(SleepStore.Saved.serializer(), SleepStore.Saved(listOf(local))))
        server.syncOffline = true
        val core = core()
        core.signIn()
        awaitBackground()
        assertEquals(SyncStatus.FirstLoadFailed, core.syncStatus.value)
        assertEquals(listOf(local.id), core.sleep.sessions.map { it.id })

        // A later round does not hide the message before it is acknowledged.
        assertTrue(core.syncNow().failed)
        assertEquals(SyncStatus.FirstLoadFailed, core.syncStatus.value)
        core.acknowledgeFirstLoadFailure()
        assertEquals(SyncStatus.Idle, core.syncStatus.value)
    }

    @Test fun slowFirstLoadHidesTheLoaderAfterTwentySeconds() = runTest {
        val night = night(27)
        server.write("sleep", sleepDocument(night))
        val gate = CompletableDeferred<Unit>()
        server.gate = gate
        // The loader's clock, apart from the test's own.
        val clock = TestCoroutineScheduler()
        val core = core(background = CoroutineScope(UnconfinedTestDispatcher(clock) + backgroundJob))
        core.signIn()
        clock.advanceTimeBy(19.seconds)
        assertEquals(SyncStatus.FirstLoad, core.syncStatus.value)

        clock.advanceTimeBy(2.seconds)
        assertEquals(SyncStatus.Running, core.syncStatus.value, "the player gets the app after 20 s, the download goes on")

        gate.complete(Unit)
        awaitBackground()
        assertEquals(SyncStatus.Idle, core.syncStatus.value)
        assertEquals(listOf(night.id), core.sleep.sessions.map { it.id })
    }

    @Test fun firstLoadEndsWithThePullAndAFailedUploadShowsNoMessage() = runTest {
        // A night from an earlier session of this account still waits to be sent.
        val local = night(26)
        val scope = DataScope(files).also { it.current = folder.substringAfterLast('/') }
        JsonFileStorage(SleepStore.FILE, SleepStore.Saved.serializer(), scope).save(SleepStore.Saved(listOf(local)))
        val putGate = CompletableDeferred<Unit>()
        server.putGate = putGate
        val core = core()
        core.signIn()

        server.putArrived.await()
        assertEquals(SyncStatus.Running, core.syncStatus.value, "the download is done: no loader while the upload runs")
        server.failPuts = true
        putGate.complete(Unit)
        awaitBackground()
        assertEquals(SyncStatus.Idle, core.syncStatus.value)
        assertEquals(listOf(local.id), core.sleep.sessions.map { it.id })
    }

    @Test fun syncFailuresReachTheLogHook() = runTest {
        val core = core()
        val messages = ConcurrentLinkedQueue<String>()
        core.log = { messages += it }
        core.signIn()
        awaitBackground()
        assertTrue(messages.isEmpty(), messages.toString())

        server.write("sleep", sleepDocument(night(27)))
        files.refuses = { it.endsWith("/sleep.json") }
        assertTrue(core.syncNow().failed)
        assertTrue(messages.any { "sleep" in it && "IOException" in it }, messages.toString())

        files.refuses = { false }
        server.syncOffline = true
        assertTrue(core.syncNow().failed)
        assertTrue(messages.any { "OFFLINE" in it }, messages.toString())
        assertTrue(messages.none { "nova@team.gg" in it || "A1" in it || "R1" in it }, messages.toString())
    }

    @Test fun saveNotifiesLocalChange() = runTest {
        server.write("sleep", sleepDocument(night(27)))
        val core = core()
        var changes = 0
        core.onLocalChange = { changes += 1 }
        core.signIn()
        awaitBackground()
        assertTrue(core.isSyncable)
        // Files written by the sync itself are not local changes.
        assertEquals(0, changes)

        core.sleep.startSleep()
        assertEquals(1, changes)
        // Files shared by all accounts (the session) are never synced.
        core.session.updateProfile("Nova Prime", "")
        assertEquals(1, changes)
    }

    @Test fun demoSaveDoesNotNotify() = runTest {
        val remote = core()
        var changes = 0
        remote.onLocalChange = { changes += 1 }
        remote.session.switchToDemo(UserRole.TEAM_PLAYER)
        assertFalse(remote.isSyncable)
        remote.sleep.startSleep()
        assertEquals(0, changes)
        assertEquals(SyncStatus.Idle, remote.syncStatus.value)
        assertEquals(SyncResult(emptyList(), emptyList(), failed = false), remote.syncNow())

        // A local build has no server: its own accounts never sync either.
        val local = core(Backend.Local)
        local.onLocalChange = { changes += 1 }
        local.session.register(RegistrationForm(nickname = "Тестер", email = "t@team.gg", password = "abcd1234", confirmPassword = "abcd1234",
            acceptedTerms = true, acceptedPrivacy = true))
        assertFalse(local.isSyncable)
        local.sleep.startSleep()
        assertEquals(0, changes)
        assertTrue(server.requests.none { it.contains("sync") })
    }

    @Test fun pendingChangesSurviveLogoutLogin() = runTest {
        val core = core()
        core.signIn()
        awaitBackground()
        server.offline = true
        val night = core.recordNight()
        core.session.logout()
        awaitBackground()
        assertNull(core.session.user.value)
        assertTrue(core.sleep.sessions.isEmpty())
        // The account folder stays on the phone.
        assertTrue(files.exists("$folder/sleep.json"))
        assertNull(server.documents["sleep"])

        server.offline = false
        core.signIn()
        awaitBackground()
        assertEquals(SyncStatus.Idle, core.syncStatus.value)
        assertEquals(setOf(night.id.toString()), serverSleepIds())
        assertEquals(listOf(night.id), core.sleep.sessions.map { it.id })
    }

    @Test fun logoutDoesNotWaitForeverOffline() = runTest {
        val core = core()
        core.signIn()
        awaitBackground()
        core.recordNight()
        server.hang = true
        val started = testScheduler.currentTime
        core.session.logout()
        assertNull(core.session.user.value)
        assertEquals(5_000, testScheduler.currentTime - started)
    }

    @Test fun storesReloadAfterAPushThatMergedAConflict() = runTest {
        val core = core()
        core.signIn()
        awaitBackground()
        val mine = core.recordNight()
        val theirs = night(25)
        // Another phone writes sleep between our pull and our upload.
        server.beforePut = { collection -> if (collection == "sleep" && server.documents["sleep"] == null) server.write("sleep", sleepDocument(theirs)) }
        val result = core.syncNow()
        assertFalse(result.failed)
        assertTrue("sleep.json" in result.downloaded)
        assertEquals(setOf(mine.id, theirs.id), core.sleep.sessions.map { it.id }.toSet())
        assertEquals(setOf(mine.id.toString(), theirs.id.toString()), serverSleepIds())
    }

    @Test fun saveDuringTheRoundKeepsWhatWasMergedBeforeIt() = runTest {
        val core = core()
        core.signIn()
        awaitBackground()
        val theirs = night(25)
        server.write("sleep", sleepDocument(theirs))
        server.write("wellbeing", PulseJson.encodeToJsonElement(WellbeingStore.Saved.serializer(), WellbeingStore.Saved()))
        // sleep.json is applied first; while wellbeing is still on its way, the player starts a night.
        server.beforeGet = { collection -> if (collection == "wellbeing") core.sleep.startSleep() }
        val result = core.syncNow()
        assertFalse(result.failed)
        assertEquals(listOf("sleep.json", "wellbeing.json"), result.downloaded)
        // The save started from the merged store: the other phone's night survives locally and on the server.
        assertEquals(listOf(theirs.id), core.sleep.sessions.map { it.id })
        assertNotNull(core.sleep.activeStart)
        assertEquals(setOf(theirs.id.toString()), serverSleepIds())
        assertNotNull(server.documents.getValue("sleep").document.jsonObject["activeStart"])
    }

    @Test fun syncReloadKeepsHealthConnectData() = runTest {
        val health = FakeHealth(night(28))
        val core = core(health = health)
        core.signIn()
        awaitBackground()
        core.fitness.connectHealth()
        val healthNights = core.sleep.state.value.healthNights
        assertTrue(healthNights.isNotEmpty())
        // Another phone added a night and a workout.
        val theirs = night(25)
        server.write("sleep", sleepDocument(theirs))
        val workout = Workout(Uuid.random(), WorkoutKind.CARDIO, "Бег", Instant.parse("2026-10-26T08:00:00Z"), 30, 250, 140)
        server.write("fitness-state", PulseJson.encodeToJsonElement(FitnessStore.Saved.serializer(), FitnessStore.Saved(listOf(workout), healthConnected = true)))

        val result = core.syncNow()
        assertFalse(result.failed)
        assertTrue(result.downloaded.containsAll(listOf("sleep.json", "fitness-state.json")))
        assertTrue(theirs.id in core.sleep.sessions.map { it.id })
        assertTrue(workout.id in core.fitness.workouts.map { it.id })
        // What only Health Connect gave stays on screen.
        assertEquals(health.snapshot, core.fitness.state.value.health)
        assertTrue(core.fitness.state.value.isHealthConnected)
        assertEquals(healthNights, core.sleep.state.value.healthNights)
        assertTrue(core.sleep.state.value.isHealthConnected)
    }

    @Test fun gameReloadFromSyncKeepsTheFaceitProfile() = runTest {
        val scope = DataScope(files).also { it.current = "6F1B6A2E-1C2D-4E5F-8A9B-0C1D2E3F4A5B" }
        val storage = JsonFileStorage(GameStore.FILE, GameStore.Saved.serializer(), scope)
        val profile = FaceitProfile("p1", "Nova", null, "ru", "EU", 3010, 10, 1500, null, emptyList(), emptyList(), Clock.System.now())
        val store = GameStore(storage, MemorySecretStore(), builtInKey = "app-key") { object : FaceitProvider {
            override suspend fun profile(nickname: String) = profile
        } }
        store.reload(isDemo = false)
        store.connect("Nova", null)
        assertEquals(profile, store.state.value.faceit)
        // Another phone's ELO points arrive in the synced file.
        val snapshots = listOf(GameStore.EloSnapshot(Instant.parse("2026-10-20T10:00:00Z"), 2950)) + store.state.value.snapshots
        storage.save(GameStore.Saved("Nova", "p1", snapshots))
        store.reloadSaved()
        assertEquals(assertNotNull(storage.load()).snapshots, store.state.value.snapshots)
        assertEquals(2, store.state.value.snapshots.size)
        assertEquals(profile, store.state.value.faceit)
    }

    @Test fun overlappingSyncNowCallsRunOneAtATime() = runTest {
        val core = core()
        core.signIn()
        awaitBackground()
        server.requests.clear()
        server.listed = CompletableDeferred()
        val gate = CompletableDeferred<Unit>()
        server.gate = gate

        val first = async(Dispatchers.Default) { core.syncNow() }
        server.listed.await()
        val second = async(Dispatchers.Default) { core.syncNow() }
        // Real time for the second round to reach the server, if it could.
        withContext(Dispatchers.Default) { delay(300) }
        assertEquals(1, server.count("GET sync"))

        gate.complete(Unit)
        assertFalse(first.await().failed)
        assertFalse(second.await().failed)
        assertEquals(2, server.count("GET sync"))
        assertEquals(1, server.maxActive.get())
    }

    @Test fun syncNowReportsFileErrorsAsAFailedRound() = runTest {
        val core = core()
        core.signIn()
        awaitBackground()
        server.write("sleep", sleepDocument(night(27)))
        files.refuses = { it.endsWith("/sleep.json") }
        assertTrue(core.syncNow().failed)
        assertEquals(SyncStatus.Idle, core.syncStatus.value)
    }

    @Test fun roundOfAPreviousAccountStopsAtLogout() = runTest {
        server.write("sleep", sleepDocument(night(27)))
        val gate = CompletableDeferred<Unit>()
        server.gate = gate
        val core = core()
        core.signIn()
        assertEquals(SyncStatus.FirstLoad, core.syncStatus.value)
        // The logout's own upload waits for the first load at most 5 s, then the account signs out and its round stops.
        core.session.logout()
        assertNull(core.session.user.value)
        assertEquals(SyncStatus.Idle, core.syncStatus.value)

        gate.complete(Unit)
        awaitBackground()
        assertEquals(SyncStatus.Idle, core.syncStatus.value)
        assertTrue(core.sleep.sessions.isEmpty())
        assertEquals(0, server.count("GET sync/sleep"))
        assertFalse(files.exists("$folder/sleep.json"))
    }
}

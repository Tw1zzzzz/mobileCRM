package com.pulse.android

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.pulse.android.platform.SyncScheduler
import com.pulse.android.platform.SyncSource
import com.pulse.android.platform.SyncWorker
import com.pulse.android.ui.TEST_USER_JSON
import com.pulse.android.ui.main.SyncStatusHost
import com.pulse.android.ui.testCore
import com.pulse.core.PulseCore
import com.pulse.core.SyncStatus
import com.pulse.core.api.Backend
import com.pulse.core.sync.SyncResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Background sync (WorkManager) and what the app shows during the first download after sign-in. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SyncSchedulerTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun initWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
    }

    /** A remote-build core signed in to a real (non-demo) account; its server never answers `GET sync` while [gate] is open. */
    private fun signedInCore(gate: CompletableDeferred<Unit>? = null, syncOffline: Boolean = false): PulseCore {
        val session = """{"user":$TEST_USER_JSON,"access_token":"A","refresh_token":"R"}"""
        val engine = MockEngine { request ->
            val data = if (request.url.encodedPath.endsWith("auth/login")) session else {
                if (syncOffline) throw IOException("offline")
                gate?.await()
                "[]"
            }
            respond("""{"success":true,"data":$data,"error":null}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val core = testCore(null, backend = Backend.Remote("https://api.test/v1/"), http = HttpClient(engine))
        runBlocking { core.session.login("tester@example.com", "test-pass-1") }
        return core
    }

    /** The process lifecycle, driven by hand. */
    private class FakeProcess : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry

        fun toForeground() = listOf(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME).forEach(registry::handleLifecycleEvent)
        fun toBackground() = listOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP).forEach(registry::handleLifecycleEvent)
    }

    private fun pendingSyncs(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncScheduler.WORK_NAME).get().filter { !it.state.isFinished }

    private fun attach(core: PulseCore, process: FakeProcess = FakeProcess()): FakeProcess {
        SyncScheduler.attach(context, core, process.lifecycle)
        return process
    }

    // — scheduling —

    @Test fun localChangeEnqueuesDelayedSync() {
        val core = signedInCore(syncOffline = true)
        attach(core).toForeground()
        requireNotNull(core.onLocalChange).invoke()

        val sync = pendingSyncs().single()
        assertEquals(10_000L, sync.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, sync.constraints.requiredNetworkType)
    }

    @Test fun anotherChangeReplacesThePendingSync() {
        val core = signedInCore(syncOffline = true)
        attach(core).toForeground()
        requireNotNull(core.onLocalChange).invoke()
        val first = pendingSyncs().single().id
        requireNotNull(core.onLocalChange).invoke()

        val second = pendingSyncs().single()
        assertTrue(second.id != first, "the edits are batched into one later sync")
        assertEquals(10_000L, second.initialDelayMillis)
    }

    @Test fun backgroundEnqueuesImmediateSync() {
        val core = signedInCore(syncOffline = true)
        val process = attach(core)
        process.toForeground()
        requireNotNull(core.onLocalChange).invoke()
        assertEquals(10_000L, pendingSyncs().single().initialDelayMillis)

        process.toBackground()

        val sync = pendingSyncs().single()
        assertEquals(0L, sync.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, sync.constraints.requiredNetworkType)
    }

    @Test fun appStartWithSyncableAccountEnqueuesImmediateSync() {
        attach(signedInCore(syncOffline = true))
        assertEquals(0L, pendingSyncs().single().initialDelayMillis)
    }

    @Test fun localAccountNeverEnqueuesSync() {
        val core = testCore(null)
        val process = attach(core)
        process.toForeground()
        process.toBackground()
        assertTrue(pendingSyncs().isEmpty())
    }

    @Test fun scheduleUsesTheGivenDelay() {
        SyncScheduler.schedule(context, 42.seconds)
        assertEquals(42_000L, pendingSyncs().single().initialDelayMillis)
    }

    // — the worker —

    private class FakeSource(var syncable: Boolean = true, var failed: Boolean = false) : SyncSource {
        var runs = 0
        var thread: String? = null
        override val isSyncable: Boolean get() = syncable
        override suspend fun syncNow(): SyncResult {
            runs += 1
            thread = Thread.currentThread().name
            return SyncResult(emptyList(), emptyList(), failed)
        }
    }

    private fun runWorker(source: SyncSource, thread: String = "fake-ui"): ListenableWorker.Result {
        val ui = Executors.newSingleThreadExecutor { Thread(it, thread) }.asCoroutineDispatcher()
        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    SyncWorker(appContext, workerParameters, { source }, ui)
            })
            .build()
        return runBlocking { worker.doWork() }
    }

    @Test fun workerRunsSyncNow() {
        val source = FakeSource()
        assertEquals(ListenableWorker.Result.success(), runWorker(source))
        assertEquals(1, source.runs)
        assertTrue(source.thread?.startsWith("fake-ui") == true, "the round runs on the UI dispatcher, where screens save")
    }

    @Test fun failedRoundRetriesWithBackoff() {
        val source = FakeSource(failed = true)
        assertEquals(ListenableWorker.Result.retry(), runWorker(source))
        assertEquals(1, source.runs)
    }

    @Test fun workerDoesNothingWithoutASyncableAccount() {
        val source = FakeSource(syncable = false)
        assertEquals(ListenableWorker.Result.success(), runWorker(source))
        assertEquals(0, source.runs)
    }

    // — the first-load screen —

    /** The core answers on the main looper, which a Robolectric test only runs when it asks for it. */
    private fun awaitStatus(core: PulseCore, expected: SyncStatus) {
        val deadline = System.currentTimeMillis() + 5_000
        while (core.syncStatus.value != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            ShadowLooper.idleMainLooper()
        }
        assertEquals(expected, core.syncStatus.value)
        rule.waitForIdle()
    }

    private fun hasText(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test fun firstLoadShowsLoader() {
        val gate = CompletableDeferred<Unit>()
        val core = signedInCore(gate)
        assertEquals(SyncStatus.FirstLoad, core.syncStatus.value)
        rule.setContent { SyncStatusHost(core) }

        rule.onNodeWithText("Загружаем ваши данные…").assertExists()
        assertTrue(!hasText("Понятно"))

        // The download finishes: the loader goes away.
        gate.complete(Unit)
        awaitStatus(core, SyncStatus.Idle)
        rule.waitUntil(5_000) { !hasText("Загружаем ваши данные…") }
    }

    @Test fun firstLoadFailureShowsDialogAndDismisses() {
        val core = signedInCore(syncOffline = true)
        rule.setContent { SyncStatusHost(core) }
        awaitStatus(core, SyncStatus.FirstLoadFailed)

        rule.onNodeWithText("Не удалось загрузить данные с сервера. Покажем то, что есть на телефоне, и попробуем ещё раз позже.").assertExists()
        assertTrue(!hasText("Загружаем ваши данные…"))
        rule.onNodeWithText("Понятно").performClick()

        awaitStatus(core, SyncStatus.Idle)
        rule.waitUntil(5_000) { !hasText("Понятно") }
    }

    @Test fun idleShowsNothing() {
        val core = testCore(null)
        rule.setContent { SyncStatusHost(core) }
        assertTrue(!hasText("Загружаем ваши данные…") && !hasText("Понятно"))
    }
}

package com.pulse.android.platform

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pulse.android.PulseApplication
import com.pulse.core.PulseCore
import com.pulse.core.sync.SyncResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Background data sync via WorkManager: one unique work `pulse-sync`. A new request replaces a waiting one, so a burst
 * of edits leaves a single sync [LOCAL_CHANGE_DELAY] after the last one; a round that is already running is never
 * cancelled — the new request follows it. It waits for a network and, when a round fails, is retried with exponential
 * backoff starting at 30 s.
 */
object SyncScheduler {
    const val WORK_NAME = "pulse-sync"
    private val LOCAL_CHANGE_DELAY = 10.seconds
    private const val BACKOFF_SECONDS = 30L

    /** Sync workers of this process inside [SyncWorker.doWork]. */
    internal val runningRounds = AtomicInteger()

    /**
     * Edits and the background trigger: APPEND_OR_REPLACE while a round runs (it finishes, the new one follows it),
     * REPLACE otherwise (the new request supersedes the waiting one). Always appending would chain one delayed round
     * per edit of a burst, since WorkManager appends to waiting work too.
     */
    private fun followUpPolicy(): ExistingWorkPolicy =
        if (runningRounds.get() > 0) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE

    /**
     * [policy] defaults to [followUpPolicy] (edits, background). The app-start call uses KEEP: a process WorkManager
     * started to run delayed or backed-off work must not cancel it.
     */
    fun schedule(context: Context, delay: Duration, policy: ExistingWorkPolicy = followUpPolicy()) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    /**
     * Wires the three triggers. A local change syncs [LOCAL_CHANGE_DELAY] later; the app going to the background
     * syncs now; so does every start of the app with a real account (without replacing work that is already pending) —
     * an app killed during its first download finishes syncing through this one.
     */
    fun attach(context: Context, core: PulseCore, process: Lifecycle = ProcessLifecycleOwner.get().lifecycle) {
        val app = context.applicationContext
        core.onLocalChange = { schedule(app, LOCAL_CHANGE_DELAY) }
        process.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && core.isSyncable) schedule(app, Duration.ZERO)
        })
        if (core.isSyncable) schedule(app, Duration.ZERO, ExistingWorkPolicy.KEEP)
    }
}

/** What a sync round needs from the core. */
internal interface SyncSource {
    val isSyncable: Boolean
    suspend fun syncNow(): SyncResult
}

private class CoreSyncSource(private val core: PulseCore) : SyncSource {
    override val isSyncable: Boolean get() = core.isSyncable
    override suspend fun syncNow(): SyncResult = core.syncNow()
}

/**
 * One sync round. It runs on the UI dispatcher: the sync ledger is only safe when rounds run where screens save.
 * A failed round asks for a retry, so WorkManager's backoff applies; with nobody to sync for there is nothing to do.
 */
class SyncWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val source: () -> SyncSource,
    private val ui: CoroutineDispatcher
) : CoroutineWorker(context, params) {
    /** Created by WorkManager. */
    constructor(context: Context, params: WorkerParameters) : this(
        context, params, { CoreSyncSource((context.applicationContext as PulseApplication).core) }, Dispatchers.Main.immediate
    )

    override suspend fun doWork(): Result {
        val core = source()
        if (!core.isSyncable) return Result.success()
        SyncScheduler.runningRounds.incrementAndGet()
        try {
            val result = withContext(ui) { core.syncNow() }
            return if (result.failed) Result.retry() else Result.success()
        } finally {
            SyncScheduler.runningRounds.decrementAndGet()
        }
    }
}

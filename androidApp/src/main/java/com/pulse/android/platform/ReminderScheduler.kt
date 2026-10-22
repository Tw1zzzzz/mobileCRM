package com.pulse.android.platform

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pulse.android.PulseApplication
import com.pulse.core.PulseCore
import com.pulse.core.feature.NotificationRules
import com.pulse.core.feature.ReminderKind
import com.pulse.core.feature.ScheduledReminder
import com.pulse.core.model.CalendarEvent
import com.pulse.core.model.GameTestPhase
import com.pulse.core.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Local reminders via WorkManager. Each reminder is a unique work named by [ReminderSync.workName] and tagged with its
 * id (`rid:<id>`) and its name (`name:<workName>`); replanning reconciles instead of cancelling everything, so a
 * reminder whose time has come still fires after a cold start. No exact alarms — a few minutes of drift is fine.
 */
object ReminderScheduler {
    private const val TAG = "reminder"
    /** The morning wake check belongs to the sleep session, not to the reminder plan — replanning leaves it alone. */
    const val SLEEP_TAG = "sleep"
    /** Demo pushes are not part of the plan either. */
    private const val DEMO_TAG = "demo"
    private const val NAME_PREFIX = "name:"

    /** Replanning reads WorkManager's database — off the main thread, one at a time. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun schedule(context: Context, reminder: ScheduledReminder, now: Instant = Clock.System.now(), tag: String = TAG) {
        val name = ReminderSync.workName(reminder)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay((reminder.at - now).inWholeMilliseconds.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(ReminderWorker.data(reminder))
            .addTag(tag)
            .addTag("rid:${reminder.id}")
            .addTag(NAME_PREFIX + name)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    }

    /** Cancels a reminder by id, whatever its time (event deleted, sleep stopped). */
    fun cancel(context: Context, id: String) = WorkManager.getInstance(context).cancelAllWorkByTag("rid:$id")

    /** After saving an event: its reminder and game tests move with it (or disappear when no longer due). */
    fun eventChanged(context: Context, core: PulseCore) = rescheduleAll(context, core)

    fun eventDeleted(context: Context, id: Uuid) {
        val key = id.toString().uppercase()
        listOf("event-$key", "game-beforeTraining-$key", "game-afterTraining-$key").forEach { cancel(context, it) }
    }

    fun rescheduleAll(context: Context, core: PulseCore) {
        val app = context.applicationContext
        scope.launch { reschedule(app, core) }
    }

    /** Brings pending reminders in line with the plan; clears them all when reminders are off or nobody is signed in. */
    suspend fun reschedule(context: Context, core: PulseCore, now: Instant = Clock.System.now()) = mutex.withLock {
        val manager = WorkManager.getInstance(context)
        val user = core.session.user.value
        if (!PulseNotifications.isEnabled(context) || user == null) {
            manager.cancelAllWorkByTag(TAG)
            return@withLock
        }
        val existing = withContext(Dispatchers.IO) { manager.getWorkInfosByTag(TAG).get() }
            .filter { !it.state.isFinished }
            .map { info -> info.tags.firstOrNull { it.startsWith(NAME_PREFIX) }?.removePrefix(NAME_PREFIX) ?: ReminderSync.legacyName(info.id.toString()) }
        val sync = ReminderSync.diff(existing, NotificationRules.upcoming(core.calendar.events, user, core.sleep.window, now), now)
        sync.toCancel.forEach { name ->
            ReminderSync.legacyWorkId(name)?.let { manager.cancelWorkById(java.util.UUID.fromString(it)) } ?: manager.cancelUniqueWork(name)
        }
        sync.toEnqueue.forEach { schedule(context, it, now) }
    }

    /** Профиль → Демо-режим: show a notification in a few seconds. */
    fun demo(context: Context, reminder: ScheduledReminder, seconds: Long = 5) =
        schedule(context, reminder.copy(id = "demo-${reminder.id}", at = Clock.System.now() + seconds.seconds), tag = DEMO_TAG)

    fun demoEvent(events: List<CalendarEvent>, now: Instant): ScheduledReminder? {
        val event = events.filter { it.start > now }.minByOrNull { it.start } ?: events.firstOrNull() ?: return null
        return ScheduledReminder("event-${event.id.toString().uppercase()}", now, event.title,
            "Через час · ${Formatters.time(event.start)}–${Formatters.time(event.end)}", ReminderKind.EVENT, event.id)
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val reminder = reminder(inputData) ?: return Result.success()
        val core = (applicationContext as PulseApplication).core
        // A wake check is pointless once sleep was stopped, «Скоро отбой» once it has started.
        val outdated = (reminder.kind == ReminderKind.WAKE_CHECK && !core.sleep.isSleeping) ||
            (reminder.kind == ReminderKind.BEDTIME && core.sleep.isSleeping)
        if (!outdated) PulseNotifications.post(applicationContext, reminder)
        // Repeating ones (bedtime, weekly) plan their next occurrence.
        if (reminder.kind == ReminderKind.BEDTIME || reminder.kind == ReminderKind.WEEKLY) {
            ReminderScheduler.reschedule(applicationContext, core)
        }
        return Result.success()
    }

    companion object {
        fun data(reminder: ScheduledReminder): Data = workDataOf(
            "id" to reminder.id, "at" to reminder.at.toEpochMilliseconds(), "title" to reminder.title, "body" to reminder.body,
            "kind" to reminder.kind.name, "eventId" to reminder.eventId?.toString(), "phase" to reminder.phase?.name
        )

        fun reminder(data: Data): ScheduledReminder? {
            val id = data.getString("id") ?: return null
            val kind = runCatching { ReminderKind.valueOf(data.getString("kind") ?: "") }.getOrNull() ?: return null
            return ScheduledReminder(
                id, Instant.fromEpochMilliseconds(data.getLong("at", 0)), data.getString("title") ?: "", data.getString("body") ?: "", kind,
                data.getString("eventId")?.let { runCatching { Uuid.parse(it) }.getOrNull() },
                data.getString("phase")?.let { runCatching { GameTestPhase.valueOf(it) }.getOrNull() }
            )
        }
    }
}

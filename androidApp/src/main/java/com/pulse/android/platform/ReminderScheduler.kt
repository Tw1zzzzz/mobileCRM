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
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Local reminders via WorkManager: one unique work per reminder id (`REPLACE`, so editing an event moves its
 * reminder). No exact alarms — a few minutes of drift is fine for these reminders.
 */
object ReminderScheduler {
    private const val TAG = "reminder"
    /** The morning wake check belongs to the sleep session, not to the reminder plan — `rescheduleAll` leaves it alone. */
    const val SLEEP_TAG = "sleep"
    /** Demo pushes are not part of the plan either — turning reminders on must not cancel one already on its way. */
    private const val DEMO_TAG = "demo"

    fun schedule(context: Context, reminder: ScheduledReminder, now: Instant = Clock.System.now(), tag: String = TAG) {
        val delay = (reminder.at - now).inWholeMilliseconds.coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(ReminderWorker.data(reminder))
            .addTag(tag)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(reminder.id, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, id: String) = WorkManager.getInstance(context).cancelUniqueWork(id)

    /** After saving an event: its reminder and game tests move with it (or disappear when no longer due). */
    fun eventChanged(context: Context, core: PulseCore) = rescheduleAll(context, core)

    fun eventDeleted(context: Context, id: Uuid) {
        val key = id.toString().uppercase()
        listOf("event-$key", "game-beforeTraining-$key", "game-afterTraining-$key").forEach { cancel(context, it) }
    }

    /** Replaces every pending reminder with the current plan; clears them when reminders are off or nobody is signed in. */
    fun rescheduleAll(context: Context, core: PulseCore, now: Instant = Clock.System.now()) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        if (!PulseNotifications.isEnabled(context)) return
        NotificationRules.upcoming(core.calendar.events, core.session.user.value, core.sleep.window, now).forEach { schedule(context, it, now) }
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
        PulseNotifications.post(applicationContext, reminder)
        // Repeating ones (bedtime, weekly) plan their next occurrence.
        if (reminder.kind == ReminderKind.BEDTIME || reminder.kind == ReminderKind.WEEKLY) {
            val core = (applicationContext as PulseApplication).core
            ReminderScheduler.rescheduleAll(applicationContext, core)
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

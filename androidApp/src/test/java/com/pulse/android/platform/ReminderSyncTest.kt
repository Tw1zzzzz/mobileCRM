package com.pulse.android.platform

import com.pulse.core.feature.ReminderKind
import com.pulse.core.feature.ScheduledReminder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ReminderSyncTest {
    private val now = Instant.fromEpochSeconds(1_800_000_000)
    private fun reminder(id: String, at: Instant, title: String = "Матч") = ScheduledReminder(id, at, title, "Через час", ReminderKind.EVENT)

    @Test fun dueWorkSurvivesReplanningAfterColdStart() {
        // The process was started to run this reminder; the new plan no longer contains it because its time has come.
        val due = ReminderSync.workName(reminder("event-A", now - 1.minutes))
        val sync = ReminderSync.diff(existing = listOf(due), planned = emptyList(), now = now)
        assertTrue(sync.toCancel.isEmpty())
        assertTrue(sync.toEnqueue.isEmpty())
    }

    @Test fun unchangedFutureReminderIsKept() {
        val planned = listOf(reminder("event-A", now + 30.minutes))
        val sync = ReminderSync.diff(listOf(ReminderSync.workName(planned[0])), planned, now)
        assertTrue(sync.toCancel.isEmpty())
        assertTrue(sync.toEnqueue.isEmpty())
    }

    @Test fun movedEventReplacesItsFutureReminder() {
        val old = ReminderSync.workName(reminder("event-A", now + 30.minutes))
        val moved = reminder("event-A", now + 90.minutes)
        val sync = ReminderSync.diff(listOf(old), listOf(moved), now)
        assertEquals(listOf(old), sync.toCancel)
        assertEquals(listOf(moved), sync.toEnqueue)
    }

    @Test fun renamedEventReplacesItsReminder() {
        val old = ReminderSync.workName(reminder("event-A", now + 30.minutes, "Матч"))
        val renamed = reminder("event-A", now + 30.minutes, "Финал")
        assertEquals(listOf(old), ReminderSync.diff(listOf(old), listOf(renamed), now).toCancel)
    }

    @Test fun futureReminderNoLongerPlannedIsCancelled() {
        val gone = ReminderSync.workName(reminder("event-B", now + 30.minutes))
        assertEquals(listOf(gone), ReminderSync.diff(listOf(gone), emptyList(), now).toCancel)
    }

    @Test fun workNameRoundTripsItsTime() {
        val at = now + 42.minutes
        assertEquals(at.epochSeconds, ReminderSync.atOf(ReminderSync.workName(reminder("sleep-bedtime", at)))?.epochSeconds)
    }

    @Test fun workFromOlderBuildsIsCancelled() {
        // Seen on the emulator after upgrading: entries scheduled before work names carried a time («weekly-report»).
        val legacy = ReminderSync.legacyName("0b6c3a52-6d0e-4bd2-9a5f-2f0d0f6b9e11")
        val planned = listOf(reminder("weekly-report", now + 3.minutes))
        val sync = ReminderSync.diff(listOf(legacy), planned, now)
        assertEquals(listOf(legacy), sync.toCancel)
        assertEquals("0b6c3a52-6d0e-4bd2-9a5f-2f0d0f6b9e11", ReminderSync.legacyWorkId(legacy))
    }
}

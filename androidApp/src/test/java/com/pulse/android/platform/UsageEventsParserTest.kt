package com.pulse.android.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class UsageEventsParserTest {
    private fun t(minute: Int) = Instant.fromEpochSeconds(1_800_000_000L + minute * 60L)

    @Test fun resumePausePairsBecomeSessions() {
        val events = listOf(
            RawUsageEvent("tg", RawUsageEvent.Type.RESUMED, t(0)),
            RawUsageEvent("tg", RawUsageEvent.Type.PAUSED, t(10)),
            RawUsageEvent("yt", RawUsageEvent.Type.RESUMED, t(12)),
            RawUsageEvent("yt", RawUsageEvent.Type.PAUSED, t(40))
        )
        val sessions = UsageEventsParser.sessions(events, now = t(60))
        assertEquals(listOf(Triple("tg", t(0), t(10)), Triple("yt", t(12), t(40))), sessions.map { Triple(it.packageName, it.start, it.end) })
    }

    @Test fun appStillOpenRunsUntilNow() {
        val sessions = UsageEventsParser.sessions(listOf(RawUsageEvent("yt", RawUsageEvent.Type.RESUMED, t(50))), now = t(60))
        assertEquals(t(60), sessions.single().end)
    }

    @Test fun pauseWithoutResumeIsIgnoredAndRepeatedResumeKeepsFirst() {
        val events = listOf(
            RawUsageEvent("tg", RawUsageEvent.Type.PAUSED, t(1)),
            RawUsageEvent("tg", RawUsageEvent.Type.RESUMED, t(5)),
            RawUsageEvent("tg", RawUsageEvent.Type.RESUMED, t(6)),
            RawUsageEvent("tg", RawUsageEvent.Type.PAUSED, t(9))
        )
        assertEquals(listOf(t(5) to t(9)), UsageEventsParser.sessions(events, t(60)).map { it.start to it.end })
    }

    @Test fun screenOffClosesEverything() {
        val events = listOf(
            RawUsageEvent("tg", RawUsageEvent.Type.RESUMED, t(0)),
            RawUsageEvent("", RawUsageEvent.Type.SCREEN_OFF, t(7)),
            RawUsageEvent("tg", RawUsageEvent.Type.PAUSED, t(30))
        )
        assertEquals(listOf(t(0) to t(7)), UsageEventsParser.sessions(events, t(60)).map { it.start to it.end })
    }

    @Test fun unlocksAreCollected() {
        val events = listOf(RawUsageEvent("", RawUsageEvent.Type.UNLOCKED, t(3)), RawUsageEvent("", RawUsageEvent.Type.UNLOCKED, t(20)))
        assertEquals(listOf(t(3), t(20)), UsageEventsParser.unlocks(events))
    }

    @Test fun resumeOfAnotherAppClosesPreviousWithoutPause() {
        // Seen on the emulator: Pulse resumed, then the launcher resumed with no Pulse pause in between.
        val events = listOf(
            RawUsageEvent("pulse", RawUsageEvent.Type.RESUMED, t(0)),
            RawUsageEvent("launcher", RawUsageEvent.Type.RESUMED, t(10)),
            RawUsageEvent("launcher", RawUsageEvent.Type.PAUSED, t(11)),
            RawUsageEvent("pulse", RawUsageEvent.Type.RESUMED, t(11))
        )
        val sessions = UsageEventsParser.sessions(events, now = t(20))
        assertEquals(listOf(Triple("pulse", t(0), t(10)), Triple("launcher", t(10), t(11)), Triple("pulse", t(11), t(20))),
            sessions.map { Triple(it.packageName, it.start, it.end) })
        // One app at a time: the total never exceeds the wall clock.
        assertEquals(20L, sessions.sumOf { (it.end - it.start).inWholeMinutes })
    }
}

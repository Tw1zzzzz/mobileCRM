package com.pulse.core.feature

import com.pulse.core.model.AppCategory
import com.pulse.core.model.at
import com.pulse.core.model.pinTestZone
import com.pulse.core.util.Tint
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScreenUsageTest {
    @BeforeTest fun zone() = pinTestZone()

    private val now = at(2026, 10, 21, 18)

    private fun session(pkg: String, category: AppCategory, start: kotlin.time.Instant, end: kotlin.time.Instant, label: String = pkg) =
        AppSession(pkg, label, category, start, end)

    @Test fun sessionsClippedToDayAndNow() {
        val sessions = listOf(
            // 23:30 → 00:45 spans midnight: 30 min yesterday, 45 min today.
            session("tg", AppCategory.SOCIAL, at(2026, 10, 20, 23, 30), at(2026, 10, 21, 0, 45)),
            // Still in the foreground at query time: counted up to now.
            session("yt", AppCategory.VIDEO, at(2026, 10, 21, 17, 40), at(2026, 10, 21, 19, 0))
        )
        val snapshot = ScreenUsageMath.build(sessions, unlocks = emptyList(), now = now, days = 7)
        assertEquals(7, snapshot.days.size)
        assertEquals(30, snapshot.days[5].minutes)
        assertEquals(45 + 20, snapshot.days[6].minutes)
        assertEquals(mapOf(AppCategory.SOCIAL to 45, AppCategory.VIDEO to 20), snapshot.days[6].categories)
    }

    @Test fun hourlyBucketsToday() {
        val snapshot = ScreenUsageMath.build(listOf(session("yt", AppCategory.VIDEO, at(2026, 10, 21, 9, 50), at(2026, 10, 21, 10, 20))), emptyList(), now)
        assertEquals(24, snapshot.hourlyToday.size)
        assertEquals(10, snapshot.hourlyToday[9])
        assertEquals(20, snapshot.hourlyToday[10])
        assertEquals(30, snapshot.hourlyToday.sum())
    }

    @Test fun appsSortedByMinutes() {
        val snapshot = ScreenUsageMath.build(listOf(
            session("tg", AppCategory.SOCIAL, at(2026, 10, 21, 8), at(2026, 10, 21, 8, 20), "Telegram"),
            session("yt", AppCategory.VIDEO, at(2026, 10, 21, 12), at(2026, 10, 21, 13), "YouTube"),
            session("tg", AppCategory.SOCIAL, at(2026, 10, 21, 14), at(2026, 10, 21, 14, 10), "Telegram"),
            session("old", AppCategory.GAMES, at(2026, 10, 20, 12), at(2026, 10, 20, 13), "Old")
        ), emptyList(), now)
        assertEquals(listOf("YouTube" to 60, "Telegram" to 30), snapshot.appsToday.map { it.name to it.minutes })
        assertEquals("play.rectangle.fill", snapshot.appsToday[0].icon)
        assertEquals(Tint.RED, snapshot.appsToday[0].tint)
        assertEquals(AppCategory.SOCIAL, snapshot.appsToday[1].category)
    }

    @Test fun pickupsAndFirstPickup() {
        val snapshot = ScreenUsageMath.build(emptyList(), listOf(at(2026, 10, 20, 23), at(2026, 10, 21, 7, 42), at(2026, 10, 21, 9)), now)
        assertEquals(2, snapshot.pickupsToday)
        assertEquals("07:42", snapshot.firstPickupToday)
        assertNull(ScreenUsageMath.build(emptyList(), emptyList(), now).firstPickupToday)
    }
}

package com.pulse.android.ui.staff

import com.pulse.core.staff.StaffTeamState
import com.pulse.core.staff.TeamSnapshot
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** When opening «Сегодня» or «Игроки» downloads the team again. */
class StaffTeamRefreshTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")

    private fun ready(age: kotlin.time.Duration, stale: Boolean = false) =
        StaffTeamState.Ready(TeamSnapshot("T", null, emptyList(), emptyMap(), now - age), stale)

    @Test fun aFreshCopyIsLeftAlone() {
        assertFalse(StaffTeamRefresh.isOld(ready(1.minutes), now))
        assertFalse(StaffTeamRefresh.isOld(ready(5.minutes), now))
    }

    @Test fun aCopyOlderThanFiveMinutesIsDownloadedAgain() {
        assertTrue(StaffTeamRefresh.isOld(ready(5.minutes + 1.seconds), now))
        assertTrue(StaffTeamRefresh.isOld(ready(2.hours, stale = true), now))
    }

    @Test fun aFailedDownloadWithoutACopyIsRetried() {
        assertTrue(StaffTeamRefresh.isOld(StaffTeamState.Failed, now))
    }

    @Test fun aRevokedAccountRetriesWhenATabOpens() {
        assertTrue(StaffTeamRefresh.isOld(StaffTeamState.NoAccess, now))
    }

    @Test fun aRunningOrNotStartedDownloadIsNotDoubled() {
        assertFalse(StaffTeamRefresh.isOld(StaffTeamState.Loading, now))
        assertFalse(StaffTeamRefresh.isOld(StaffTeamState.Idle, now))
    }

    @Test fun updatedAtShowsTheTimeOfTheDayOfTheCopyAndTheDayOtherwise() {
        val sameDay = updatedAtText(now - 10.minutes, now)
        assertTrue(Regex("Обновлено \\d{2}:\\d{2}").matches(sameDay), sameDay)
        val earlier = updatedAtText(now - 3.days, now)
        assertTrue(earlier.startsWith("Обновлено ") && ',' in earlier, earlier)
        assertEquals("Обновлено", sameDay.substringBefore(' '))
    }

    private val team = TeamSnapshot(
        "T", null, listOf("a", "b", "c").map { com.pulse.core.staff.StaffPlayer(it, "Ник-$it") }, emptyMap(), now - 10.minutes,
        missing = setOf("c", "a")
    )

    @Test fun aCompleteFreshDownloadHasNoLine() {
        assertEquals(null, staleLine(team.copy(missing = emptySet()), stale = false, now = now))
    }

    @Test fun aStaleCopyHasTheUpdatedLine() {
        assertEquals(updatedAtText(team.fetchedAt, now), staleLine(team.copy(missing = emptySet()), stale = true, now = now))
    }

    @Test fun aPartialDownloadNamesWhoDidNotLoadEvenWhenFresh() {
        val line = requireNotNull(staleLine(team, stale = false, now = now))
        assertEquals("${updatedAtText(team.fetchedAt, now)} · не загрузились: Ник-a, Ник-c", line)
    }

    @Test fun aPartialStaleCopyAddsTheNamesToTheUpdatedLine() {
        assertEquals("${updatedAtText(team.fetchedAt, now)} · не загрузились: Ник-a, Ник-c", staleLine(team, stale = true, now = now))
    }

    @Test fun missingIdsOfPlayersWhoLeftTheRosterAreNotNamed() {
        val line = requireNotNull(staleLine(team.copy(missing = setOf("zzz")), stale = true, now = now))
        assertEquals(updatedAtText(team.fetchedAt, now), line)
    }
}

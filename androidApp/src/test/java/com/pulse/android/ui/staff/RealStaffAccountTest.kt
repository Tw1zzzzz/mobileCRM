package com.pulse.android.ui.staff

import android.app.Application
import androidx.compose.ui.test.assertAny
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onSiblings
import androidx.compose.ui.test.performClick
import com.pulse.android.ui.ProvideCore
import com.pulse.android.ui.main.AppRouter
import com.pulse.android.ui.main.AppTab
import com.pulse.android.ui.main.Route
import com.pulse.android.ui.main.RouteScreen
import com.pulse.android.ui.assertAccessible
import com.pulse.android.ui.assertTextFits
import com.pulse.android.ui.StaffServerFixture
import com.pulse.android.ui.remoteStaffCore
import com.pulse.android.ui.setMain
import com.pulse.android.ui.testCore
import com.pulse.core.demo.DemoData
import com.pulse.core.model.EventTag
import com.pulse.android.ui.theme.Metrics
import com.pulse.core.feature.StaffMetrics
import com.pulse.core.model.UserRole
import com.pulse.core.staff.StaffTeamState
import com.pulse.core.staff.StaffViews
import com.pulse.core.util.Formatters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A real staff account never sees the seeded demo team, but keeps every tab and screen with an honest empty state; demo staff keep the team. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RealStaffAccountTest {
    @get:Rule val rule = createComposeRule()

    private val noData = "Пока нет данных"
    private val noDataText = "Здесь появятся показатели игроков, когда они начнут заполнять приложение."
    private val rosterTitle = "В команде пока нет игроков"
    private val rosterText = "Отправь игрокам код команды — они появятся здесь после регистрации."
    private val websiteWords = listOf("на сайте", "Открыть кабинет", "/staff", "кабинет")

    private fun assertNoDemoPlayers() {
        DemoData.players.forEach { rule.onAllNodesWithText(it.nickname, substring = true).assertCountEquals(0) }
    }

    private fun assertNoWebsitePointer() {
        websiteWords.forEach { rule.onAllNodes(hasText(it, substring = true, ignoreCase = true)).assertCountEquals(0) }
    }

    private fun assertNoData() {
        rule.onNodeWithText(noData).assertExists()
        rule.onNodeWithText(noDataText).assertExists()
        assertNoDemoPlayers()
        assertNoWebsitePointer()
    }

    @Test fun emptyTeamShowsPlayerCode() {
        val server = StaffServerFixture(inviteCode = "ABCDEF")
        rule.setMain(remoteStaffCore(server = server))
        rule.onNodeWithText("Команда Pulse Academy").assertExists()
        rule.onNodeWithText("События сегодня", ignoreCase = true).assertExists()
        rule.onNodeWithText(rosterTitle).assertExists()
        rule.onNodeWithText("Код для игроков").assertExists()
        rule.onNodeWithText("ABCDEF").assertExists()
        assertNoDemoPlayers()
        assertNoWebsitePointer()
        for (demo in listOf("ГОТОВНОСТЬ", "Требуют внимания", "Средний score", "СОН И ИГРА")) {
            rule.onAllNodesWithText(demo).assertCountEquals(0)
        }
    }

    @Test fun todayWithoutATeamCopySaysItCouldNotLoad() {
        rule.setMain(remoteStaffCore(server = StaffServerFixture(offline = true)))
        rule.onNodeWithText("Команда Pulse Academy").assertExists()
        rule.onNodeWithText("Не удалось загрузить команду").assertExists()
        rule.onNodeWithText("События сегодня", ignoreCase = true).assertExists()
        rule.onAllNodesWithText(rosterTitle).assertCountEquals(0)
        assertNoDemoPlayers()
        assertNoWebsitePointer()
    }

    @Test fun todayWeeklySummaryCardSaysNoData() {
        rule.setMain(remoteStaffCore())
        rule.onNodeWithText("Сводка команды за неделю").assertExists()
        rule.onNode(hasText("· нет данных", substring = true)).assertExists()
        rule.onAllNodes(hasText("рисков", substring = true)).assertCountEquals(0)
    }

    @Test fun allStaffHaveAllFiveTabs() {
        rule.setMain(remoteStaffCore())
        listOf("Сегодня", "Игроки", "Аналитика", "База знаний", "Профиль").forEach { rule.onNodeWithText(it).assertExists() }
    }

    @Test fun tabsDependOnRoleOnly() {
        assertEquals(listOf(AppTab.TODAY, AppTab.PLAYERS, AppTab.ANALYTICS, AppTab.LINEUPS, AppTab.PROFILE), AppTab.tabs(UserRole.STAFF))
        assertEquals(listOf(AppTab.TODAY, AppTab.STATE, AppTab.DIARY, AppTab.PROFILE), AppTab.tabs(UserRole.TEAM_PLAYER))
        assertEquals(listOf(AppTab.TODAY, AppTab.STATE, AppTab.DIARY, AppTab.PROFILE), AppTab.tabs(null))
    }

    @Test fun profileShowsZeroPlayersOfAnEmptyTeam() {
        rule.setMain(remoteStaffCore(), "profile")
        rule.onNodeWithText("игроков").assertExists()
        rule.onNodeWithText("игроков").onSiblings().assertAny(hasText("0"))
        rule.onNodeWithText("событий").assertExists()
        rule.onNodeWithText("раскидок").assertExists()
        assertNoWebsitePointer()
    }

    @Test fun rosterKeepsItsHeaderAndSegmentsAndShowsTheEmptyRoster() {
        rule.setMain(remoteStaffCore(), "players")
        rule.onAllNodesWithText("Игроки").assertCountEquals(2) // the tab and the header
        rule.onNodeWithText("Состав").assertExists()
        rule.onNodeWithText("Питание").assertExists()
        rule.onNodeWithText(rosterTitle).assertExists()
        rule.onNodeWithText(rosterText).assertExists()
        rule.onAllNodesWithText("Код для игроков").assertCountEquals(0)
        rule.onAllNodesWithText("Поиск игрока").assertCountEquals(0)
        assertNoDemoPlayers()
        assertNoWebsitePointer()
    }

    @Test fun rosterShowsTheTeamPlayerCode() {
        rule.setMain(remoteStaffCore(inviteCode = "ABCDEF"), "players")
        rule.onNodeWithText(rosterTitle).assertExists()
        rule.onNodeWithText("Код для игроков").assertExists()
        rule.onNodeWithText("ABCDEF").assertExists()
    }

    @Test fun teamFoodSegmentShowsNoData() {
        rule.setMain(remoteStaffCore(), "players")
        rule.onNodeWithText("Питание").performClick()
        rule.onNodeWithText("Состав").assertExists()
        assertNoData()
    }

    @Test fun teamAnalyticsKeepsItsHeaderAndShowsNoData() {
        rule.setMain(remoteStaffCore(), "analytics")
        rule.onNodeWithText("Состояние и игра команды").assertExists()
        rule.onNodeWithText("Команда").assertExists()
        rule.onNodeWithText("Корреляции").assertExists()
        assertNoData()
    }

    @Test fun correlationsSegmentShowsNoData() {
        rule.setMain(remoteStaffCore(), "analytics")
        rule.onNodeWithText("Корреляции").performClick()
        rule.onNodeWithText("Состояние и игра команды").assertExists()
        assertNoData()
    }

    @Test fun calendarOffersNoDemoPlayerTags() {
        rule.setMain(remoteStaffCore(), "calendar")
        rule.onNodeWithText(EventTag.Team.title).assertExists()
        assertNoDemoPlayers()
        rule.onNodeWithContentDescription("Создать событие").performClick()
        rule.onNodeWithText("Новое событие").assertExists()
        rule.onNodeWithText("Тэг").assertExists()
        assertNoDemoPlayers()
    }

    @Test fun demoStaffCalendarKeepsThePlayerTags() {
        rule.setMain(testCore(UserRole.STAFF), "calendar")
        val tag = EventTag.Player(DemoData.players[0].nickname).title
        rule.onNodeWithText(tag).assertExists()
        rule.onNodeWithContentDescription("Создать событие").performClick()
        // The filter row and the «Тэг» row of the open sheet.
        assertTrue(rule.onAllNodesWithText(tag).fetchSemanticsNodes().size >= 2)
    }

    /** A route restored after a restart (or opened by a push) on a real staff account. */
    private fun restoredRoute(route: Route) {
        rule.setContent { ProvideCore(remoteStaffCore(), AppRouter()) { RouteScreen(route) } }
    }

    @Test fun restoredPlayerRouteSaysThePlayerIsNotFound() {
        restoredRoute(Route.Player(DemoData.players[0].id))
        rule.onNodeWithText("Игрок не найден").assertExists()
        assertNoDemoPlayers()
        assertNoWebsitePointer()
    }

    @Test fun restoredPlayersRouteShowsTheEmptyRoster() {
        restoredRoute(Route.Players)
        rule.onNodeWithText(rosterTitle).assertExists()
        assertNoDemoPlayers()
        assertNoWebsitePointer()
    }

    @Test fun restoredTeamFoodRouteShowsNoData() { restoredRoute(Route.TeamFood); assertNoData() }
    @Test fun restoredTeamAnalyticsRouteShowsNoData() { restoredRoute(Route.TeamAnalytics); assertNoData() }
    @Test fun restoredTeamCorrelationsRouteShowsNoData() { restoredRoute(Route.TeamCorrelations); assertNoData() }

    @Test fun weeklyReportShowsItsHeaderAndNoData() {
        rule.setMain(remoteStaffCore(), "weekly")
        rule.onNodeWithText("Сводка команды").assertExists()
        assertNoData()
    }

    @Test fun restoredWeeklyReportRouteShowsNoData() { restoredRoute(Route.WeeklyReport); assertNoData() }

    @Test fun demoStaffKeepsTheDemoTeamAndAllTabs() {
        rule.setMain(testCore(UserRole.STAFF))
        listOf("Сегодня", "Игроки", "Аналитика", "База знаний", "Профиль").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onAllNodesWithText(noData).assertCountEquals(0)
        rule.onNodeWithText("ГОТОВНОСТЬ").assertExists()
    }

    @Test fun demoStaffRosterListsTheDemoPlayers() {
        rule.setMain(testCore(UserRole.STAFF), "players")
        rule.onNodeWithText(DemoData.players[0].nickname).assertExists()
        rule.onAllNodesWithText(rosterTitle).assertCountEquals(0)
    }

    @Test fun demoStaffAnalyticsKeepsTheTeamDashboard() {
        rule.setMain(testCore(UserRole.STAFF), "analytics")
        rule.onAllNodesWithText(noData).assertCountEquals(0)
    }

    // ---------------------------------------------------------------------- a real team

    private val tz = TimeZone.currentSystemDefault().id
    private val orion = "11111111-1111-4111-8111-111111111111"
    private val mirage = "22222222-2222-4222-8222-222222222222"
    private val vector = "33333333-3333-4333-8333-333333333333"

    /** Last night's sleep ending now, today's mood and a test today: a player with a readiness. */
    private fun fullDay(sleepHours: Int = 8): Map<String, String> {
        val now = Clock.System.now()
        val day = StaffMetrics.dayOf(now, tz)
        return mapOf(
            "sleep" to """{"sessions":[{"id":"s1","start":"${now - sleepHours.hours}","end":"$now"}]}""",
            "wellbeing" to """{"mood":[{"id":"m1","day":"${day}T00:00:00Z","slot":"morning","mood":8,"energy":7}],"balance":[]}""",
            "cognitive" to """{"results":[{"id":"c1","kind":"reaction","date":"$now","score":72,"metric":"x"}],"gameResults":[]}"""
        )
    }

    /** Three players: a rested one, one with a short night only, one who never filled anything. */
    private fun teamServer() = StaffServerFixture(
        inviteCode = "ABCDEF",
        players = linkedMapOf(orion to "Орион", mirage to "Мираж", vector to "Вектор"),
        documents = mapOf(orion to fullDay(), mirage to fullDay(sleepHours = 5).filterKeys { it == "sleep" }),
        lastActivity = mapOf(orion to Clock.System.now().minus(2.hours).toString())
    )

    private fun waitForText(text: String) {
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun team(core: com.pulse.core.PulseCore) = (core.staffTeam.state.value as StaffTeamState.Ready).team

    @Test fun realTodayShowsTeamReadiness() {
        val core = remoteStaffCore(server = teamServer())
        val overview = StaffViews.todayOverview(team(core), StaffMetrics.dayOf(Clock.System.now(), tz), tz)
        val readiness = requireNotNull(overview.teamReadiness) { "the fixture team has a readiness" }
        rule.setMain(core)
        waitForText("ГОТОВНОСТЬ")
        rule.onNodeWithText("ГОТОВНОСТЬ").assertExists()
        rule.onNodeWithText("$readiness").assertExists()
        rule.onNodeWithText("Игроков").onSiblings().assertAny(hasText("3"))
        assertTrue(rule.onAllNodesWithText("Требуют внимания", ignoreCase = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("Средний score").assertDoesNotExist()
        assertNoDemoPlayers()
        assertNoWebsitePointer()
    }

    @Test fun realTodayListsWhoNeedsAttentionWithTheReason() {
        val core = remoteStaffCore(server = teamServer())
        val overview = StaffViews.todayOverview(team(core), StaffMetrics.dayOf(Clock.System.now(), tz), tz)
        val item = overview.attention.first { it.nickname == "Мираж" }
        rule.setMain(core)
        waitForText("ГОТОВНОСТЬ")
        rule.onNodeWithText("Мираж").assertExists()
        rule.onNodeWithText(item.text).assertExists()
        rule.onNodeWithText("Игроков").onSiblings().assertAny(hasText("${overview.attention.size}"))
        rule.onNode(hasClickAction() and hasText("Мираж", substring = true)).assertHasClickAction()
    }

    @Test fun realRosterRowsFromServer() {
        val core = remoteStaffCore(server = teamServer())
        val rows = StaffViews.roster(team(core), StaffMetrics.dayOf(Clock.System.now(), tz), tz)
        rule.setMain(core, "players")
        waitForText("Орион")
        rule.onNodeWithText("Pulse Academy · 3 в составе").assertExists()
        rows.forEach { rule.onNodeWithText(it.nickname).assertExists() }
        val first = rows.first()
        rule.onNodeWithText("${requireNotNull(first.readiness)}").assertExists()
        rule.onNodeWithText(statusTitle(requireNotNull(first.status)), substring = true).assertExists()
        listOf("Сон", "Настр.", "Когн.").forEach { rule.onAllNodesWithText(it, useUnmergedTree = true).assertCountEquals(3) }
        // A value the player has no record for is «нет данных», never a zero; the player without any record is still listed.
        val gaps = rows.sumOf { row -> listOf(row.readiness, row.sleepHours, row.mood, row.cognitive).count { it == null } }
        assertTrue(gaps >= 3 + 3, "the fixture has players with gaps")
        rule.onAllNodesWithText("нет данных", useUnmergedTree = true).assertCountEquals(gaps)
        rule.onNode(hasClickAction() and hasText("Вектор", substring = true)).assertHasClickAction()
        rule.onAllNodesWithText("Поиск игрока").assertCountEquals(1)
    }

    private fun statusTitle(status: StaffMetrics.ReadinessStatus) = when (status) {
        StaffMetrics.ReadinessStatus.READY -> "Готов"
        StaffMetrics.ReadinessStatus.DOUBT -> "Под вопросом"
        StaffMetrics.ReadinessStatus.RISK -> "Риск"
    }

    @Test fun realRosterNoDemoNames() {
        rule.setMain(remoteStaffCore(server = teamServer()), "players")
        waitForText("Орион")
        rule.onNodeWithText("Орион").assertExists()
        assertNoDemoPlayers()
        assertNoWebsitePointer()
        rule.onAllNodesWithText(rosterTitle).assertCountEquals(0)
    }

    @Test fun realRosterSearchFiltersByNickname() {
        rule.setMain(remoteStaffCore(server = teamServer()), "players")
        waitForText("Орион")
        rule.onNode(hasSetTextAction()).performTextInput("ми")
        rule.onNodeWithText("Мираж").assertExists()
        rule.onAllNodesWithText("Орион").assertCountEquals(0)
        rule.onNode(hasSetTextAction()).performTextInput("zzz")
        rule.onNodeWithText("Никого не нашли").assertExists()
    }

    @Test fun realRosterShowsTheEmptyTeamWithItsCode() {
        rule.setMain(remoteStaffCore(server = StaffServerFixture(inviteCode = "QWERTY")), "players")
        rule.onNodeWithText("Pulse Academy · 0 в составе").assertExists()
        rule.onNodeWithText(rosterTitle).assertExists()
        rule.onNodeWithText("QWERTY").assertExists()
    }

    @Test fun offlineShowsUpdatedAt() {
        val server = teamServer()
        val core = remoteStaffCore(server = server)
        rule.setMain(core)
        waitForText("ГОТОВНОСТЬ")
        rule.onAllNodesWithText("Обновлено", substring = true).assertCountEquals(0)
        server.offline = true
        runBlocking { core.staffTeam.refresh() }
        val fetchedAt = team(core).fetchedAt
        waitForText("Обновлено ${Formatters.time(fetchedAt)}")
        rule.onNodeWithText("Мираж").assertExists()
        assertNoDemoPlayers()
    }

    @Test fun offlineRosterKeepsThePlayersAndSaysWhenItWasUpdated() {
        val server = teamServer()
        val core = remoteStaffCore(server = server)
        server.offline = true
        runBlocking { core.staffTeam.refresh() }
        rule.setMain(core, "players")
        waitForText("Вектор")
        rule.onNodeWithText("Обновлено ${Formatters.time(team(core).fetchedAt)}").assertExists()
        rule.onNodeWithText("Вектор").assertExists()
        rule.onNodeWithText("Pulse Academy · 3 в составе").assertExists()
    }

    @Test fun firstDownloadShowsProgress() {
        val gate = CompletableDeferred<Unit>()
        val core = remoteStaffCore(server = teamServer().also { it.gate = gate }, settle = false)
        runBlocking { withTimeout(10_000) { core.staffTeam.state.first { it == StaffTeamState.Loading } } }
        rule.setMain(core)
        rule.onNodeWithText("Загружаем команду…").assertExists()
        rule.onAllNodesWithText(rosterTitle).assertCountEquals(0)
        gate.complete(Unit)
        waitForText("ГОТОВНОСТЬ")
        rule.onAllNodesWithText("Загружаем команду…").assertCountEquals(0)
    }

    @Test fun openingTheTabRetriesAFailedDownload() {
        val server = teamServer().also { it.offline = true }
        val core = remoteStaffCore(server = server)
        assertEquals(StaffTeamState.Failed, core.staffTeam.state.value)
        server.offline = false
        rule.setMain(core, "players")
        waitForText("Вектор")
        rule.onAllNodesWithText("Не удалось загрузить команду").assertCountEquals(0)
    }

    @Test fun retryButtonDownloadsAgain() {
        val server = teamServer().also { it.offline = true }
        val core = remoteStaffCore(server = server)
        rule.setMain(core, "players")
        rule.onNodeWithText("Не удалось загрузить команду").assertExists()
        server.offline = false
        rule.onNodeWithText("Повторить").performClick()
        waitForText("Вектор")
    }

    @Test fun profileCountsRealPlayers() {
        rule.setMain(remoteStaffCore(server = teamServer()), "profile")
        rule.onNodeWithText("игроков").onSiblings().assertAny(hasText("3"))
    }

    @Test fun profileShowsADashWhenTheTeamIsUnknown() {
        rule.setMain(remoteStaffCore(server = StaffServerFixture(offline = true)), "profile")
        rule.onNodeWithText("игроков").onSiblings().assertAny(hasText("—"))
    }

    @Test fun realTeamScreensFitAndAreAccessible() {
        rule.setMain(remoteStaffCore(server = teamServer()), "players")
        waitForText("Орион")
        rule.waitForIdle()
        rule.assertTextFits()
        rule.assertAccessible()
    }

    @Test fun realTeamRosterFitsAtTheLargestFont() {
        rule.setMain(remoteStaffCore(server = teamServer()), "players", fontScale = Metrics.maxFontScale)
        waitForText("Орион")
        rule.waitForIdle()
        rule.assertTextFits()
    }

    @Test fun realTodayFitsAtTheLargestFontAndIsAccessible() {
        rule.setMain(remoteStaffCore(server = teamServer()), fontScale = Metrics.maxFontScale)
        waitForText("ГОТОВНОСТЬ")
        rule.waitForIdle()
        rule.assertTextFits()
        rule.assertAccessible()
    }

    @Test fun emptyStatesFitAndAreAccessible() {
        rule.setMain(remoteStaffCore(inviteCode = "ABCDEF"), "players")
        rule.waitForIdle()
        rule.assertTextFits()
        rule.assertAccessible()
    }
}

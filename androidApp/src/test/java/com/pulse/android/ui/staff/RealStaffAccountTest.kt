package com.pulse.android.ui.staff

import android.app.Application
import androidx.compose.ui.test.assertAny
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
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
import com.pulse.android.ui.remoteStaffCore
import com.pulse.android.ui.setMain
import com.pulse.android.ui.testCore
import com.pulse.core.demo.DemoData
import com.pulse.core.model.EventTag
import com.pulse.core.model.UserRole
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

    @Test fun todayKeepsItsHeaderEventsAndShowsNoDataInPlaceOfTheReadiness() {
        rule.setMain(remoteStaffCore())
        rule.onNodeWithText("Команда Pulse Academy").assertExists()
        rule.onNodeWithText("События сегодня", ignoreCase = true).assertExists()
        assertNoData()
        for (demo in listOf("ГОТОВНОСТЬ", "Требуют внимания", "Средний score", "СОН И ИГРА")) {
            rule.onAllNodesWithText(demo).assertCountEquals(0)
        }
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

    @Test fun profileShowsZeroPlayers() {
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

    @Test fun emptyStatesFitAndAreAccessible() {
        rule.setMain(remoteStaffCore(inviteCode = "ABCDEF"), "players")
        rule.waitForIdle()
        rule.assertTextFits()
        rule.assertAccessible()
    }
}

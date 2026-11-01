package com.pulse.android.ui.staff

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A real staff account never sees the seeded demo team; demo staff keep it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RealStaffAccountTest {
    @get:Rule val rule = createComposeRule()

    private val emptyTitle = "Данные команды — на сайте"
    private val emptyText = "Состав, готовность, питание и статистику игроков смотри в кабинете стаффа на сайте — раздел /staff. " +
        "Вход с той же почтой и паролем, что в приложении."

    private fun assertNoDemoPlayers() {
        DemoData.players.forEach { rule.onAllNodesWithText(it.nickname, substring = true).assertCountEquals(0) }
    }

    @Test fun todayShowsTheEmptyStateInsteadOfDemoTeam() {
        rule.setMain(remoteStaffCore())
        rule.onNodeWithText(emptyTitle).assertExists()
        rule.onNodeWithText(emptyText).assertExists()
        rule.onNodeWithText("События сегодня", ignoreCase = true).assertExists()
        for (demo in listOf("ГОТОВНОСТЬ", "Требуют внимания", "Средний score", "Игроков", "СОН И ИГРА")) {
            rule.onAllNodesWithText(demo).assertCountEquals(0)
        }
        assertNoDemoPlayers()
    }

    @Test fun playersAndAnalyticsTabsAreHidden() {
        rule.setMain(remoteStaffCore())
        listOf("Сегодня", "База знаний", "Профиль").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onAllNodesWithText("Игроки").assertCountEquals(0)
        rule.onAllNodesWithText("Аналитика").assertCountEquals(0)
    }

    @Test fun tabsDependOnRoleAndDemo() {
        assertEquals(listOf(AppTab.TODAY, AppTab.PLAYERS, AppTab.ANALYTICS, AppTab.LINEUPS, AppTab.PROFILE), AppTab.tabs(UserRole.STAFF, showsTeamDemo = true))
        assertEquals(listOf(AppTab.TODAY, AppTab.LINEUPS, AppTab.PROFILE), AppTab.tabs(UserRole.STAFF, showsTeamDemo = false))
        assertEquals(listOf(AppTab.TODAY, AppTab.STATE, AppTab.DIARY, AppTab.PROFILE), AppTab.tabs(UserRole.TEAM_PLAYER, showsTeamDemo = false))
        assertEquals(listOf(AppTab.TODAY, AppTab.STATE, AppTab.DIARY, AppTab.PROFILE), AppTab.tabs(null, showsTeamDemo = false))
    }

    @Test fun profileHasNoPlayersTile() {
        rule.setMain(remoteStaffCore(), "profile")
        rule.onAllNodesWithText("игроков").assertCountEquals(0)
        rule.onNodeWithText("событий").assertExists()
        rule.onNodeWithText("раскидок").assertExists()
    }

    @Test fun debugRoutesToHiddenTabsFallBackToToday() {
        rule.setMain(remoteStaffCore(), "players")
        // Only Сегодня has «События сегодня»; the gated Игроки tab would show the empty state under a «Игроки» header.
        rule.onNodeWithText("События сегодня", ignoreCase = true).assertExists()
        rule.onAllNodesWithText("Состав").assertCountEquals(0)
        rule.onAllNodesWithText("Питание").assertCountEquals(0)
        rule.onAllNodesWithText("Поиск игрока").assertCountEquals(0)
        assertNoDemoPlayers()
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
    private fun assertRestoredRouteIsEmpty(route: Route) {
        rule.setContent { ProvideCore(remoteStaffCore(), AppRouter()) { RouteScreen(route) } }
        rule.onNodeWithText(emptyTitle).assertExists()
        assertNoDemoPlayers()
    }

    @Test fun restoredPlayerRouteShowsTheEmptyState() = assertRestoredRouteIsEmpty(Route.Player(DemoData.players[0].id))
    @Test fun restoredPlayersRouteShowsTheEmptyState() = assertRestoredRouteIsEmpty(Route.Players)
    @Test fun restoredTeamFoodRouteShowsTheEmptyState() = assertRestoredRouteIsEmpty(Route.TeamFood)
    @Test fun restoredWeeklyReportRouteShowsTheEmptyState() = assertRestoredRouteIsEmpty(Route.WeeklyReport)
    @Test fun restoredTeamAnalyticsRouteShowsTheEmptyState() = assertRestoredRouteIsEmpty(Route.TeamAnalytics)
    @Test fun restoredTeamCorrelationsRouteShowsTheEmptyState() = assertRestoredRouteIsEmpty(Route.TeamCorrelations)

    @Test fun weeklyPushTargetDoesNotCrash() {
        rule.setMain(remoteStaffCore(), "weekly")
        rule.onNodeWithText(emptyTitle).assertExists()
    }

    @Test fun demoStaffKeepsTheDemoTeamAndAllTabs() {
        rule.setMain(testCore(UserRole.STAFF))
        listOf("Сегодня", "Игроки", "Аналитика", "База знаний", "Профиль").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onAllNodesWithText(emptyTitle).assertCountEquals(0)
        rule.onNodeWithText("ГОТОВНОСТЬ").assertExists()
    }

    @Test fun demoStaffRosterListsTheDemoPlayers() {
        rule.setMain(testCore(UserRole.STAFF), "players")
        rule.onNodeWithText(DemoData.players[0].nickname).assertExists()
        rule.onAllNodesWithText(emptyTitle).assertCountEquals(0)
    }

    @Test fun cabinetUrlDropsTheApiVersion() {
        assertEquals("https://api.example.com/staff", staffCabinetUrl("https://api.example.com/v1/"))
        assertEquals("https://api.example.com/staff", staffCabinetUrl("https://api.example.com/v1"))
        assertEquals("https://api.example.com/staff", staffCabinetUrl(" https://api.example.com "))
        assertNull(staffCabinetUrl(""))
    }

    @Test fun emptyStateOpensTheCabinetOnlyWhenTheServerIsKnown() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        rule.setContent { CompositionLocalProvider(LocalUriHandler provides handler) { StaffTeamEmptyState("https://api.example.com/v1/") } }
        rule.onNodeWithText("Открыть кабинет").performClick()
        assertEquals(listOf("https://api.example.com/staff"), opened)
    }

    @Test fun emptyStateFitsAndIsAccessible() {
        rule.setContent { StaffTeamEmptyState("https://api.example.com/v1/") }
        rule.assertTextFits()
        rule.assertAccessible()
    }

    @Test fun localBuildEmptyStateHasNoButton() {
        rule.setContent { StaffTeamEmptyState("") }
        rule.onNodeWithText(emptyTitle).assertExists()
        rule.onAllNodesWithText("Открыть кабинет").assertCountEquals(0)
    }
}

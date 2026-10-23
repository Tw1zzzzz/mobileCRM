package com.pulse.android.ui.main

import androidx.compose.runtime.saveable.Saver
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.pulse.core.model.UserRole

enum class AppTab(val title: String, val icon: ImageVector) {
    TODAY("Сегодня", Icons.Outlined.Home),
    STATE("Состояние", Icons.Outlined.BarChart),
    DIARY("Дневник", Icons.AutoMirrored.Outlined.MenuBook),
    PROFILE("Профиль", Icons.Outlined.Person),
    PLAYERS("Игроки", Icons.Outlined.Groups),
    ANALYTICS("Аналитика", Icons.AutoMirrored.Outlined.ShowChart),
    LINEUPS("База знаний", Icons.Outlined.GpsFixed);

    companion object {
        /** Players and staff get different app versions; staff have nothing to log, so no central «+». */
        fun tabs(role: UserRole?): List<AppTab> =
            if (role?.isStaff == true) listOf(TODAY, PLAYERS, ANALYTICS, LINEUPS, PROFILE) else listOf(TODAY, STATE, DIARY, PROFILE)

        fun showsQuickAdd(role: UserRole?): Boolean = role?.isStaff != true

        /** Tab hosting section screens (Сон, Тесты, Календарь…) for the role. */
        fun hub(role: UserRole?): AppTab = if (role?.isStaff == true) TODAY else STATE
    }
}

/** Screens pushed inside a tab (same set as iOS `Route`). */
sealed interface Route {
    data object Sleep : Route
    data object SleepRecovery : Route
    data object Fitness : Route
    data object FitnessWeek : Route
    data object ScreenTime : Route
    data object ScreenTimeApps : Route
    data object Tests : Route
    data object Cognitive : Route
    data object Mood : Route
    data object Balance : Route
    data object GameTests : Route
    data object Statistics : Route
    data class Calendar(val createNew: Boolean = false) : Route
    data class Event(val id: kotlin.uuid.Uuid) : Route
    data object Players : Route
    data class Player(val id: kotlin.uuid.Uuid) : Route
    data object Nutrition : Route
    data object NutritionHistory : Route
    data object Lineups : Route
    data class Lineup(val id: kotlin.uuid.Uuid) : Route
    data object TeamAnalytics : Route
    data object TeamCorrelations : Route
    data object GameStats : Route
    data object TeamFood : Route
    data object WeeklyReport : Route
    data object PlayerPortrait : Route
}

/** Routes as short strings for saved instance state: `Sleep`, `Calendar:true`, `Event:<uuid>`. */
object RouteCodec {
    private val objects: List<Route> = listOf(
        Route.Sleep, Route.SleepRecovery, Route.Fitness, Route.FitnessWeek, Route.ScreenTime, Route.ScreenTimeApps, Route.Tests,
        Route.Cognitive, Route.Mood, Route.Balance, Route.GameTests, Route.Statistics, Route.Players, Route.Nutrition,
        Route.NutritionHistory, Route.Lineups, Route.TeamAnalytics, Route.TeamCorrelations, Route.GameStats, Route.TeamFood,
        Route.WeeklyReport, Route.PlayerPortrait
    )

    fun encode(route: Route): String = when (route) {
        is Route.Calendar -> "Calendar:${route.createNew}"
        is Route.Event -> "Event:${route.id}"
        is Route.Player -> "Player:${route.id}"
        is Route.Lineup -> "Lineup:${route.id}"
        else -> route.toString()
    }

    /** Null for anything this build does not know (a route removed in an update) — it is dropped from the stack. */
    fun decode(text: String): Route? {
        val name = text.substringBefore(':')
        val argument = text.substringAfter(':', "")
        fun id() = runCatching { kotlin.uuid.Uuid.parse(argument) }.getOrNull()
        return when (name) {
            "Calendar" -> argument.toBooleanStrictOrNull()?.let { Route.Calendar(it) }
            "Event" -> id()?.let { Route.Event(it) }
            "Player" -> id()?.let { Route.Player(it) }
            "Lineup" -> id()?.let { Route.Lineup(it) }
            else -> objects.firstOrNull { it.toString() == text }
        }
    }
}

/** `adb shell am start … --es route sleep` opens a screen directly (debug builds; used for UI review). */
object DebugRoutes {
    fun resolve(name: String, staff: Boolean): Pair<AppTab, List<Route>>? {
        val hub = if (staff) AppTab.TODAY else AppTab.STATE
        return when (name) {
            "today" -> AppTab.TODAY to emptyList()
            "state" -> AppTab.STATE to emptyList()
            "profile" -> AppTab.PROFILE to emptyList()
            "sleep" -> hub to listOf(Route.Sleep)
            "recovery" -> hub to listOf(Route.Sleep, Route.SleepRecovery)
            "fitness" -> hub to listOf(Route.Fitness)
            "fitnessWeek" -> hub to listOf(Route.Fitness, Route.FitnessWeek)
            "screen" -> hub to listOf(Route.ScreenTime)
            "screenApps" -> hub to listOf(Route.ScreenTime, Route.ScreenTimeApps)
            "nutrition" -> hub to listOf(Route.Nutrition)
            "nutritionHistory" -> hub to listOf(Route.Nutrition, Route.NutritionHistory)
            "tests" -> hub to listOf(Route.Tests)
            "cognitive" -> hub to listOf(Route.Tests, Route.Cognitive)
            "mood" -> hub to listOf(Route.Tests, Route.Mood)
            "balance" -> hub to listOf(Route.Tests, Route.Balance)
            "gameTests" -> hub to listOf(Route.Tests, Route.GameTests)
            "stats" -> hub to listOf(Route.Statistics)
            "calendar" -> hub to listOf(Route.Calendar())
            "gameStats" -> hub to listOf(Route.GameStats)
            "weekly" -> AppTab.TODAY to listOf(Route.WeeklyReport)
            "portrait" -> AppTab.PROFILE to listOf(Route.PlayerPortrait)
            "diary" -> (if (staff) AppTab.LINEUPS else AppTab.DIARY) to emptyList()
            "players" -> AppTab.PLAYERS to emptyList()
            "analytics" -> AppTab.ANALYTICS to emptyList()
            "player" -> AppTab.PLAYERS to listOf(Route.Player(com.pulse.core.demo.DemoData.players[0].id))
            else -> null
        }
    }
}

/** One back stack per tab, like iOS `AppRouter`; tapping the open tab again returns it to its first screen. */
class AppRouter {
    var selectedTab by mutableStateOf(AppTab.TODAY)
    private val stacks = mutableStateMapOf<AppTab, List<Route>>()

    fun path(tab: AppTab): List<Route> = stacks[tab] ?: emptyList()

    fun open(route: Route, tab: AppTab = selectedTab) {
        selectedTab = tab
        stacks[tab] = path(tab) + route
    }

    /** System back: pops the open tab; false when it is already at its root. */
    fun pop(): Boolean {
        val path = path(selectedTab)
        if (path.isEmpty()) return false
        stacks[selectedTab] = path.dropLast(1)
        return true
    }

    fun popToRoot(tab: AppTab) {
        if (path(tab).isNotEmpty()) stacks[tab] = emptyList()
    }

    /** Resets a tab to its root and pushes the given stack. */
    fun show(routes: List<Route>, tab: AppTab) {
        selectedTab = tab
        stacks[tab] = routes
    }

    /** Opens the questionnaire full-screen (reminder cards). */
    var showsSurvey by mutableStateOf(false)
    /** Set before `showsSurvey` to re-run only the «Игровой характер» test. */
    var surveyTestOnly by mutableStateOf(false)
    var pendingFoodCapture by mutableStateOf(false)

    fun select(tab: AppTab) {
        if (tab == selectedTab) popToRoot(tab) else selectedTab = tab
    }

    /** Open tab and back stacks for saved instance state; sheets (survey, food capture) are not kept. */
    fun saved(): List<String> = listOf(selectedTab.name) +
        stacks.filterValues { it.isNotEmpty() }.map { (tab, path) -> "${tab.name}=" + path.joinToString("|", transform = RouteCodec::encode) }

    companion object {
        fun restored(saved: List<String>): AppRouter = AppRouter().apply {
            saved.firstOrNull()?.let { name -> AppTab.entries.firstOrNull { it.name == name } }?.let { selectedTab = it }
            saved.drop(1).forEach { entry ->
                val tab = AppTab.entries.firstOrNull { it.name == entry.substringBefore('=') } ?: return@forEach
                val path = entry.substringAfter('=', "").split('|').filter { it.isNotEmpty() }.mapNotNull(RouteCodec::decode)
                if (path.isNotEmpty()) stacks[tab] = path
            }
        }

        /** Keeps the open screen across rotation, window resizing and process death. */
        val Saver: Saver<AppRouter, List<String>> = Saver(save = { it.saved() }, restore = { restored(it) })
    }
}

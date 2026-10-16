package com.pulse.android.ui.main

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
    var pendingFoodCapture by mutableStateOf(false)

    fun select(tab: AppTab) {
        if (tab == selectedTab) popToRoot(tab) else selectedTab = tab
    }
}

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
    }
}

/** Screens pushed inside a tab (filled in step 3). */
sealed interface Route {
    data class Placeholder(val title: String, val step: String) : Route
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

    fun select(tab: AppTab) {
        if (tab == selectedTab) popToRoot(tab) else selectedTab = tab
    }
}

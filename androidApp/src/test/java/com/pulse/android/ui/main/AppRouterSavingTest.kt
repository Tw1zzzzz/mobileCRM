package com.pulse.android.ui.main

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class AppRouterSavingTest {
    private val id = Uuid.parse("CD90FD1F-F5CA-4030-B11B-5E1C546D9804")

    @Test fun everyRouteRoundTrips() {
        val routes = listOf(
            Route.Sleep, Route.SleepRecovery, Route.Fitness, Route.FitnessWeek, Route.ScreenTime, Route.ScreenTimeApps, Route.Tests,
            Route.Cognitive, Route.Mood, Route.Balance, Route.GameTests, Route.Statistics, Route.Calendar(), Route.Calendar(createNew = true),
            Route.Event(id), Route.Players, Route.Player(id), Route.Nutrition, Route.NutritionHistory, Route.Lineups, Route.Lineup(id),
            Route.TeamAnalytics, Route.TeamCorrelations, Route.GameStats, Route.TeamFood, Route.WeeklyReport, Route.PlayerPortrait
        )
        routes.forEach { assertEquals(it, RouteCodec.decode(RouteCodec.encode(it)), "round trip of $it") }
    }

    @Test fun unknownRouteIsDropped() {
        assertNull(RouteCodec.decode("Nope"))
        assertNull(RouteCodec.decode("Event:not-a-uuid"))
        val router = AppRouter.restored(listOf("STATE", "STATE=Sleep|Nope"))
        assertEquals(listOf<Route>(Route.Sleep), router.path(AppTab.STATE))
    }

    @Test fun routerRoundTripsThroughSavedState() {
        val router = AppRouter()
        router.show(listOf(Route.PlayerPortrait), AppTab.PROFILE)
        router.show(listOf(Route.Sleep, Route.SleepRecovery), AppTab.STATE)
        val restored = AppRouter.restored(router.saved())
        assertEquals(AppTab.STATE, restored.selectedTab)
        assertEquals(listOf(Route.Sleep, Route.SleepRecovery), restored.path(AppTab.STATE))
        assertEquals(listOf<Route>(Route.PlayerPortrait), restored.path(AppTab.PROFILE))
        assertEquals(emptyList(), restored.path(AppTab.TODAY))
    }
}

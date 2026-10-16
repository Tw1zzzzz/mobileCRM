package com.pulse.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.pulse.android.ui.main.AppRouter
import com.pulse.core.PulseCore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

val LocalCore = staticCompositionLocalOf<PulseCore> { error("PulseCore is not provided") }
val LocalRouter = staticCompositionLocalOf<AppRouter> { error("AppRouter is not provided") }

/** Changes every time any store changes; screens read it through [observeCore] to redraw. */
private val LocalCoreVersion = compositionLocalOf { 0 }

/** All store states merged into one signal (like SwiftUI's observation of the iOS stores). */
private fun PulseCore.changes(): Flow<Int> {
    val flows: List<Flow<Any?>> = listOf(
        session.user, sleep.state, nutrition.state, wellbeing.state, cognitive.state, calendar.state, fitness.state,
        screenTime.state, lineups.state, gameGoals.state, game.state, survey.state
    )
    var version = 0
    return combine(flows) { ++version }
}

@Composable
fun ProvideCore(core: PulseCore, router: AppRouter, content: @Composable () -> Unit) {
    val changes = remember(core) { core.changes() }
    val version by changes.collectAsState(0)
    CompositionLocalProvider(LocalCore provides core, LocalRouter provides router, LocalCoreVersion provides version, content = content)
}

/** The core for a screen that should redraw when data changes. */
@Composable
fun observeCore(): PulseCore {
    LocalCoreVersion.current
    return LocalCore.current
}

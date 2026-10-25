package com.pulse.android.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.pulse.android.ui.auth.AuthScreen
import com.pulse.android.ui.theme.Metrics
import com.pulse.core.model.UserRole
import com.pulse.android.ui.components.FitReport
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A small phone (360dp) at the largest font scale the app honours: no clipped text, no words broken in half. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "w360dp-h640dp")
class TextFitSweepTest {
    @get:Rule val rule = createComposeRule()
    private val unfit = mutableSetOf<String>()

    @Before fun listen() { FitReport.onUnfit = { synchronized(unfit) { unfit += it } } }
    @After fun stop() { FitReport.onUnfit = null }

    private fun sweep(route: String, role: UserRole = UserRole.TEAM_PLAYER) {
        rule.setMain(testCore(role), route, fontScale = Metrics.maxFontScale)
        rule.waitForIdle()
        rule.assertTextFits(unfit)
    }

    @Test fun today() = sweep("today")
    @Test fun stateHub() = sweep("state")
    @Test fun diary() = sweep("diary")
    @Test fun profile() = sweep("profile")
    @Test fun sleep() = sweep("sleep")
    @Test fun recovery() = sweep("recovery")
    @Test fun fitness() = sweep("fitness")
    @Test fun screenTime() = sweep("screen")
    @Test fun calendar() = sweep("calendar")
    @Test fun weekly() = sweep("weekly")
    @Test fun statistics() = sweep("stats")
    @Test fun tests() = sweep("tests")
    @Test fun gameStats() = sweep("gameStats")
    @Test fun nutrition() = sweep("nutrition")
    @Test fun staffPlayers() = sweep("players", UserRole.STAFF)
    @Test fun staffAnalytics() = sweep("analytics", UserRole.STAFF)

    @Test fun aboutOnly() {
        val core = registeredCore().also { it.survey.dismissOnboarding() }
        rule.setMain(core, fontScale = Metrics.maxFontScale)
        rule.waitForIdle()
        rule.assertTextFits(unfit)
    }

    @Test fun signInAndRegistration() {
        val session = testCore(null).session
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, Metrics.maxFontScale)) { AuthScreen(session) }
        }
        rule.assertTextFits(unfit)
        rule.onNodeWithText("Зарегистрироваться").performScrollTo().performClick()
        rule.waitForIdle()
        rule.assertTextFits(unfit)
    }
}

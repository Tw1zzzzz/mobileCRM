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
import com.pulse.core.PulseCore
import com.pulse.core.auth.SessionStore
import kotlinx.coroutines.runBlocking
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

    @Test fun bodyMetricsFromProfile() {
        val core = registeredCore()
        val form = com.pulse.core.model.AboutYou.initial(core.sleep.window, core.fitness.goals, core.nutrition.goals, 300, null, isNewPlayer = true)
            .copy(birthDate = kotlinx.datetime.LocalDate(2004, 3, 12), heightText = "182").withWeightText("74,5")
        core.applyAboutYou(form)
        rule.setMain(core, "fitnessWeek", fontScale = Metrics.maxFontScale)
        rule.waitForIdle()
        rule.assertTextFits(unfit)
    }

    @Test fun aboutOnly() {
        val core = registeredCore().also { it.survey.dismissOnboarding() }
        rule.setMain(core, fontScale = Metrics.maxFontScale)
        rule.waitForIdle()
        rule.assertTextFits(unfit)
    }

    private fun setAuth(session: SessionStore) = rule.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, Metrics.maxFontScale)) { AuthScreen(session) }
    }

    /** Sign-in in a remote build, opened on the card [open] leads to (titled [title]). */
    private fun authSweep(title: String, core: PulseCore = remoteTestCore(), open: () -> Unit = {}) {
        setAuth(core.session)
        open()
        rule.waitForIdle()
        rule.onNodeWithText(title).assertExists()
        rule.assertTextFits(unfit)
    }

    @Test fun remoteSignIn() = authSweep("Вход")
    @Test fun remoteRegistration() = authSweep("Регистрация") { rule.onNodeWithText("Зарегистрироваться").performScrollTo().performClick() }
    @Test fun verifyEmail() = authSweep("Подтвердите почту", remoteTestCore().also { runBlocking { it.session.register(remoteForm) } })
    @Test fun resetEmail() = authSweep("Сброс пароля") { rule.onNodeWithText("Забыли пароль?").performScrollTo().performClick() }
    @Test fun resetCode() = authSweep("Сменить пароль", remoteTestCore().also { runBlocking { it.session.requestPasswordReset("tester@example.com") } })

    @Test fun signInAndRegistration() {
        setAuth(testCore(null).session)
        rule.assertTextFits(unfit)
        rule.onNodeWithText("Зарегистрироваться").performScrollTo().performClick()
        rule.waitForIdle()
        rule.assertTextFits(unfit)
    }
}

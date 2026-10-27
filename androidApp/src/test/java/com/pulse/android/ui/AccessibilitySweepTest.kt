package com.pulse.android.ui

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.pulse.android.ui.auth.AuthScreen
import com.pulse.core.PulseCore
import com.pulse.core.model.UserRole
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

/** Every main screen, as a player and as staff: all controls are labeled for TalkBack and big enough to hit. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AccessibilitySweepTest {
    @get:Rule val rule = createComposeRule()

    private fun sweep(route: String, role: UserRole = UserRole.TEAM_PLAYER) {
        rule.setMain(testCore(role), route)
        rule.waitForIdle()
        rule.assertAccessible()
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
    @Test fun gameStats() = sweep("gameStats")
    @Test fun portrait() = sweep("portrait")
    @Test fun staffPlayers() = sweep("players", UserRole.STAFF)
    @Test fun staffAnalytics() = sweep("analytics", UserRole.STAFF)

    @Test fun aboutOnly() {
        val core = registeredCore().also { it.survey.dismissOnboarding() }
        rule.setMain(core)
        rule.waitForIdle()
        rule.assertAccessible()
    }
    @Test fun staffPlayer() = sweep("player", UserRole.STAFF)

    /** Sign-in in a remote build, opened on the card [open] leads to (titled [title]). */
    private fun authSweep(title: String, core: PulseCore = remoteTestCore(), open: () -> Unit = {}) {
        rule.setContent { AuthScreen(core.session) }
        open()
        rule.waitForIdle()
        rule.onNodeWithText(title).assertExists()
        rule.assertAccessible()
    }

    @Test fun remoteSignIn() = authSweep("Вход")
    @Test fun remoteRegistration() = authSweep("Регистрация") { rule.onNodeWithText("Зарегистрироваться").performScrollTo().performClick() }
    @Test fun verifyEmail() = authSweep("Подтвердите почту", remoteTestCore().also { runBlocking { it.session.register(remoteForm) } })
    @Test fun resetEmail() = authSweep("Сброс пароля") { rule.onNodeWithText("Забыли пароль?").performScrollTo().performClick() }
    @Test fun resetCode() = authSweep("Сменить пароль", remoteTestCore().also { runBlocking { it.session.requestPasswordReset("tester@example.com") } })

    @Test fun tabsReportSelection() {
        rule.setMain(testCore(UserRole.TEAM_PLAYER), "today")
        rule.onNode(hasText("Сегодня") and isSelectable()).assertIsSelected()
        rule.onNode(hasText("Профиль") and isSelectable()).assertIsNotSelected()
    }

    @Test fun headersAreHeadings() {
        rule.setMain(testCore(UserRole.TEAM_PLAYER), "sleep")
        rule.onNode(hasText("Сон") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
    }
}

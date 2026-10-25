package com.pulse.android.ui

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import com.pulse.core.model.UserRole
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

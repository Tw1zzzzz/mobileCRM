package com.pulse.android.ui.onboarding

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.pulse.android.ui.MainContent
import com.pulse.android.ui.registeredCore
import com.pulse.android.ui.setMain
import com.pulse.core.PulseCore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** A registered player without «О тебе» gets the step on its own, once, and cannot skip it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AboutYouStepTest {
    @get:Rule val rule = createComposeRule()

    private fun open(): PulseCore = registeredCore().also { core ->
        // The full questionnaire was answered earlier; only «О тебе» is missing.
        core.survey.dismissOnboarding()
        rule.setMain(core)
        rule.waitForIdle()
    }

    private fun type(placeholder: String, text: String) =
        rule.onNode(hasSetTextAction() and hasText(placeholder)).performScrollTo().performTextInput(text)

    private fun fillBody() {
        type("ДД.ММ.ГГГГ", "12.03.2004")
        type("Например, 182", "182")
        type("Например, 74", "74")
    }

    @Test fun aboutStepRequiresAllFields() {
        open()
        rule.onNodeWithText("Сохранить").assertIsNotEnabled()
        fillBody()
        rule.onNodeWithText("Сохранить").assertIsEnabled()
    }

    @Test fun savingAppliesToCore() {
        val core = open()
        fillBody()
        rule.onNodeWithText("Сохранить").performClick()
        rule.waitForIdle()
        assertEquals(182, core.body.profile?.heightCm)
        assertFalse(core.needsAboutYou)
        rule.onNodeWithText("Сохранить").assertDoesNotExist()
    }

    @Test fun aboutOnlyOffersSignOut() {
        val core = open()
        rule.onNodeWithText("Выйти из аккаунта").performClick()
        rule.waitForIdle()
        assertNull(core.session.user.value)
    }

    @Test fun aboutStepSurvivesRecreation() {
        val core = registeredCore().also { it.survey.dismissOnboarding() }
        val restoration = StateRestorationTester(rule)
        restoration.setContent { MainContent(core) }
        rule.waitForIdle()
        type("Например, 182", "182")
        type("Например, 74", "74")
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText("182")).assertExists()
        rule.onNode(hasSetTextAction() and hasText("74")).assertExists()
    }
}

package com.pulse.android.ui.auth

import android.app.Application
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.pulse.android.ui.remoteTestCore
import com.pulse.android.ui.testCore
import com.pulse.core.model.AccessType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RegistrationRestoreTest {
    @get:Rule val rule = createComposeRule()

    @Test fun registrationKeepsFieldsButNotPasswords() {
        val restoration = StateRestorationTester(rule)
        val session = testCore(null).session
        restoration.setContent { AuthScreen(session) }
        rule.onNodeWithText("Зарегистрироваться").performScrollTo().performClick()
        rule.onNode(hasSetTextAction() and hasText("Имя")).performTextInput("Tw1z")
        rule.onNode(hasSetTextAction() and hasText("Email")).performTextInput("tw1z@example.com")
        rule.onNode(hasSetTextAction() and hasText("Пароль")).performTextInput("secret-123")

        restoration.emulateSavedInstanceStateRestore()

        rule.onNode(hasSetTextAction() and hasText("Tw1z")).assertExists()
        rule.onNode(hasSetTextAction() and hasText("tw1z@example.com")).assertExists()
        rule.onNode(hasSetTextAction() and hasText("Пароль")).assertExists() // empty again: only the placeholder
    }

    @Test fun betaConsentSurvivesRestore() {
        val restoration = StateRestorationTester(rule)
        val session = remoteTestCore().session
        restoration.setContent { AuthScreen(session) }
        rule.onNodeWithText("Зарегистрироваться").performScrollTo().performClick()
        rule.onAllNodes(isToggleable())[2].performScrollTo().performClick()

        restoration.emulateSavedInstanceStateRestore()

        rule.onAllNodes(isToggleable())[2].assertIsOn()
    }

    /** A form saved by the previous version (nine entries, no beta consent) still restores. */
    @Test fun restoresFormSavedBeforeBetaConsent() {
        val saved = listOf("Tw1z", "tw1z@example.com", "", "", "", "TEAM", "PLAYER", "true", "true")
        val form = requireNotNull(RegistrationFormSaver.restore(saved))
        assertEquals("Tw1z", form.nickname)
        assertEquals(AccessType.TEAM, form.accessType)
        assertFalse(form.acceptedBetaTerms)
    }
}

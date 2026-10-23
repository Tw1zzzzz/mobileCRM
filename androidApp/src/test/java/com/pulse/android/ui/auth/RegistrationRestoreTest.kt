package com.pulse.android.ui.auth

import android.app.Application
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.pulse.android.ui.testCore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
}

package com.pulse.android.ui.auth

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import com.pulse.android.ui.testCore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/** Registration reads like a form: names stay on filled fields, «Далее» moves on, consents come before the button. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RegistrationFormTest {
    @get:Rule val rule = createComposeRule()

    private fun openRegistration() {
        rule.setContent { AuthScreen(testCore(null).session) }
        rule.onNodeWithText("Зарегистрироваться").performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun field(name: String) = rule.onNode(hasSetTextAction() and hasText(name))

    @Test fun filledFieldKeepsItsName() {
        openRegistration()
        field("Имя").performScrollTo().performTextInput("Artem")
        field("Пароль").performScrollTo().performTextInput("test-pass-1")
        field("Имя").assertExists()
        field("Пароль").assertExists()
    }

    @Test fun consentsAboveCreateButton() {
        openRegistration()
        val consent = rule.onNode(isToggleable() and hasText("Я принимаю условия", substring = true)).getUnclippedBoundsInRoot()
        val button = rule.onNodeWithText("Создать аккаунт").getUnclippedBoundsInRoot()
        assertTrue(consent.top < button.top, "consent at ${consent.top}, button at ${button.top}")
    }

    @Test fun fieldsMoveToNext() {
        openRegistration()
        field("Имя").assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Next))
        field("Повторите пароль").assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Next))
        field("Ссылка Faceit").assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Done))
    }
}

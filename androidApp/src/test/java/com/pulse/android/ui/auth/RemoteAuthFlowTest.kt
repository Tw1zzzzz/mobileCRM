package com.pulse.android.ui.auth

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.pulse.android.ui.remoteTestCore
import com.pulse.android.ui.testCore
import com.pulse.core.PulseCore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Remote build: sign-up asks for the beta terms and an emailed code; a forgotten password is reset with a code. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RemoteAuthFlowTest {
    @get:Rule val rule = createComposeRule()
    private val requests = mutableListOf<String>()

    private fun open(core: PulseCore = remoteTestCore(requests)): PulseCore = core.also { rule.setContent { AuthScreen(it.session) } }

    private fun field(name: String) = rule.onNode(hasSetTextAction() and hasText(name))

    private fun waitForText(text: String) = rule.waitUntil(5_000) {
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun openRegistration() {
        rule.onNodeWithText("Зарегистрироваться").performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun register() {
        openRegistration()
        field("Имя").performScrollTo().performTextInput("Тестер")
        field("Email").performScrollTo().performTextInput("tester@example.com")
        field("Пароль").performScrollTo().performTextInput("test-pass-1")
        field("Повторите пароль").performScrollTo().performTextInput("test-pass-1")
        rule.onAllNodes(isToggleable()).fetchSemanticsNodes().indices.forEach { index ->
            rule.onAllNodes(isToggleable())[index].performScrollTo().performClick()
        }
        rule.onNodeWithText("Создать аккаунт").performScrollTo().performClick()
        waitForText("Подтвердите почту")
    }

    @Test fun remoteRegistrationShowsBetaConsent() {
        open()
        openRegistration()
        rule.onAllNodes(isToggleable()).assertCountEquals(3)
        // Privacy and beta terms: «Я принимаю » + the document link.
        rule.onAllNodes(isToggleable() and hasText("Я принимаю ")).assertCountEquals(2)
        rule.onNode(hasText("условия бета-тестирования") and hasClickAction() and !isToggleable()).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Условия бета-тестирования").assertExists()
        rule.onNodeWithText("закрытое тестирование", substring = true).assertExists()
    }

    @Test fun localRegistrationHasNoBetaConsent() {
        open(testCore(null))
        openRegistration()
        rule.onAllNodes(isToggleable()).assertCountEquals(2)
        rule.onAllNodesWithText("условия бета-тестирования").assertCountEquals(0)
    }

    @Test fun remoteTeamHints() {
        open()
        openRegistration()
        rule.onNodeWithText("Командный доступ").performClick()
        rule.onNodeWithText("Код команды выдаёт стафф.").assertExists()
        rule.onNodeWithText("Я стафф").performClick()
        rule.onNodeWithText("После регистрации вы получите код команды — отправьте его игрокам.").assertExists()
    }

    @Test fun registrationLeadsToCodeStep() {
        open()
        register()
        rule.onNodeWithText("Мы отправили 6-значный код на tester@example.com").assertExists()
        rule.onNodeWithText("Изменить почту").assertExists()
        assertTrue("auth/register" in requests)
    }

    @Test fun codeStepSignsIn() {
        val core = open()
        register()
        field("Код из письма").performTextInput("123456")
        rule.onNodeWithText("Подтвердить").performScrollTo().performClick()
        rule.waitUntil(5_000) { core.session.user.value != null }
        assertEquals("tester@example.com", core.session.user.value?.email)
        assertTrue("auth/verify-email" in requests)
    }

    @Test fun codeFieldKeepsSixDigits() {
        open()
        register()
        field("Код из письма").performTextInput("12a34567")
        field("123456").assertExists()
    }

    @Test fun resendIsThrottled() {
        open()
        register()
        rule.onNodeWithText("Отправить ещё раз через 1:00").assertExists()
        rule.onAllNodesWithText("Отправить код ещё раз").assertCountEquals(0)
    }

    @Test fun changeEmailReturnsToForm() {
        open()
        register()
        rule.onNodeWithText("Изменить почту").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Создать аккаунт").assertExists()
        field("tester@example.com").assertExists()
    }

    @Test fun forgotPasswordFlow() {
        val core = open()
        rule.onNodeWithText("Забыли пароль?").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Сброс пароля").assertExists()
        field("Email").performTextInput("tester@example.com")
        rule.onNodeWithText("Получить код").performScrollTo().performClick()
        waitForText("Мы отправили 6-значный код на tester@example.com")
        field("Код из письма").performScrollTo().performTextInput("654321")
        field("Новый пароль").performScrollTo().performTextInput("new-pass-2")
        field("Повторите пароль").performScrollTo().performTextInput("new-pass-2")
        rule.onNodeWithText("Сменить пароль").performScrollTo().performClick()
        rule.waitUntil(5_000) { core.session.user.value != null }
        assertTrue("auth/password-reset" in requests)
        assertTrue("auth/password-reset/confirm" in requests)
    }

    @Test fun resetBackReturnsToLogin() {
        open()
        rule.onNodeWithText("Забыли пароль?").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Назад ко входу").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Вход").assertExists()
    }

    @Test fun localModeHasNoForgotPassword() {
        open(testCore(null))
        rule.onAllNodesWithText("Забыли пароль?").assertCountEquals(0)
    }
}

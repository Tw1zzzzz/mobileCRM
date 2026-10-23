package com.pulse.android.ui.auth

import android.app.Application
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.pulse.android.ui.assertAccessible
import com.pulse.android.ui.testCore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ConsentTest {
    @get:Rule val rule = createComposeRule()

    @Test fun consentRowsAreCheckboxes() {
        rule.setContent { AuthScreen(testCore(null).session) }
        rule.onNodeWithText("Зарегистрироваться").performScrollTo().performClick()

        rule.onAllNodes(isToggleable()).assertCountEquals(2)
        rule.onNode(isToggleable() and hasText("Я принимаю условия", substring = true))
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assertIsOff()
            .performClick()
            .assertIsOn()
        rule.onNode(hasText("Пользовательского соглашения") and hasClickAction() and !isToggleable()).assertExists()
        rule.assertAccessible()
    }
}

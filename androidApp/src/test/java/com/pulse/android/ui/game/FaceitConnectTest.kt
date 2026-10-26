package com.pulse.android.ui.game

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.pulse.android.ui.registeredCore
import com.pulse.android.ui.setMain
import com.pulse.core.model.BodyProfile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** With the app's own Faceit key a player connects by nickname only; without it the key field is asked for. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FaceitConnectTest {
    @get:Rule val rule = createComposeRule()

    private fun openSheet(faceitKey: String?) {
        val core = registeredCore(faceitKey).also {
            it.survey.dismissOnboarding()
            it.body.save(BodyProfile.demo)
        }
        rule.setMain(core, route = "gameStats")
        rule.waitForIdle()
        rule.onNodeWithText("Подключить").performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test fun builtInKeyAsksForNicknameOnly() {
        openSheet("app-key")
        rule.onNodeWithText("Ник или ссылка на профиль").assertExists()
        rule.onNodeWithText("API-ключ *").assertDoesNotExist()
        rule.onNodeWithText("Где взять ключ").assertDoesNotExist()
    }

    @Test fun withoutBuiltInKeyAsksForKey() {
        openSheet(null)
        rule.onNodeWithText("API-ключ *").assertExists()
    }
}

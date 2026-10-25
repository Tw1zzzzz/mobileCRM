package com.pulse.android.ui.health

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.pulse.android.ui.registeredCore
import com.pulse.android.ui.setMain
import com.pulse.core.PulseCore
import com.pulse.core.model.BodyProfile
import com.pulse.core.model.ManualNight
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

/** «Добавить ночь вручную»: a forgotten night goes into the history from the sleep screen. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AddNightTest {
    @get:Rule val rule = createComposeRule()

    private fun open(prepare: (PulseCore) -> Unit = {}): PulseCore = registeredCore().also { core ->
        core.survey.dismissOnboarding()
        core.body.save(BodyProfile.demo)
        prepare(core)
        rule.setMain(core, route = "sleep")
        rule.waitForIdle()
    }

    @Test fun addNightSavesSession() {
        val core = open()
        rule.onNodeWithText("Добавить ночь вручную").performScrollTo().performClick()
        rule.onNodeWithText("Сохранить").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, core.sleep.sessions.size)
        rule.onNodeWithText("Сохранить").assertDoesNotExist()
    }

    @Test fun addNightShowsError() {
        val core = open { core -> assertNull(core.sleep.addNight(ManualNight.suggested(core.sleep.window, Clock.System.now()))) }
        rule.onNodeWithText("Добавить ночь вручную").performScrollTo().performClick()
        rule.onNodeWithText("Сохранить").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Эта ночь пересекается с уже записанной").assertExists()
        assertEquals(1, core.sleep.sessions.size)
    }
}

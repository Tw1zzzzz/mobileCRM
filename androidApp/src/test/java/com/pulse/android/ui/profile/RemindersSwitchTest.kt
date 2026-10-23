package com.pulse.android.ui.profile

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.pulse.android.platform.PulseNotifications
import com.pulse.android.ui.setMain
import com.pulse.android.ui.testCore
import com.pulse.core.model.UserRole
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RemindersSwitchTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val manager get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    /** Leaves and re-enters the screen, as when coming back from system settings. */
    private fun resume() {
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
    }

    @Test fun remindersSwitchFollowsSystemSetting() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        PulseNotifications.setEnabled(app, true)
        rule.setMain(testCore(UserRole.TEAM_PLAYER), "profile")
        val reminders = rule.onNode(isToggleable() and hasText("Напоминания о событиях"))
        reminders.assertIsOn()

        manager.setNotificationsEnabled(false)
        resume()
        reminders.assertIsOff()

        manager.setNotificationsEnabled(true)
        resume()
        reminders.assertIsOn()
    }
}

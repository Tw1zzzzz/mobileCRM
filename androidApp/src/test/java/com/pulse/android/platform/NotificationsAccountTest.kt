package com.pulse.android.platform

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.pulse.core.feature.ReminderKind
import com.pulse.core.feature.ScheduledReminder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.time.Clock

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NotificationsAccountTest {
    @Test fun accountChangeClearsPostedNotifications() {
        val app: Application = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        PulseNotifications.ensureChannels(app)
        PulseNotifications.post(app, ScheduledReminder("weekly-report", Clock.System.now(), "Итоги недели готовы", "…", ReminderKind.WEEKLY))
        assertEquals(1, manager.allNotifications.size)

        PulseNotifications.onAccountChanged(app)

        assertEquals(0, manager.allNotifications.size)
    }
}

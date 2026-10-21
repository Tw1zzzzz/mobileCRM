package com.pulse.android.platform

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pulse.android.R
import com.pulse.core.feature.NotificationRules
import com.pulse.core.model.SleepWindow
import kotlin.time.Instant

/**
 * Sleep tracking in the shade — the Android twin of the iOS Live Activity: an ongoing notification with a running
 * timer and «☀️ Я проснулся», plus the morning «Доброе утро» check in case the user forgets to stop.
 */
object SleepTrackingNotification {
    private const val TAG = "sleep-tracking"

    fun sync(context: Context, activeStart: Instant?, window: SleepWindow) {
        if (activeStart == null) {
            NotificationManagerCompat.from(context).cancel(TAG, 0)
            PulseNotifications.cancel(context, "sleep-wake-check")
            ReminderScheduler.cancel(context, "sleep-wake-check")
            return
        }
        if (!PulseNotifications.systemAllows(context)) return
        val notification = NotificationCompat.Builder(context, PulseNotifications.CHANNEL_SLEEP)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle("Сон отслеживается")
            .setContentText("Будильник в ${window.wakeText} · нажми «Я проснулся», когда встанешь")
            .setWhen(activeStart.toEpochMilliseconds())
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(PulseNotifications.openIntent(context, "sleep", TAG.hashCode()))
            .addAction(0, "☀️ Я проснулся", SleepActions.pending(context, SleepActions.STOP, TAG))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(TAG, 0, notification)
        } catch (_: SecurityException) {
            return
        }
        ReminderScheduler.schedule(context, NotificationRules.wakeCheck(activeStart, window), tag = ReminderScheduler.SLEEP_TAG)
    }
}

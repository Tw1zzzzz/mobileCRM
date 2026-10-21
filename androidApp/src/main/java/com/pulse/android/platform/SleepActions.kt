package com.pulse.android.platform

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pulse.android.PulseApplication

/** «Начать сон» / «Я проснулся» buttons in notifications, the widget and the tile — without opening the app. */
object SleepActions {
    const val START = "com.pulse.android.START_SLEEP"
    const val STOP = "com.pulse.android.STOP_SLEEP"

    private const val EXTRA_NOTIFICATION = "notification"

    /** `notificationTag` — the notification the button lives in, closed after the action. */
    fun pending(context: Context, action: String, notificationTag: String? = null): PendingIntent = PendingIntent.getBroadcast(
        context, (action + notificationTag).hashCode(),
        Intent(context, SleepActionReceiver::class.java).setAction(action).putExtra(EXTRA_NOTIFICATION, notificationTag),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun run(context: Context, action: String, notificationTag: String? = null) {
        val core = (context.applicationContext as PulseApplication).core
        when (action) {
            START -> if (!core.sleep.isSleeping) core.sleep.startSleep()
            STOP -> if (core.sleep.isSleeping) core.sleep.stopSleep()
        }
        notificationTag?.let { PulseNotifications.cancel(context, it) }
        PulseNotifications.cancel(context, "sleep-bedtime")
        PulseNotifications.cancel(context, "sleep-wake-check")
    }
}

class SleepActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.action?.let { SleepActions.run(context, it, intent.getStringExtra("notification")) }
    }
}

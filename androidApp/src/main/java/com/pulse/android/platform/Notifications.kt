package com.pulse.android.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pulse.android.MainActivity
import com.pulse.android.R
import com.pulse.core.feature.ReminderKind
import com.pulse.core.feature.ScheduledReminder

/** Channels, the «Напоминания» switch and posting of local notifications (iOS `UNUserNotificationCenter` twin). */
object PulseNotifications {
    const val CHANNEL_EVENTS = "events"
    const val CHANNEL_TESTS = "tests"
    const val CHANNEL_SLEEP = "sleep"
    const val CHANNEL_WEEKLY = "weekly"

    /** Extra of the activity intent: «event:<UUID>», «sleep», «weekly», «gameTest:<phase>». */
    const val EXTRA_OPEN = "open"

    private const val PREFS = "pulse.settings"
    private const val KEY_ENABLED = "notifications.enabled"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        listOf(
            NotificationChannel(CHANNEL_EVENTS, "События", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_TESTS, "Игровые тесты", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_SLEEP, "Сон", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_WEEKLY, "Итоги недели", NotificationManager.IMPORTANCE_LOW)
        ).forEach(manager::createNotificationChannel)
    }

    /** The system allows posting (Android 13+ runtime permission and the app-level switch in settings). */
    fun systemAllows(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Профиль → «Напоминания о событиях». */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false) && systemAllows(context)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun channelFor(kind: ReminderKind): String = when (kind) {
        ReminderKind.EVENT -> CHANNEL_EVENTS
        ReminderKind.GAME_TEST -> CHANNEL_TESTS
        ReminderKind.BEDTIME, ReminderKind.WAKE_CHECK -> CHANNEL_SLEEP
        ReminderKind.WEEKLY -> CHANNEL_WEEKLY
    }

    /** Where a tap leads, as the activity extra. */
    fun openTarget(reminder: ScheduledReminder): String = when (reminder.kind) {
        ReminderKind.EVENT -> "event:${reminder.eventId}"
        ReminderKind.GAME_TEST -> "gameTest:${reminder.phase?.name}"
        ReminderKind.BEDTIME, ReminderKind.WAKE_CHECK -> "sleep"
        ReminderKind.WEEKLY -> "weekly"
    }

    fun openIntent(context: Context, target: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN, target)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun post(context: Context, reminder: ScheduledReminder) {
        if (!systemAllows(context)) return
        val code = reminder.id.hashCode()
        val builder = NotificationCompat.Builder(context, channelFor(reminder.kind))
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle(reminder.title)
            .setContentText(reminder.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.body))
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, openTarget(reminder), code))
        when (reminder.kind) {
            ReminderKind.BEDTIME -> builder.addAction(0, "🌙 Начать сон", SleepActions.pending(context, SleepActions.START, reminder.id))
            ReminderKind.WAKE_CHECK -> builder.addAction(0, "☀️ Я проснулся", SleepActions.pending(context, SleepActions.STOP, reminder.id))
            else -> Unit
        }
        try {
            NotificationManagerCompat.from(context).notify(reminder.id, 0, builder.build())
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post: nothing to show.
        }
    }

    fun cancel(context: Context, id: String) = NotificationManagerCompat.from(context).cancel(id, 0)
}

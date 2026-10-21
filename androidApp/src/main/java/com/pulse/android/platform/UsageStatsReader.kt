package com.pulse.android.platform

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.pulse.core.feature.AppSession
import com.pulse.core.feature.ScreenUsageMath
import com.pulse.core.feature.ScreenUsageSnapshot
import com.pulse.core.model.AppCategory
import com.pulse.core.util.addingDays
import com.pulse.core.util.startOfDay
import kotlin.time.Instant

/**
 * Real screen time from Android usage statistics (special access «Доступ к истории использования»). Reads 30 days of
 * foreground events and unlocks; the home screen and system UI are not counted, like Digital Wellbeing.
 */
class UsageStatsReader(private val context: Context) {
    fun hasAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** null without access, so the store falls back to manual entries / the demo. */
    fun read(now: Instant, days: Int = 30): ScreenUsageSnapshot? {
        if (!hasAccess()) return null
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val from = now.startOfDay().addingDays(-(days - 1))
        val raw = mutableListOf<RawUsageEvent>()
        val events = manager.queryEvents(from.toEpochMilliseconds(), now.toEpochMilliseconds())
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> RawUsageEvent.Type.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> RawUsageEvent.Type.PAUSED
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> RawUsageEvent.Type.SCREEN_OFF
                UsageEvents.Event.KEYGUARD_HIDDEN -> RawUsageEvent.Type.UNLOCKED
                else -> null
            } ?: continue
            raw += RawUsageEvent(event.packageName ?: "", type, Instant.fromEpochMilliseconds(event.timeStamp))
        }
        val excluded = homePackages() + "com.android.systemui"
        val sessions = UsageEventsParser.sessions(raw, now).filter { it.packageName !in excluded }
        val apps = sessions.map { it.packageName }.distinct().associateWith(::describe)
        return ScreenUsageMath.build(
            sessions.map { AppSession(it.packageName, apps.getValue(it.packageName).first, apps.getValue(it.packageName).second, it.start, it.end) },
            UsageEventsParser.unlocks(raw), now, days
        )
    }

    private fun homePackages(): Set<String> = context.packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        .map { it.activityInfo.packageName }.toSet()

    private val browsers: Set<String> by lazy {
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.toSet()
    }

    /** App name and Pulse category from the store category the developer declared. */
    private fun describe(packageName: String): Pair<String, AppCategory> {
        val info = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() } to AppCategory.OTHER
        val label = context.packageManager.getApplicationLabel(info).toString()
        val category = when {
            packageName in browsers -> AppCategory.BROWSER
            info.category == ApplicationInfo.CATEGORY_GAME -> AppCategory.GAMES
            info.category == ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.SOCIAL
            info.category == ApplicationInfo.CATEGORY_VIDEO -> AppCategory.VIDEO
            info.category == ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppCategory.WORK
            packageName == context.packageName || "health" in packageName || "fit" in packageName -> AppCategory.HEALTH
            else -> AppCategory.OTHER
        }
        return label to category
    }

    companion object {
        /** System screen where the user grants usage access (opens on Pulse where the system supports it). */
        fun settingsIntent(context: Context): Intent =
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

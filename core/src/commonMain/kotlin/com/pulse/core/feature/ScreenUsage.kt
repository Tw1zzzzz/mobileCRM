package com.pulse.core.feature

import com.pulse.core.model.AppCategory
import com.pulse.core.model.AppUsage
import com.pulse.core.model.ScreenTimeDay
import com.pulse.core.util.Formatters
import com.pulse.core.util.Tint
import com.pulse.core.util.addingDays
import com.pulse.core.util.isSameDay
import com.pulse.core.util.startOfDay
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** One foreground stretch of an app, read from the platform (Android usage events). */
data class AppSession(val packageName: String, val label: String, val category: AppCategory, val start: Instant, val end: Instant)

/** Real screen time: per-day totals (oldest first, last = today) and today's details. */
data class ScreenUsageSnapshot(
    val days: List<ScreenTimeDay>,
    val hourlyToday: List<Int>,
    val appsToday: List<AppUsage>,
    val pickupsToday: Int,
    val firstPickupToday: String?
)

object ScreenUsageMath {
    /** Icon and colour per category, matching the demo dashboard. */
    fun style(category: AppCategory): Pair<String, Tint> = when (category) {
        AppCategory.SOCIAL -> "paperplane.fill" to Tint.CYAN
        AppCategory.GAMES -> "gamecontroller" to Tint.PINK
        AppCategory.BROWSER -> "safari" to Tint.BLUE
        AppCategory.WORK -> "briefcase" to Tint.PURPLE
        AppCategory.VIDEO -> "play.rectangle.fill" to Tint.RED
        AppCategory.HEALTH -> "waveform.path.ecg" to Tint.GREEN
        AppCategory.OTHER -> "app" to Tint.REST_ZONE
    }

    fun build(sessions: List<AppSession>, unlocks: List<Instant>, now: Instant, days: Int = 30): ScreenUsageSnapshot {
        val today = now.startOfDay()
        val dayStarts = (0 until days).map { today.addingDays(it - (days - 1)) }
        // Sessions are clipped to `now` (an app still open at query time) and split at day and hour borders.
        val clipped = sessions.mapNotNull { session ->
            val end = minOf(session.end, now)
            if (end > session.start) session.copy(end = end) else null
        }
        fun seconds(session: AppSession, from: Instant, to: Instant): Long {
            val start = maxOf(session.start, from)
            val end = minOf(session.end, to)
            return if (end > start) (end - start).inWholeSeconds else 0
        }
        val dayList = dayStarts.map { day ->
            val next = day.addingDays(1)
            val perCategory = clipped.groupBy { it.category }
                .mapValues { (_, list) -> (list.sumOf { seconds(it, day, next) } / 60).toInt() }
                .filterValues { it > 0 }
            ScreenTimeDay(day, (clipped.sumOf { seconds(it, day, next) } / 60).toInt(), perCategory)
        }
        val hourly = (0 until 24).map { hour ->
            val from = today + hour.hours
            (clipped.sumOf { seconds(it, from, from + 1.hours) } / 60).toInt()
        }
        val tomorrow = today.addingDays(1)
        val apps = clipped.groupBy { it.packageName }.mapNotNull { (_, list) ->
            val minutes = (list.sumOf { seconds(it, today, tomorrow) } / 60).toInt()
            if (minutes <= 0) return@mapNotNull null
            val first = list.first()
            val (icon, tint) = style(first.category)
            AppUsage(first.label, first.category, minutes, icon, tint)
        }.sortedByDescending { it.minutes }
        val todayUnlocks = unlocks.filter { it.isSameDay(now) && it <= now }.sorted()
        return ScreenUsageSnapshot(dayList, hourly, apps, todayUnlocks.size, todayUnlocks.firstOrNull()?.let(Formatters::time))
    }
}

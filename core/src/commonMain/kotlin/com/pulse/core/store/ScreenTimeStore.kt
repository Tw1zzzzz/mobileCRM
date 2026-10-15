@file:UseSerializers(IsoInstantSerializer::class)

package com.pulse.core.store

import com.pulse.core.demo.DemoData
import com.pulse.core.model.AppCategory
import com.pulse.core.model.AppUsage
import com.pulse.core.model.DailyValue
import com.pulse.core.model.ScreenTimeDay
import com.pulse.core.storage.JsonFileStorage
import com.pulse.core.util.IsoInstantSerializer
import com.pulse.core.util.StatisticsMath
import com.pulse.core.util.addingDays
import com.pulse.core.util.isSameDay
import com.pulse.core.util.startOfDay
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Screen time («screen-time.json»): manual daily entries (iOS) or numbers read from the phone (Android usage
 * statistics, step 4), the daily limit and the demo dashboard.
 */
class ScreenTimeStore(
    private val storage: JsonFileStorage<Saved>?,
    isDemo: Boolean = true
) : PulseStore<ScreenTimeStore.State>(State(isDemo = isDemo)) {

    @Serializable
    data class Entry(val date: Instant, val minutes: Int)

    @Serializable
    data class Saved(val entries: List<Entry> = emptyList(), val limitMinutes: Int = 300, val accessGranted: Boolean = true)

    data class State(
        val entries: List<Entry> = emptyList(),
        val dailyLimitMinutes: Int = 300,
        /** Demo: the «access to screen time» switch that shows the Empty State when off. */
        val isAuthorized: Boolean = true,
        val isDemo: Boolean = true
    )

    init {
        storage?.load()?.let { current = current.copy(entries = it.entries, dailyLimitMinutes = it.limitMinutes, isAuthorized = it.accessGranted) }
    }

    fun reload(isDemo: Boolean) {
        val saved = storage?.load()
        current = State(saved?.entries ?: emptyList(), saved?.limitMinutes ?: 300, saved?.accessGranted ?: true, isDemo)
    }

    private fun persist() {
        storage?.save(Saved(current.entries, current.dailyLimitMinutes, current.isAuthorized))
    }

    fun setLimit(minutes: Int) {
        current = current.copy(dailyLimitMinutes = minutes)
        persist()
    }

    fun setAuthorized(value: Boolean) {
        current = current.copy(isAuthorized = value)
        persist()
    }

    fun setMinutes(minutes: Int, day: Instant) {
        val clamped = minutes.coerceIn(0, 24 * 60)
        current = current.copy(entries = (current.entries.filter { !it.date.isSameDay(day) } + Entry(day.startOfDay(), clamped)).sortedBy { it.date })
        persist()
    }

    fun minutes(day: Instant): Int? = current.entries.firstOrNull { it.date.isSameDay(day) }?.minutes

    /** Hourly chart, pickups and per-app usage need automatic collection (demo only for now). */
    val hasDetailedData: Boolean get() = current.isDemo
    /** Real accounts see a «введи время» prompt instead of the access Empty State. */
    val showsContent: Boolean get() = if (current.isDemo) current.isAuthorized else true

    fun todayMinutes(now: Instant = Clock.System.now()): Int = if (current.isDemo) 263 else minutes(now) ?: 0
    fun yesterdayMinutes(now: Instant = Clock.System.now()): Int = if (current.isDemo) 308 else minutes(now.addingDays(-1)) ?: 0
    val pickups = 87
    val firstPickup = "07:42"
    val hourly: List<Int> get() = if (current.isDemo) DemoData.screenTimeHourly else emptyList()
    val apps: List<AppUsage> get() = if (current.isDemo) DemoData.screenTimeApps else emptyList()

    fun week(now: Instant = Clock.System.now()): List<ScreenTimeDay> {
        if (current.isDemo) return DemoData.screenTimeWeek(now)
        return (0 until 7).map { index ->
            val day = now.startOfDay().addingDays(index - 6)
            ScreenTimeDay(day, minutes(day) ?: 0, emptyMap())
        }
    }

    fun limitProgress(now: Instant = Clock.System.now()): Double = todayMinutes(now).toDouble() / maxOf(current.dailyLimitMinutes, 1)
    fun remainingMinutes(now: Instant = Clock.System.now()): Int = current.dailyLimitMinutes - todayMinutes(now)

    fun changeVsYesterday(now: Instant = Clock.System.now()): Double =
        StatisticsMath.percentChange(yesterdayMinutes(now).toDouble(), todayMinutes(now).toDouble()) ?: 0.0

    fun weekAverage(now: Instant = Clock.System.now()): Int {
        val days = week(now).let { if (current.isDemo) it else it.filter { day -> day.minutes > 0 } }
        return if (days.isEmpty()) 0 else days.sumOf { it.minutes } / days.size
    }

    val categoryTotals: List<Pair<AppCategory, Int>>
        get() = apps.groupBy { it.category }.map { (category, list) -> category to list.sumOf { it.minutes } }.sortedByDescending { it.second }

    /** Daily totals for the last `days` days, oldest first (only entered days for real accounts). */
    fun daily(lastDays: Int, now: Instant = Clock.System.now()): List<DailyValue> {
        if (current.isDemo) return DemoData.screenTimeSeries(lastDays, now)
        val from = now.startOfDay().addingDays(-(lastDays - 1))
        return current.entries.filter { it.date >= from }.map { DailyValue(it.date, it.minutes.toDouble()) }
    }

    companion object {
        const val FILE = "screen-time.json"
    }
}

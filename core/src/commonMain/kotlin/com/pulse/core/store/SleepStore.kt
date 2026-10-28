@file:UseSerializers(IsoInstantSerializer::class)

package com.pulse.core.store

import com.pulse.core.demo.DemoData
import com.pulse.core.model.HealthSnapshot
import com.pulse.core.model.ManualNight
import com.pulse.core.model.SleepMath
import com.pulse.core.model.SleepNight
import com.pulse.core.model.SleepSession
import com.pulse.core.model.SleepWeekDay
import com.pulse.core.model.SleepWindow
import com.pulse.core.storage.JsonFileStorage
import com.pulse.core.util.IsoInstantSerializer
import com.pulse.core.util.addingDays
import com.pulse.core.util.isSameDay
import com.pulse.core.util.minutesBetween
import com.pulse.core.util.secondsBetween
import com.pulse.core.util.startOfDay
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Manual sleep tracking, the planned window and nights from Health Connect («sleep.json», same shape as iOS). */
class SleepStore(
    private val storage: JsonFileStorage<Saved>?,
    seed: List<SleepSession> = DemoData.sleepHistory()
) : PulseStore<SleepStore.State>(State(sessions = seed.sortedByDescending { it.start })) {

    @Serializable
    data class Saved(val sessions: List<SleepSession>, val activeStart: Instant? = null, val window: SleepWindow = SleepWindow())

    data class State(
        val sessions: List<SleepSession> = emptyList(),
        val activeStart: Instant? = null,
        val window: SleepWindow = SleepWindow(),
        /** Nights read from Health Connect (oldest first); empty until connected. */
        val healthNights: List<SleepNight> = emptyList(),
        val isHealthConnected: Boolean = false,
        /** Demo accounts fall back to the demo night; real accounts show only their own data. */
        val isDemo: Boolean = true
    )

    /** Fired when a session starts or ends (ongoing notification, wake-up check). */
    var onTrackingChange: ((Instant?, SleepWindow) -> Unit)? = null
    /** Fired when the planned sleep window changes (bedtime reminder). */
    var onWindowChange: ((SleepWindow) -> Unit)? = null

    init {
        storage?.load()?.let { saved ->
            current = current.copy(sessions = saved.sessions.sortedByDescending { it.start }, activeStart = saved.activeStart, window = saved.window)
        }
    }

    val sessions get() = current.sessions
    val window get() = current.window
    val activeStart get() = current.activeStart
    val isSleeping: Boolean get() = current.activeStart != null

    /** Re-reads the active account's folder (login, logout, demo switch). */
    fun reload(isDemo: Boolean) {
        val saved = storage?.load()
        current = State(
            sessions = (saved?.sessions ?: if (isDemo) DemoData.sleepHistory() else emptyList()).sortedByDescending { it.start },
            activeStart = saved?.activeStart,
            window = saved?.window ?: SleepWindow(),
            isDemo = isDemo
        )
    }

    /** A sync rewrote «sleep.json»: only the saved fields change; nights from Health Connect stay on screen. */
    fun reloadSaved() {
        val saved = storage?.load() ?: return
        current = current.copy(sessions = saved.sessions.sortedByDescending { it.start }, activeStart = saved.activeStart, window = saved.window)
    }

    fun startSleep(at: Instant = Clock.System.now()) {
        if (current.activeStart != null) return
        current = current.copy(activeStart = at)
        persist()
        onTrackingChange?.invoke(at, current.window)
    }

    /** Finishes the active session, computes its duration and stores it in history. */
    fun stopSleep(at: Instant = Clock.System.now()): SleepSession? {
        val start = current.activeStart
        if (start == null || at <= start) {
            current = current.copy(activeStart = null)
            persist()
            onTrackingChange?.invoke(null, current.window)
            return null
        }
        val session = SleepSession(Uuid.random(), start, at)
        current = current.copy(sessions = (listOf(session) + current.sessions).sortedByDescending { it.start }, activeStart = null)
        persist()
        onTrackingChange?.invoke(null, current.window)
        return session
    }

    fun toggleSleep(at: Instant = Clock.System.now()) {
        if (isSleeping) stopSleep(at) else startSleep(at)
    }

    fun cancelActiveSession() {
        current = current.copy(activeStart = null)
        persist()
        onTrackingChange?.invoke(null, current.window)
    }

    /** Why [night] cannot be saved, or null when it can. */
    fun nightError(night: ManualNight, now: Instant = Clock.System.now()): String? {
        val (start, end) = night.interval(now)
        val minutes = minutesBetween(start, end).toInt()
        val active = current.activeStart
        return when {
            end > now -> "Подъём ещё не наступил"
            minutes !in ManualNight.DURATION_MINUTES -> "Сон — от 1 до 16 часов"
            current.sessions.any { it.start < end && start < it.end } || (active != null && active < end) -> "Эта ночь пересекается с уже записанной"
            else -> null
        }
    }

    /** Saves [night] in the history; returns the reason instead when it cannot be saved. */
    fun addNight(night: ManualNight, now: Instant = Clock.System.now()): String? {
        nightError(night, now)?.let { return it }
        val (start, end) = night.interval(now)
        current = current.copy(sessions = (current.sessions + SleepSession(Uuid.random(), start, end)).sortedByDescending { it.start })
        persist()
        return null
    }

    fun deleteSession(id: Uuid) {
        current = current.copy(sessions = current.sessions.filter { it.id != id })
        persist()
    }

    fun updateWindow(window: SleepWindow) {
        current = current.copy(window = window)
        persist()
        onWindowChange?.invoke(window)
    }

    fun sessions(lastDays: Int, now: Instant = Clock.System.now()): List<SleepSession> {
        val from = now.startOfDay().addingDays(-lastDays)
        return current.sessions.filter { it.end >= from && it.end <= now }
    }

    fun averageMinutes(lastDays: Int, now: Instant = Clock.System.now()): Int? {
        val valid = sessions(lastDays, now).filter { it.isValid }
        return if (valid.isEmpty()) null else valid.sumOf { it.durationMinutes } / valid.size
    }

    fun totalMinutes(lastDays: Int, now: Instant = Clock.System.now()): Int = sessions(lastDays, now).sumOf { it.durationMinutes }

    /** Sleep minutes attributed to the day the session ended (wake-up day). */
    fun minutes(day: Instant): Int? {
        val matching = current.sessions.filter { it.end.isSameDay(day) }
        return if (matching.isEmpty()) null else matching.sumOf { it.durationMinutes }
    }

    /**
     * Builds nights from Health Connect and replaces overlapping sessions in the history with them
     * (the tracker is more precise than a manual start/stop — and it also replaces demo nights).
     */
    fun applyHealth(snapshot: HealthSnapshot?) {
        if (snapshot == null) {
            current = current.copy(isHealthConnected = false, healthNights = emptyList())
            return
        }
        val groups = SleepMath.nights(snapshot.sleepIntervals)
        val built = mutableListOf<SleepNight>()
        groups.forEachIndexed { index, group ->
            val vitals = if (index == groups.size - 1) snapshot.nightVitals ?: SleepMath.NightVitals() else SleepMath.NightVitals()
            SleepMath.night(group, current.window.durationMinutes, vitals, built.takeLast(7).map { it.score })?.let { built += it }
        }
        current = current.copy(isHealthConnected = true, healthNights = built)
        importHealthNights(built.map { SleepSession(Uuid.random(), it.start, it.end) })
    }

    fun importHealthNights(nights: List<SleepSession>) {
        if (nights.isEmpty()) return
        val kept = current.sessions.filter { session -> nights.none { overlap(session, it) > 0.5 } }
        current = current.copy(sessions = (kept + nights).sortedByDescending { it.start })
        persist()
    }

    /** The night shown on «Сон», «Сегодня» and «Восстановление»: Health → last session → demo (demo only). */
    fun displayNight(now: Instant = Clock.System.now()): SleepNight? {
        current.healthNights.lastOrNull()?.takeIf { it.end > now.addingDays(-2) }?.let { return it }
        if (current.isDemo && !current.isHealthConnected) return DemoData.lastNight(now)
        return current.sessions.firstOrNull()?.let { SleepMath.manualNight(it, current.window.durationMinutes) }
    }

    fun week(now: Instant = Clock.System.now()): List<SleepWeekDay> {
        if (current.healthNights.any { it.hasStages }) return SleepMath.week(current.healthNights, now)
        if (current.isDemo) return DemoData.sleepWeek(now)
        // Manual sessions have no phases: the whole night counts as light sleep in the weekly bars.
        val nights = current.sessions.take(14).map { SleepMath.manualNight(it, current.window.durationMinutes) }
        return (0 until 7).map { index ->
            val day = now.startOfDay().addingDays(index - 6)
            SleepWeekDay(day, 0, 0, nights.filter { it.end.isSameDay(day) }.sumOf { it.totalMinutes })
        }
    }

    /** Restores the demo history (Профиль → Демо-режим, demo accounts only). */
    fun resetToDemo() {
        current = current.copy(sessions = DemoData.sleepHistory().sortedByDescending { it.start }, activeStart = null, window = SleepWindow())
        persist()
        onTrackingChange?.invoke(null, current.window)
        onWindowChange?.invoke(current.window)
    }

    private fun persist() {
        storage?.save(Saved(current.sessions, current.activeStart, current.window))
    }

    companion object {
        const val FILE = "sleep.json"

        /** Share of the shorter session covered by the other one. */
        fun overlap(a: SleepSession, b: SleepSession): Double {
            val start = maxOf(a.start, b.start)
            val end = minOf(a.end, b.end)
            if (end <= start) return 0.0
            val shorter = minOf(secondsBetween(a.start, a.end), secondsBetween(b.start, b.end))
            return if (shorter > 0) secondsBetween(start, end) / shorter else 0.0
        }
    }
}

@file:UseSerializers(IsoInstantSerializer::class, UuidSerializer::class)

package com.pulse.core.model

import com.pulse.core.util.IsoInstantSerializer
import com.pulse.core.util.Tint
import com.pulse.core.util.UuidSerializer
import com.pulse.core.util.minutesBetween
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

enum class SleepStage(val title: String, val tint: Tint, /** Vertical position on the hypnogram (0 = top). */ val level: Double) {
    AWAKE("Бодрствование", Tint.ORANGE, 0.14),
    REM("REM", Tint.PURPLE, 0.50),
    LIGHT("Лёгкий", Tint.LIGHT_BLUE, 0.635),
    DEEP("Глубокий", Tint.BLUE, 0.83)
}

data class SleepStageSegment(val stage: SleepStage, val start: Instant, val end: Instant) {
    val minutes: Int get() = minutesBetween(start, end).toInt()
}

/** Detailed night from the tracker (phases, body metrics): Health Connect when connected, demo otherwise. */
data class SleepNight(
    val start: Instant,
    val end: Instant,
    val score: Int,
    val baselineDelta: Int,
    val segments: List<SleepStageSegment>,
    val cycles: Int,
    val efficiency: Int,
    val latencyMinutes: Int,
    val awakenings: Int,
    val heartRate: List<Int>,
    /** Body metrics need a watch / tracker; null = not recorded that night. */
    val restingHeartRate: Int?,
    val minHeartRate: Int?,
    val heartRateBaselineDelta: Int?,
    val respiratoryRate: Int?,
    val hrv: Int?,
    val spo2: Int?,
    /** Sleep phases are only recorded by a watch / tracker. */
    val hasStages: Boolean = true
) {
    val totalMinutes: Int get() = minutesBetween(start, end).toInt()

    /** Time actually asleep (without awake segments). */
    val asleepMinutes: Int get() = totalMinutes - minutes(SleepStage.AWAKE)

    fun minutes(stage: SleepStage): Int = segments.filter { it.stage == stage }.sumOf { it.minutes }

    val averageCycleMinutes: Int get() = if (cycles <= 0) 0 else (totalMinutes - minutes(SleepStage.AWAKE)) / cycles

    val scoreLabel: String
        get() = when {
            score >= 85 -> "Отлично"
            score >= 70 -> "Хорошо"
            score >= 50 -> "Средне"
            else -> "Плохо"
        }

    companion object {
        /** Neutral stand-in used only for layout when there is no night yet (never shown as data). */
        fun placeholder(now: Instant = Clock.System.now()) = SleepNight(
            start = now, end = now, score = 0, baselineDelta = 0, segments = emptyList(), cycles = 0, efficiency = 0,
            latencyMinutes = 0, awakenings = 0, heartRate = emptyList(), restingHeartRate = null, minHeartRate = null,
            heartRateBaselineDelta = null, respiratoryRate = null, hrv = null, spo2 = null, hasStages = false
        )
    }
}

/** A manually tracked sleep session (start/stop button in the app, notification action or tile). */
@Serializable
data class SleepSession(val id: Uuid, val start: Instant, val end: Instant) {
    val durationMinutes: Int get() = max(minutesBetween(start, end).toInt(), 0)
    val isValid: Boolean get() = durationMinutes >= MINIMUM_VALID_MINUTES
    val statusTitle: String get() = if (isValid) "Валидная" else "Неполная"

    companion object {
        const val MINIMUM_VALID_MINUTES = 60
    }
}

@Serializable
data class SleepWindow(
    val bedtimeHour: Int = 23,
    val bedtimeMinute: Int = 0,
    val wakeHour: Int = 7,
    val wakeMinute: Int = 0,
    val smartAlarmEnabled: Boolean = true
) {
    val bedtimeText: String get() = "${bedtimeHour.pad()}:${bedtimeMinute.pad()}"
    val wakeText: String get() = "${wakeHour.pad()}:${wakeMinute.pad()}"

    val durationMinutes: Int
        get() {
            val diff = (wakeHour * 60 + wakeMinute) - (bedtimeHour * 60 + bedtimeMinute)
            return if (diff > 0) diff else diff + 24 * 60
        }

    private fun Int.pad() = toString().padStart(2, '0')
}

/** One bar in the weekly stacked chart (deep / REM / light minutes). */
data class SleepWeekDay(val date: Instant, val deep: Int, val rem: Int, val light: Int) {
    val total: Int get() = deep + rem + light
}

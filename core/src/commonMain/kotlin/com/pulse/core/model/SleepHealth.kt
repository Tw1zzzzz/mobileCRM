package com.pulse.core.model

import com.pulse.core.util.StatisticsMath
import com.pulse.core.util.addingDays
import com.pulse.core.util.isSameDay
import com.pulse.core.util.minutesBetween
import com.pulse.core.util.roundToIntSwift
import com.pulse.core.util.secondsBetween
import com.pulse.core.util.startOfDay
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Instant

/** One raw sleep interval from Health Connect / HealthKit (`null` stage = «В кровати»). */
data class SleepInterval(val stage: SleepStage?, val start: Instant, val end: Instant) {
    val minutes: Double get() = minutesBetween(start, end)
}

data class HeartRateSample(val date: Instant, val bpm: Double)

/** Builds nights and the sleep score from raw intervals (pure, no platform APIs). */
object SleepMath {
    /** A gap longer than this (seconds) starts a new night (naps and the next night are separate). */
    const val NIGHT_GAP_SECONDS = 3 * 3600.0

    /** Groups intervals into nights, oldest first. */
    fun nights(intervals: List<SleepInterval>): List<List<SleepInterval>> {
        val sorted = intervals.filter { it.end > it.start }.sortedBy { it.start }
        val groups = mutableListOf<MutableList<SleepInterval>>()
        var lastEnd: Instant? = null
        for (interval in sorted) {
            val previous = lastEnd
            if (previous != null && secondsBetween(previous, interval.start) <= NIGHT_GAP_SECONDS && groups.isNotEmpty()) {
                groups.last().add(interval)
            } else {
                groups.add(mutableListOf(interval))
            }
            lastEnd = if (previous == null) interval.end else maxOf(previous, interval.end)
        }
        // A real night has some sleep in it; drop «in bed only» groups and short naps (< 2 h asleep).
        return groups.filter { asleepMinutes(it) >= 120 }
    }

    fun asleepMinutes(night: List<SleepInterval>): Double =
        night.filter { it.stage != null && it.stage != SleepStage.AWAKE }.sumOf { it.minutes }

    /** Stage segments merged into a continuous hypnogram (overlaps resolved by the later sample). */
    fun segments(night: List<SleepInterval>): List<SleepStageSegment> {
        val staged = night.filter { it.stage != null }.sortedBy { it.start }
        val result = mutableListOf<SleepStageSegment>()
        for (interval in staged) {
            val stage = interval.stage ?: continue
            val start = maxOf(interval.start, result.lastOrNull()?.end ?: interval.start)
            if (interval.end <= start) continue
            val last = result.lastOrNull()
            if (last != null && last.stage == stage && secondsBetween(last.end, start) < 60) {
                result[result.size - 1] = SleepStageSegment(stage, last.start, interval.end)
            } else {
                result.add(SleepStageSegment(stage, start, interval.end))
            }
        }
        return result
    }

    /** 0–100: duration vs. the planned window (50), efficiency (20), deep + REM share (20), awakenings (10). */
    fun score(asleepMinutes: Double, targetMinutes: Int, efficiency: Int, restorativeShare: Double?, awakenings: Int): Int {
        val duration = min(asleepMinutes / max(targetMinutes, 60).toDouble(), 1.0) * 50
        val efficiencyPart = ((efficiency - 70) / 25.0).coerceIn(0.0, 1.0) * 20
        // Without stages (no watch) the restorative part is neutral.
        val restorative = (restorativeShare?.let { min(it / 0.4, 1.0) } ?: 0.5) * 20
        val wake = max(10 - awakenings * 2, 0).toDouble()
        return (duration + efficiencyPart + restorative + wake).roundToIntSwift()
    }

    /** Averages heart-rate samples into a fixed number of buckets for the night chart. */
    fun heartRateCurve(samples: List<HeartRateSample>, start: Instant, end: Instant, buckets: Int = 20): List<Int> {
        val span = secondsBetween(start, end)
        if (span <= 0 || samples.isEmpty()) return emptyList()
        val sums = DoubleArray(buckets)
        val counts = IntArray(buckets)
        for (sample in samples) {
            if (sample.date < start || sample.date > end) continue
            val index = min((secondsBetween(start, sample.date) / span * buckets).toInt(), buckets - 1)
            sums[index] += sample.bpm
            counts[index] += 1
        }
        return sums.indices.mapNotNull { if (counts[it] > 0) (sums[it] / counts[it]).roundToIntSwift() else null }
    }

    data class NightVitals(
        val heartRate: List<HeartRateSample> = emptyList(),
        val restingHeartRate: Int? = null,
        val restingBaseline: Double? = null,
        val hrv: Int? = null,
        val respiratoryRate: Int? = null,
        val spo2: Int? = null
    )

    /** Night from a manual start/stop session: duration only, no phases or body metrics. */
    fun manualNight(session: SleepSession, targetMinutes: Int): SleepNight = SleepNight(
        start = session.start, end = session.end,
        score = score(session.durationMinutes.toDouble(), targetMinutes, efficiency = 90, restorativeShare = null, awakenings = 0),
        baselineDelta = 0, segments = emptyList(), cycles = max(session.durationMinutes / 90, 1), efficiency = 90,
        latencyMinutes = 0, awakenings = 0, heartRate = emptyList(), restingHeartRate = null, minHeartRate = null,
        heartRateBaselineDelta = null, respiratoryRate = null, hrv = null, spo2 = null, hasStages = false
    )

    /** Stage minutes per wake-up day for the weekly chart (light includes unspecified sleep). */
    fun week(nights: List<SleepNight>, now: Instant = Clock.System.now()): List<SleepWeekDay> = (0 until 7).map { index ->
        val day = now.startOfDay().addingDays(index - 6)
        val night = nights.lastOrNull { it.end.isSameDay(day) }
        SleepWeekDay(
            date = day,
            deep = night?.minutes(SleepStage.DEEP) ?: 0,
            rem = night?.minutes(SleepStage.REM) ?: 0,
            light = night?.minutes(SleepStage.LIGHT) ?: 0
        )
    }

    /** A full [SleepNight] for the screens. `previousScores` give the personal baseline for «к базовому». */
    fun night(intervals: List<SleepInterval>, targetMinutes: Int, vitals: NightVitals, previousScores: List<Int>): SleepNight? {
        val start = intervals.minOfOrNull { it.start } ?: return null
        val end = intervals.maxOfOrNull { it.end } ?: return null
        val segments = segments(intervals)
        val asleep = asleepMinutes(intervals)
        val span = minutesBetween(start, end)
        val efficiency = if (span > 0) (asleep / span * 100).roundToIntSwift() else 0
        val firstSleep = segments.firstOrNull { it.stage != SleepStage.AWAKE }?.start ?: start
        val latency = minutesBetween(start, firstSleep).toInt()
        val awakenings = segments.withIndex().count { (index, segment) ->
            segment.stage == SleepStage.AWAKE && index > 0 && index < segments.size - 1
        }
        val hasStages = segments.any { it.stage == SleepStage.DEEP || it.stage == SleepStage.REM }
        val restorative = if (hasStages) {
            segments.filter { it.stage == SleepStage.DEEP || it.stage == SleepStage.REM }.sumOf { it.minutes } / max(asleep, 1.0)
        } else null
        val score = score(asleep, targetMinutes, efficiency, restorative, awakenings)
        val baseline = StatisticsMath.mean(previousScores.map { it.toDouble() })
        val curve = heartRateCurve(vitals.heartRate, start, end)
        val minimum = vitals.heartRate.filter { it.date >= start && it.date <= end }.minOfOrNull { it.bpm }
        val resting = vitals.restingHeartRate ?: curve.minOrNull()
        val restingBaseline = vitals.restingBaseline
        return SleepNight(
            start = start,
            end = end,
            score = score,
            baselineDelta = baseline?.let { score - it.roundToIntSwift() } ?: 0,
            segments = segments,
            cycles = if (hasStages) max(segments.count { it.stage == SleepStage.REM }, 1) else max((asleep / 90).toInt(), 1),
            efficiency = min(efficiency, 100),
            latencyMinutes = max(latency, 0),
            awakenings = awakenings,
            heartRate = curve,
            restingHeartRate = resting,
            minHeartRate = minimum?.roundToIntSwift(),
            heartRateBaselineDelta = if (resting != null && restingBaseline != null) (resting - restingBaseline).roundToIntSwift() else null,
            respiratoryRate = vitals.respiratoryRate,
            hrv = vitals.hrv,
            spo2 = vitals.spo2,
            hasStages = hasStages
        )
    }
}

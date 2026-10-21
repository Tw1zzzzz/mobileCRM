package com.pulse.core.feature

import com.pulse.core.model.BodyMetric
import com.pulse.core.model.DailyValue
import com.pulse.core.model.FitnessMath
import com.pulse.core.model.HealthDaySummary
import com.pulse.core.model.HealthSnapshot
import com.pulse.core.model.HeartRateSample
import com.pulse.core.model.SleepInterval
import com.pulse.core.model.SleepMath
import com.pulse.core.model.Workout
import com.pulse.core.model.WorkoutKind
import com.pulse.core.util.StatisticsMath
import com.pulse.core.util.addingDays
import com.pulse.core.util.minutesBetween
import com.pulse.core.util.roundToIntSwift
import com.pulse.core.util.startOfDay
import kotlin.math.max
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A quantity recorded over an interval (steps, metres, kcal). */
data class TimedValue(val start: Instant, val end: Instant, val value: Double)

data class RawExercise(
    val id: String,
    val start: Instant,
    val end: Instant,
    val kind: WorkoutKind,
    val kcal: Double?,
    val averageHeartRate: Double?
)

/** Platform records already converted to core types (Health Connect on Android); every list may be empty. */
data class HealthRawData(
    val steps: List<TimedValue> = emptyList(),
    val distanceMeters: List<TimedValue> = emptyList(),
    val activeKcal: List<TimedValue> = emptyList(),
    val exercises: List<RawExercise> = emptyList(),
    val heartRate: List<HeartRateSample> = emptyList(),
    val restingHeartRate: List<HeartRateSample> = emptyList(),
    val hrv: List<HeartRateSample> = emptyList(),
    val respiratoryRate: List<HeartRateSample> = emptyList(),
    val spo2Percent: List<HeartRateSample> = emptyList(),
    val weightKg: Double? = null,
    val bodyFatPercent: Double? = null,
    val leanMassKg: Double? = null,
    val sleep: List<SleepInterval> = emptyList()
)

/** Builds the same [HealthSnapshot] iOS builds from Apple Health (`HealthKitService.snapshot`). */
object HealthSnapshotBuilder {
    const val ACTIVITY_DAYS = 7
    const val LOAD_DAYS = 28
    const val BASELINE_DAYS = 30

    fun build(raw: HealthRawData, now: Instant, sourceName: String): HealthSnapshot {
        val today = now.startOfDay()
        val days = (0 until ACTIVITY_DAYS).map { today.addingDays(it - (ACTIVITY_DAYS - 1)) }
        fun dayOf(instant: Instant) = instant.startOfDay()
        fun sums(values: List<TimedValue>) = values.groupBy { dayOf(it.start) }.mapValues { (_, list) -> list.sumOf { it.value } }

        val steps = sums(raw.steps)
        val distance = sums(raw.distanceMeters)
        val energy = sums(raw.activeKcal)
        val exerciseMinutes = raw.exercises.groupBy { dayOf(it.start) }.mapValues { (_, list) -> list.sumOf { minutesBetween(it.start, it.end) } }
        val brisk = briskMinutesPerDay(raw.steps)
        val activeMinutes = days.map { DailyValue(it, max(exerciseMinutes[it] ?: 0.0, (brisk[it] ?: 0).toDouble())) }

        val todaySteps = (steps[today] ?: 0.0).toInt()
        val recordedKcal = (energy[today] ?: 0.0).roundToIntSwift()
        val summary = HealthDaySummary(
            steps = todaySteps,
            distanceKm = (distance[today] ?: 0.0) / 1000,
            activeKcal = if (recordedKcal > 0) recordedKcal else FitnessMath.estimatedActiveKcal(todaySteps, raw.weightKg),
            activeMinutes = activeMinutes.last().value.toInt()
        )

        val todayHeart = raw.heartRate.filter { it.date >= today && it.date <= now }.map { it.bpm }
        val loadFrom = today.addingDays(-(LOAD_DAYS - 1))
        val workouts = raw.exercises.filter { it.start >= loadFrom && it.start <= now }.sortedBy { it.start }.map { workout(it, sourceName) }

        return HealthSnapshot(
            today = summary,
            weeklyActiveMinutes = activeMinutes,
            workouts = workouts.filter { it.date >= days.first() },
            heartRateZones = FitnessMath.zones(todayHeart, FitnessMath.maxHeartRate(FitnessMath.age(null, now))),
            averageHeartRate = StatisticsMath.mean(todayHeart)?.roundToIntSwift(),
            restingHeartRate = raw.restingHeartRate.maxByOrNull { it.date }?.bpm?.roundToIntSwift(),
            hrv = raw.hrv.maxByOrNull { it.date }?.bpm?.roundToIntSwift(),
            bodyMetrics = listOfNotNull(
                raw.weightKg?.let { BodyMetric("Вес", it, "кг") },
                raw.bodyFatPercent?.let { BodyMetric("Жир", it, "%") },
                raw.leanMassKg?.let { BodyMetric("Сух. масса", it, "кг") }
            ),
            fetchedAt = now,
            sleepIntervals = raw.sleep.filter { it.end >= today.addingDays(-BASELINE_DAYS) }.sortedBy { it.start },
            nightVitals = nightVitals(SleepMath.nights(raw.sleep).lastOrNull(), raw),
            restingHeartRateByDay = dailyAverages(raw.restingHeartRate, today.addingDays(-(BASELINE_DAYS - 1)), now),
            hrvByDay = dailyAverages(raw.hrv, today.addingDays(-(BASELINE_DAYS - 1)), now),
            loadWorkouts = workouts
        )
    }

    /** Without a watch there is no exercise time: count minutes with ≥ 100 steps (records spread evenly over their minutes). */
    private fun briskMinutesPerDay(steps: List<TimedValue>): Map<Instant, Int> {
        val perMinute = mutableMapOf<Long, Double>()
        for (record in steps) {
            val minutes = max(minutesBetween(record.start, record.end).roundToIntSwift(), 1)
            val share = record.value / minutes
            val first = record.start.epochSeconds / 60
            for (offset in 0 until minutes) perMinute[first + offset] = (perMinute[first + offset] ?: 0.0) + share
        }
        return perMinute.entries.groupBy { Instant.fromEpochSeconds(it.key * 60).startOfDay() }
            .mapValues { (_, list) -> FitnessMath.briskMinutes(list.map { it.value }) }
    }

    /** Daily average of a discrete metric, only days with data, oldest first. */
    private fun dailyAverages(samples: List<HeartRateSample>, from: Instant, to: Instant): List<DailyValue> =
        samples.filter { it.date >= from && it.date <= to }.groupBy { it.date.startOfDay() }.toSortedMap(compareBy { it })
            .mapNotNull { (day, list) -> StatisticsMath.mean(list.map { it.bpm })?.let { DailyValue(day, it) } }

    private fun nightVitals(night: List<SleepInterval>?, raw: HealthRawData): SleepMath.NightVitals? {
        val start = night?.minOfOrNull { it.start } ?: return null
        val end = night.maxOf { it.end }
        fun inside(samples: List<HeartRateSample>) = samples.filter { it.date >= start && it.date <= end }.sortedBy { it.date }
        fun average(samples: List<HeartRateSample>) = StatisticsMath.mean(inside(samples).map { it.bpm })?.roundToIntSwift()
        val baselineFrom = end.startOfDay().addingDays(-BASELINE_DAYS)
        return SleepMath.NightVitals(
            heartRate = inside(raw.heartRate),
            restingHeartRate = raw.restingHeartRate.maxByOrNull { it.date }?.bpm?.roundToIntSwift(),
            restingBaseline = StatisticsMath.mean(dailyAverages(raw.restingHeartRate, baselineFrom, end.startOfDay()).map { it.value }),
            hrv = average(raw.hrv),
            respiratoryRate = average(raw.respiratoryRate),
            spo2 = average(raw.spo2Percent)
        )
    }

    private fun workout(raw: RawExercise, sourceName: String) = Workout(
        id = stableId(raw.id),
        kind = raw.kind,
        detail = sourceName,
        date = raw.start,
        minutes = max(minutesBetween(raw.start, raw.end).roundToIntSwift(), 1),
        kcal = (raw.kcal ?: 0.0).roundToIntSwift(),
        averageHeartRate = (raw.averageHeartRate ?: 0.0).roundToIntSwift()
    )

    /** Platform ids are UUIDs on Health Connect; anything else gets a deterministic id so effort ratings stick to the workout. */
    private fun stableId(id: String): Uuid = runCatching { Uuid.parse(id) }.getOrElse {
        var a = 1125899906842597L
        var b = 7L
        for (char in id) {
            a = 31 * a + char.code
            b = 131 * b + char.code
        }
        Uuid.fromLongs(a, b)
    }
}

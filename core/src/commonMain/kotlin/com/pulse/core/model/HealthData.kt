package com.pulse.core.model

import com.pulse.core.util.Tint
import com.pulse.core.util.localDate
import com.pulse.core.util.roundToIntSwift
import kotlinx.datetime.LocalDate
import kotlinx.datetime.yearsUntil
import kotlin.time.Clock
import kotlin.time.Instant

/** One day of activity read from Health Connect / HealthKit. */
data class HealthDaySummary(
    val steps: Int,
    val distanceKm: Double,
    val activeKcal: Int,
    /** Exercise minutes from a watch or, without one, minutes of brisk walking. */
    val activeMinutes: Int
)

/** Everything the fitness screens need from the health store, fetched in one pass. */
data class HealthSnapshot(
    val today: HealthDaySummary,
    /** Active minutes per day, oldest first, last element is today. */
    val weeklyActiveMinutes: List<DailyValue>,
    val workouts: List<Workout>,
    /** Share of today's heart-rate samples per zone; null when there are no samples (no watch). */
    val heartRateZones: List<HeartRateZone>?,
    val averageHeartRate: Int?,
    val restingHeartRate: Int?,
    val hrv: Int?,
    val bodyMetrics: List<BodyMetric>,
    val fetchedAt: Instant,
    /** Raw sleep intervals for the last 30 days (phases need a watch / tracker). */
    val sleepIntervals: List<SleepInterval> = emptyList(),
    /** Body metrics recorded during the most recent night. */
    val nightVitals: SleepMath.NightVitals? = null,
    /** Daily averages for the last 30 days — personal baselines for recovery. */
    val restingHeartRateByDay: List<DailyValue> = emptyList(),
    val hrvByDay: List<DailyValue> = emptyList(),
    /** Workouts of the last 28 days for training load (`workouts` keeps the last 7 for the screens). */
    val loadWorkouts: List<Workout> = emptyList()
)

/** Pure formulas behind the fitness numbers. */
object FitnessMath {
    /** A minute with at least this many steps counts as brisk walking (≈ moderate intensity). */
    const val BRISK_STEPS_PER_MINUTE = 100.0
    /** Rough active energy per step for a 70 kg person. */
    const val KCAL_PER_STEP = 0.04
    const val REFERENCE_WEIGHT_KG = 70.0
    /** Typical age of esports players, used when Health has no birth date. */
    const val DEFAULT_AGE = 21

    fun briskMinutes(stepsPerMinute: List<Double>): Int = stepsPerMinute.count { it >= BRISK_STEPS_PER_MINUTE }

    /** Fallback when the device does not record active energy. */
    fun estimatedActiveKcal(steps: Int, weightKg: Double?): Int {
        val weight = weightKg ?: REFERENCE_WEIGHT_KG
        return (steps * KCAL_PER_STEP * weight / REFERENCE_WEIGHT_KG).roundToIntSwift()
    }

    /** Tanaka formula: 208 − 0.7 × age. */
    fun maxHeartRate(age: Int): Double = 208 - 0.7 * age

    fun age(birthDate: LocalDate?, now: Instant = Clock.System.now()): Int {
        birthDate ?: return DEFAULT_AGE
        val years = birthDate.yearsUntil(now.localDate())
        return if (years in 10..90) years else DEFAULT_AGE
    }

    data class ZoneBound(val name: String, val range: String, val upper: Double, val tint: Tint)

    /** Zone boundaries as a share of max heart rate, matching the mockup's five zones. */
    val zoneBounds = listOf(
        ZoneBound("Покой", "< 60%", 0.6, Tint.REST_ZONE),
        ZoneBound("Жиросжигание", "60–70%", 0.7, Tint.CYAN),
        ZoneBound("Кардио", "70–80%", 0.8, Tint.GREEN),
        ZoneBound("Пик", "80–90%", 0.9, Tint.ORANGE),
        ZoneBound("Максимум", "> 90%", Double.POSITIVE_INFINITY, Tint.RED)
    )

    fun zoneIndex(bpm: Double, maxHeartRate: Double): Int {
        val share = bpm / maxHeartRate
        return zoneBounds.indexOfFirst { share < it.upper }.takeIf { it >= 0 } ?: (zoneBounds.size - 1)
    }

    /** Percent of samples in each zone; percentages sum to 100 (largest-remainder rounding). */
    fun zones(samples: List<Double>, maxHeartRate: Double): List<HeartRateZone>? {
        if (samples.isEmpty() || maxHeartRate <= 0) return null
        val counts = IntArray(zoneBounds.size)
        for (bpm in samples) counts[zoneIndex(bpm, maxHeartRate)] += 1
        val exact = counts.map { it.toDouble() / samples.size * 100 }
        val percents = exact.map { it.toInt() }.toMutableList()
        val missing = 100 - percents.sum()
        // Stable sort by remainder, larger first (ties keep zone order, like Swift's sort on these inputs).
        val order = exact.indices.sortedByDescending { exact[it] - percents[it] }
        for (index in order.take(missing)) percents[index] += 1
        return zoneBounds.indices.map { HeartRateZone(zoneBounds[it].name, zoneBounds[it].range, percents[it], zoneBounds[it].tint) }
    }
}

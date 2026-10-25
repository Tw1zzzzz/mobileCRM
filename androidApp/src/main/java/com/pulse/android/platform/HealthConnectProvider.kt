package com.pulse.android.platform

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.pulse.core.feature.HealthRawData
import com.pulse.core.feature.HealthSnapshotBuilder
import com.pulse.core.feature.RawExercise
import com.pulse.core.feature.TimedValue
import com.pulse.core.model.BodyProfile
import com.pulse.core.model.HealthSnapshot
import com.pulse.core.model.HeartRateSample
import com.pulse.core.model.SleepInterval
import com.pulse.core.model.SleepStage
import com.pulse.core.model.WorkoutKind
import com.pulse.core.store.HealthDataProvider
import com.pulse.core.util.addingDays
import com.pulse.core.util.startOfDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/**
 * Read-only Health Connect access (Android twin of iOS `HealthKitService`): records and aggregates of up to 30 days are
 * read into plain core types and assembled by [HealthSnapshotBuilder]. Partially granted permissions just leave parts empty.
 */
class HealthConnectProvider(private val context: Context) : HealthDataProvider {
    override val name = "Health Connect"

    override val isAvailable: Boolean
        get() = HealthConnectAvailability.state(context) == HealthCardState.AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(context, HealthConnectAvailability.PROVIDER_PACKAGE) }

    /** Granted through the system screen launched by the UI; here we only check that something was allowed. */
    override suspend fun requestAuthorization() {
        if (client.permissionController.getGrantedPermissions().intersect(PERMISSIONS).isEmpty()) {
            throw IllegalStateException("Health Connect: no permissions granted")
        }
    }

    /**
     * Steps, distance and kcal are deduplicated aggregates (a phone and a watch both write steps; summing records
     * would count them twice). Each type is read once, over the window it is needed for, newest first when capped;
     * conversion runs off the main thread.
     */
    override suspend fun snapshot(now: Instant, body: BodyProfile?): HealthSnapshot = withContext(Dispatchers.Default) {
        val granted = client.permissionController.getGrantedPermissions()
        fun canRead(type: KClass<out Record>) = HealthPermission.getReadPermission(type) in granted
        val today = now.startOfDay()
        val baselineFrom = today.addingDays(-HealthSnapshotBuilder.BASELINE_DAYS)
        // Today's zones and last night's vitals only need samples from yesterday on.
        val nightFrom = today.addingDays(-1)
        suspend fun <T : Record> read(type: KClass<T>, from: Instant): List<T> = if (canRead(type)) readAll(type, from, now) else emptyList()
        suspend fun <T : Record> latest(type: KClass<T>): T? = if (canRead(type)) {
            client.readRecords(ReadRecordsRequest(type, range(baselineFrom, now), ascendingOrder = false, pageSize = 1)).records.firstOrNull()
        } else null

        val days = dailyTotals(today.addingDays(-(HealthSnapshotBuilder.ACTIVITY_DAYS - 1)), now, ::canRead)
        val weight = latest(WeightRecord::class)
        val raw = HealthRawData(
            steps = days.steps,
            stepMinutes = stepMinutes(today.addingDays(-(HealthSnapshotBuilder.ACTIVITY_DAYS - 1)), now, canRead(StepsRecord::class)),
            distanceMeters = days.distance,
            activeKcal = days.kcal,
            exercises = exercises(read(ExerciseSessionRecord::class, today.addingDays(-(HealthSnapshotBuilder.LOAD_DAYS - 1))), ::canRead),
            heartRate = read(HeartRateRecord::class, nightFrom).flatMap { record -> record.samples.map { HeartRateSample(it.time.toKotlinInstant(), it.beatsPerMinute.toDouble()) } },
            restingHeartRate = read(RestingHeartRateRecord::class, baselineFrom).map { HeartRateSample(it.time.toKotlinInstant(), it.beatsPerMinute.toDouble()) },
            hrv = read(HeartRateVariabilityRmssdRecord::class, baselineFrom).map { HeartRateSample(it.time.toKotlinInstant(), it.heartRateVariabilityMillis) },
            respiratoryRate = read(RespiratoryRateRecord::class, nightFrom).map { HeartRateSample(it.time.toKotlinInstant(), it.rate) },
            spo2Percent = read(OxygenSaturationRecord::class, nightFrom).map { HeartRateSample(it.time.toKotlinInstant(), it.percentage.value) },
            weightKg = weight?.weight?.inKilograms,
            weightAt = weight?.time?.toKotlinInstant(),
            bodyFatPercent = latest(BodyFatRecord::class)?.percentage?.value,
            leanMassKg = latest(LeanBodyMassRecord::class)?.mass?.inKilograms,
            sleep = read(SleepSessionRecord::class, baselineFrom).flatMap(::sleepIntervals)
        )
        HealthSnapshotBuilder.build(raw, now, name, body)
    }

    private fun range(from: Instant, to: Instant) = TimeRangeFilter.between(from.toJavaInstant(), to.toJavaInstant())

    /** Newest first, so the cap drops the oldest records rather than today's. */
    private suspend fun <T : Record> readAll(type: KClass<T>, from: Instant, to: Instant): List<T> {
        val records = mutableListOf<T>()
        var token: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(type, range(from, to), ascendingOrder = false, pageSize = 1000, pageToken = token))
            records += response.records
            token = response.pageToken
        } while (token != null && records.size < MAX_RECORDS)
        return records
    }

    private class DailyTotals(val steps: List<TimedValue>, val distance: List<TimedValue>, val kcal: List<TimedValue>)

    /** One deduplicated total per local day; only metrics whose read permission was granted are requested. */
    private suspend fun dailyTotals(from: Instant, to: Instant, canRead: (KClass<out Record>) -> Boolean): DailyTotals {
        val metrics = setOfNotNull<AggregateMetric<*>>(
            StepsRecord.COUNT_TOTAL.takeIf { canRead(StepsRecord::class) },
            DistanceRecord.DISTANCE_TOTAL.takeIf { canRead(DistanceRecord::class) },
            ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL.takeIf { canRead(ActiveCaloriesBurnedRecord::class) }
        )
        if (metrics.isEmpty()) return DailyTotals(emptyList(), emptyList(), emptyList())
        val zone = ZoneId.systemDefault()
        val groups = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics,
                TimeRangeFilter.between(LocalDateTime.ofInstant(from.toJavaInstant(), zone), LocalDateTime.ofInstant(to.toJavaInstant(), zone)),
                Period.ofDays(1)
            )
        )
        fun <T : Any> daily(metric: AggregateMetric<T>, value: (T) -> Double) = groups.mapNotNull { group ->
            group.result[metric]?.let {
                TimedValue(group.startTime.atZone(zone).toInstant().toKotlinInstant(), group.endTime.atZone(zone).toInstant().toKotlinInstant(), value(it))
            }
        }
        return DailyTotals(
            daily(StepsRecord.COUNT_TOTAL) { it.toDouble() },
            daily(DistanceRecord.DISTANCE_TOTAL) { it.inMeters },
            daily(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL) { it.inKilocalories }
        )
    }

    /** Deduplicated steps per minute for brisk walking; Health Connect allows at most [MAX_BUCKETS] buckets per call. */
    private suspend fun stepMinutes(from: Instant, to: Instant, allowed: Boolean): List<TimedValue> {
        if (!allowed) return emptyList()
        val chunk = MAX_BUCKETS.minutes
        return generateSequence(from) { it + chunk }.takeWhile { it < to }.toList().flatMap { start ->
            client.aggregateGroupByDuration(
                AggregateGroupByDurationRequest(setOf(StepsRecord.COUNT_TOTAL), range(start, minOf(start + chunk, to)), Duration.ofMinutes(1))
            ).mapNotNull { bucket ->
                bucket.result[StepsRecord.COUNT_TOTAL]?.let { TimedValue(bucket.startTime.toKotlinInstant(), bucket.endTime.toKotlinInstant(), it.toDouble()) }
            }
        }
    }

    /** Workout kcal and average pulse are aggregated over each session's time (only sessions the load chart can show). */
    private suspend fun exercises(sessions: List<ExerciseSessionRecord>, canRead: (KClass<out Record>) -> Boolean): List<RawExercise> {
        val metrics = setOfNotNull<AggregateMetric<*>>(
            ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL.takeIf { canRead(ActiveCaloriesBurnedRecord::class) },
            HeartRateRecord.BPM_AVG.takeIf { canRead(HeartRateRecord::class) }
        )
        return sessions.map { session ->
            val result = metrics.takeIf { it.isNotEmpty() }?.let { client.aggregate(AggregateRequest(it, TimeRangeFilter.between(session.startTime, session.endTime))) }
            RawExercise(
                session.metadata.id, session.startTime.toKotlinInstant(), session.endTime.toKotlinInstant(), kind(session.exerciseType),
                result?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories?.takeIf { it > 0 },
                result?.get(HeartRateRecord.BPM_AVG)?.toDouble()
            )
        }
    }

    /** «Out of bed» has no stage; unspecified sleep counts as light sleep (same as iOS). */
    private fun sleepIntervals(session: SleepSessionRecord): List<SleepInterval> {
        if (session.stages.isEmpty()) return listOf(SleepInterval(SleepStage.LIGHT, session.startTime.toKotlinInstant(), session.endTime.toKotlinInstant()))
        return session.stages.map { stage ->
            val kind = when (stage.stage) {
                SleepSessionRecord.STAGE_TYPE_DEEP -> SleepStage.DEEP
                SleepSessionRecord.STAGE_TYPE_REM -> SleepStage.REM
                SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> SleepStage.AWAKE
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> null
                else -> SleepStage.LIGHT
            }
            SleepInterval(kind, stage.startTime.toKotlinInstant(), stage.endTime.toKotlinInstant())
        }
    }

    private fun kind(type: Int): WorkoutKind = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> WorkoutKind.STRENGTH
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP -> WorkoutKind.HIIT
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA, ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> WorkoutKind.YOGA
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> WorkoutKind.STRETCH
        else -> WorkoutKind.CARDIO
    }

    companion object {
        private const val MAX_RECORDS = 20_000
        private const val MAX_BUCKETS = 5000

        /** Everything Pulse reads; mirrored in AndroidManifest.xml. */
        val PERMISSIONS: Set<String> = listOf(
            StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class, ExerciseSessionRecord::class,
            HeartRateRecord::class, RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class, RespiratoryRateRecord::class,
            OxygenSaturationRecord::class, WeightRecord::class, BodyFatRecord::class, LeanBodyMassRecord::class, SleepSessionRecord::class
        ).map { HealthPermission.getReadPermission(it) }.toSet()
    }
}

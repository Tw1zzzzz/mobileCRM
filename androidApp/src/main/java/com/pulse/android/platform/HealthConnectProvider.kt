package com.pulse.android.platform

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
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
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.pulse.core.feature.HealthRawData
import com.pulse.core.feature.HealthSnapshotBuilder
import com.pulse.core.feature.RawExercise
import com.pulse.core.feature.TimedValue
import com.pulse.core.model.HealthSnapshot
import com.pulse.core.model.HeartRateSample
import com.pulse.core.model.SleepInterval
import com.pulse.core.model.SleepStage
import com.pulse.core.model.WorkoutKind
import com.pulse.core.store.HealthDataProvider
import com.pulse.core.util.addingDays
import com.pulse.core.util.startOfDay
import kotlin.reflect.KClass
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/**
 * Read-only Health Connect access (Android twin of iOS `HealthKitService`): records of the last 30 days are read into
 * plain core types and assembled by [HealthSnapshotBuilder]. Partially granted permissions just leave parts empty.
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

    override suspend fun snapshot(now: Instant): HealthSnapshot {
        val granted = client.permissionController.getGrantedPermissions()
        val from = now.startOfDay().addingDays(-HealthSnapshotBuilder.BASELINE_DAYS)
        suspend fun <T : Record> read(type: KClass<T>): List<T> =
            if (HealthPermission.getReadPermission(type) in granted) readAll(type, from, now) else emptyList()

        val raw = HealthRawData(
            steps = read(StepsRecord::class).map { TimedValue(it.startTime.toKotlinInstant(), it.endTime.toKotlinInstant(), it.count.toDouble()) },
            distanceMeters = read(DistanceRecord::class).map { TimedValue(it.startTime.toKotlinInstant(), it.endTime.toKotlinInstant(), it.distance.inMeters) },
            activeKcal = read(ActiveCaloriesBurnedRecord::class).map { TimedValue(it.startTime.toKotlinInstant(), it.endTime.toKotlinInstant(), it.energy.inKilocalories) },
            exercises = exercises(read(ExerciseSessionRecord::class), read(ActiveCaloriesBurnedRecord::class), read(HeartRateRecord::class)),
            heartRate = read(HeartRateRecord::class).flatMap { record -> record.samples.map { HeartRateSample(it.time.toKotlinInstant(), it.beatsPerMinute.toDouble()) } },
            restingHeartRate = read(RestingHeartRateRecord::class).map { HeartRateSample(it.time.toKotlinInstant(), it.beatsPerMinute.toDouble()) },
            hrv = read(HeartRateVariabilityRmssdRecord::class).map { HeartRateSample(it.time.toKotlinInstant(), it.heartRateVariabilityMillis) },
            respiratoryRate = read(RespiratoryRateRecord::class).map { HeartRateSample(it.time.toKotlinInstant(), it.rate) },
            spo2Percent = read(OxygenSaturationRecord::class).map { HeartRateSample(it.time.toKotlinInstant(), it.percentage.value) },
            weightKg = read(WeightRecord::class).maxByOrNull { it.time }?.weight?.inKilograms,
            bodyFatPercent = read(BodyFatRecord::class).maxByOrNull { it.time }?.percentage?.value,
            leanMassKg = read(LeanBodyMassRecord::class).maxByOrNull { it.time }?.mass?.inKilograms,
            sleep = read(SleepSessionRecord::class).flatMap(::sleepIntervals)
        )
        return HealthSnapshotBuilder.build(raw, now, name)
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, from: Instant, to: Instant): List<T> {
        val records = mutableListOf<T>()
        var token: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(type, TimeRangeFilter.between(from.toJavaInstant(), to.toJavaInstant()), pageSize = 1000, pageToken = token)
            )
            records += response.records
            token = response.pageToken
        } while (token != null && records.size < 20_000)
        return records
    }

    /** Workout kcal and average pulse come from the calorie and heart-rate records inside the session. */
    private fun exercises(sessions: List<ExerciseSessionRecord>, calories: List<ActiveCaloriesBurnedRecord>, heart: List<HeartRateRecord>): List<RawExercise> =
        sessions.map { session ->
            val inside = { start: java.time.Instant -> !start.isBefore(session.startTime) && start.isBefore(session.endTime) }
            val kcal = calories.filter { inside(it.startTime) }.sumOf { it.energy.inKilocalories }
            val bpm = heart.flatMap { it.samples }.filter { inside(it.time) }.map { it.beatsPerMinute.toDouble() }
            RawExercise(
                session.metadata.id, session.startTime.toKotlinInstant(), session.endTime.toKotlinInstant(), kind(session.exerciseType),
                kcal.takeIf { it > 0 }, bpm.takeIf { it.isNotEmpty() }?.average()
            )
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
        /** Everything Pulse reads; mirrored in AndroidManifest.xml. */
        val PERMISSIONS: Set<String> = listOf(
            StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class, ExerciseSessionRecord::class,
            HeartRateRecord::class, RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class, RespiratoryRateRecord::class,
            OxygenSaturationRecord::class, WeightRecord::class, BodyFatRecord::class, LeanBodyMassRecord::class, SleepSessionRecord::class
        ).map { HealthPermission.getReadPermission(it) }.toSet()
    }
}

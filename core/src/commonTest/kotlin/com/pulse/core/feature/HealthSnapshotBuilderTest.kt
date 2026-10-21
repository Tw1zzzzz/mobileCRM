package com.pulse.core.feature

import com.pulse.core.model.HeartRateSample
import com.pulse.core.model.SleepInterval
import com.pulse.core.model.SleepStage
import com.pulse.core.model.WorkoutKind
import com.pulse.core.model.at
import com.pulse.core.model.pinTestZone
import com.pulse.core.util.addingMinutes
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthSnapshotBuilderTest {
    @BeforeTest fun zone() = pinTestZone()

    private val now = at(2026, 10, 21, 20)

    @Test fun todaySummaryFromRecords() {
        val raw = HealthRawData(
            steps = listOf(
                TimedValue(at(2026, 10, 21, 9), at(2026, 10, 21, 10), 3000.0),
                TimedValue(at(2026, 10, 21, 15), at(2026, 10, 21, 16), 2000.0),
                TimedValue(at(2026, 10, 20, 9), at(2026, 10, 20, 10), 7000.0)
            ),
            distanceMeters = listOf(TimedValue(at(2026, 10, 21, 9), at(2026, 10, 21, 10), 3200.0))
        )
        val snapshot = HealthSnapshotBuilder.build(raw, now, "Health Connect")
        assertEquals(5000, snapshot.today.steps)
        assertEquals(3.2, snapshot.today.distanceKm, 1e-9)
        // No calories recorded: estimated from steps at the reference weight (5000 × 0,04 = 200).
        assertEquals(200, snapshot.today.activeKcal)
        assertEquals(now, snapshot.fetchedAt)
        assertEquals(7, snapshot.weeklyActiveMinutes.size)
    }

    @Test fun activeMinutesUseBriskWalking() {
        val start = at(2026, 10, 21, 7)
        val walk = (0 until 60).map { TimedValue(start.addingMinutes(it), start.addingMinutes(it + 1), 110.0) }
        val slow = (0 until 30).map { TimedValue(at(2026, 10, 21, 12).addingMinutes(it), at(2026, 10, 21, 12).addingMinutes(it + 1), 40.0) }
        val snapshot = HealthSnapshotBuilder.build(HealthRawData(steps = walk + slow), now, "Health Connect")
        assertEquals(60, snapshot.today.activeMinutes)
        assertEquals(60.0, snapshot.weeklyActiveMinutes.last().value)
    }

    @Test fun exerciseMinutesWinOverBriskWalking() {
        val raw = HealthRawData(
            exercises = listOf(RawExercise("w1", at(2026, 10, 21, 18), at(2026, 10, 21, 19, 30), WorkoutKind.STRENGTH, 420.0, 128.4))
        )
        val snapshot = HealthSnapshotBuilder.build(raw, now, "Health Connect")
        assertEquals(90, snapshot.today.activeMinutes)
        val workout = snapshot.workouts.single()
        assertEquals(WorkoutKind.STRENGTH, workout.kind)
        assertEquals("Health Connect", workout.detail)
        assertEquals(90, workout.minutes)
        assertEquals(420, workout.kcal)
        assertEquals(128, workout.averageHeartRate)
    }

    @Test fun workoutsMappedAndLimited() {
        val raw = HealthRawData(
            exercises = listOf(
                RawExercise("old", at(2026, 10, 11, 18), at(2026, 10, 11, 19), WorkoutKind.CARDIO, null, null),
                RawExercise("new", at(2026, 10, 20, 18), at(2026, 10, 20, 19), WorkoutKind.CARDIO, null, null)
            )
        )
        val snapshot = HealthSnapshotBuilder.build(raw, now, "Health Connect")
        assertEquals(1, snapshot.workouts.size)
        assertEquals(2, snapshot.loadWorkouts.size)
    }

    @Test fun snapshotWithoutSleepOrHeart() {
        val snapshot = HealthSnapshotBuilder.build(HealthRawData(), now, "Health Connect")
        assertNull(snapshot.heartRateZones)
        assertNull(snapshot.averageHeartRate)
        assertNull(snapshot.nightVitals)
        assertTrue(snapshot.sleepIntervals.isEmpty())
        assertTrue(snapshot.bodyMetrics.isEmpty())
        assertEquals(0, snapshot.today.steps)
    }

    @Test fun nightVitalsFromLastNight() {
        val sleep = listOf(
            SleepInterval(SleepStage.LIGHT, at(2026, 10, 20, 23), at(2026, 10, 21, 2)),
            SleepInterval(SleepStage.DEEP, at(2026, 10, 21, 2), at(2026, 10, 21, 7))
        )
        val heart = listOf(
            HeartRateSample(at(2026, 10, 20, 22), 80.0),
            HeartRateSample(at(2026, 10, 21, 1), 54.0),
            HeartRateSample(at(2026, 10, 21, 4), 50.0),
            HeartRateSample(at(2026, 10, 21, 18), 120.0)
        )
        val raw = HealthRawData(
            heartRate = heart, sleep = sleep,
            hrv = listOf(HeartRateSample(at(2026, 10, 21, 3), 62.0)),
            spo2Percent = listOf(HeartRateSample(at(2026, 10, 21, 3), 96.4))
        )
        val snapshot = HealthSnapshotBuilder.build(raw, now, "Health Connect")
        val vitals = assertNotNull(snapshot.nightVitals)
        assertEquals(listOf(54.0, 50.0), vitals.heartRate.map { it.bpm })
        assertEquals(62, vitals.hrv)
        assertEquals(96, vitals.spo2)
        assertEquals(2, snapshot.sleepIntervals.size)
        // Today's zones use today's samples only (night samples after midnight count as today).
        assertNotNull(snapshot.heartRateZones)
    }

    @Test fun bodyMetricsAndBaselines() {
        val raw = HealthRawData(
            weightKg = 72.5, bodyFatPercent = 14.0, leanMassKg = 60.0,
            restingHeartRate = listOf(HeartRateSample(at(2026, 10, 19, 8), 58.0), HeartRateSample(at(2026, 10, 21, 8), 55.0)),
            hrv = listOf(HeartRateSample(at(2026, 10, 20, 3), 60.0), HeartRateSample(at(2026, 10, 20, 4), 70.0))
        )
        val snapshot = HealthSnapshotBuilder.build(raw, now, "Health Connect")
        assertEquals(listOf("Вес" to 72.5, "Жир" to 14.0, "Сух. масса" to 60.0), snapshot.bodyMetrics.map { it.title to it.value })
        assertEquals(55, snapshot.restingHeartRate)
        assertEquals(listOf(58.0, 55.0), snapshot.restingHeartRateByDay.map { it.value })
        assertEquals(listOf(65.0), snapshot.hrvByDay.map { it.value })
        // Like iOS `latest(.heartRateVariabilitySDNN)`: the most recent sample, not the day average.
        assertEquals(70, snapshot.hrv)
    }
}

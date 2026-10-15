package com.pulse.core.model

import com.pulse.core.util.addingDays
import com.pulse.core.util.addingMinutes
import com.pulse.core.util.setting
import com.pulse.core.util.startOfDay
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Ported from PulseTests/HealthTests.swift and SleepLoadRecoveryTests.swift. */
class FitnessMathTests {
    @BeforeTest fun zone() = pinTestZone()

    @Test fun briskMinutesCountOnlyFastWalking() {
        assertEquals(3, FitnessMath.briskMinutes(listOf(0.0, 40.0, 99.0, 100.0, 130.0, 180.0)))
        assertEquals(0, FitnessMath.briskMinutes(emptyList()))
    }

    @Test fun kcalEstimateScalesWithWeight() {
        assertEquals(400, FitnessMath.estimatedActiveKcal(10_000, null))
        assertEquals(500, FitnessMath.estimatedActiveKcal(10_000, 87.5))
    }

    @Test fun maxHeartRateAndAge() {
        assertEquals(194.0, FitnessMath.maxHeartRate(20))
        assertEquals(21, FitnessMath.age(LocalDate(2005, 1, 1), at(2026, 10, 14)))
        assertEquals(FitnessMath.DEFAULT_AGE, FitnessMath.age(null))
    }

    @Test fun zonesSplitSamplesAndSumTo100() {
        // max HR 200: 100 → покой, 130 → жиросжигание, 150 → кардио, 170 → пик, 190 → максимум.
        val zones = assertNotNull(FitnessMath.zones(listOf(100.0, 100.0, 130.0, 150.0, 170.0, 190.0), 200.0))
        assertEquals(100, zones.sumOf { it.percent })
        assertEquals(listOf("Покой", "Жиросжигание", "Кардио", "Пик", "Максимум"), zones.map { it.name })
        assertEquals(33, zones[0].percent)
        assertNull(FitnessMath.zones(emptyList(), 200.0))
    }
}

class SleepMathTests {
    @BeforeTest fun zone() = pinTestZone()

    private val bed = Clock.System.now().startOfDay().addingDays(-1).setting(23, 0)

    private fun interval(stage: SleepStage?, from: Int, to: Int) = SleepInterval(stage, bed.addingMinutes(from), bed.addingMinutes(to))

    private val night
        get() = listOf(
            interval(null, 0, 480),
            interval(SleepStage.LIGHT, 15, 60),
            interval(SleepStage.DEEP, 60, 120),
            interval(SleepStage.LIGHT, 120, 200),
            interval(SleepStage.AWAKE, 200, 210),
            interval(SleepStage.REM, 210, 270),
            interval(SleepStage.LIGHT, 270, 400),
            interval(SleepStage.REM, 400, 460),
            interval(SleepStage.AWAKE, 460, 480)
        )

    @Test fun groupsNightsAndDropsNaps() {
        val nap = SleepInterval(SleepStage.LIGHT, bed.addingMinutes(900), bed.addingMinutes(940))
        val groups = SleepMath.nights(night + nap)
        assertEquals(1, groups.size)
        assertEquals(435.0, SleepMath.asleepMinutes(groups[0]))
    }

    @Test fun buildsNightMetrics() {
        val built = assertNotNull(SleepMath.night(night, 480, SleepMath.NightVitals(), listOf(70, 74)))
        assertTrue(built.hasStages)
        assertEquals(15, built.latencyMinutes)
        assertEquals(1, built.awakenings)       // the final wake-up is not counted
        assertEquals(2, built.cycles)           // two REM periods
        assertEquals(91, built.efficiency)      // 435 asleep of 480 in bed
        assertEquals(60, built.minutes(SleepStage.DEEP))
        assertEquals(built.score - 72, built.baselineDelta)
        assertTrue(built.score in 0..100)
    }

    @Test fun scoreRewardsLongerRestorativeSleep() {
        val short = SleepMath.score(300.0, 480, 85, 0.3, 3)
        val good = SleepMath.score(470.0, 480, 93, 0.42, 1)
        assertTrue(good > short)
        assertEquals(100, SleepMath.score(600.0, 480, 100, 0.5, 0))
    }

    @Test fun heartRateCurveAveragesBuckets() {
        val samples = (0 until 40).map { HeartRateSample(bed.addingMinutes(it * 12), (50 + it % 4).toDouble()) }
        val curve = SleepMath.heartRateCurve(samples, bed, bed.addingMinutes(480), buckets = 10)
        assertEquals(10, curve.size)
        assertTrue(curve.all { it in 50..53 })
    }

    @Test fun manualNightHasNoStages() {
        val built = SleepMath.manualNight(SleepSession(Uuid.random(), bed, bed.addingMinutes(450)), 480)
        assertFalse(built.hasStages)
        assertTrue(built.segments.isEmpty())
        assertNull(built.hrv)
    }
}

class TrainingLoadTests {
    @BeforeTest fun zone() = pinTestZone()

    @Test fun estimatedEffortFromPulseOrType() {
        assertEquals(8, TrainingLoadMath.estimatedRPE(WorkoutKind.YOGA, 170, 190.0))
        assertEquals(8, TrainingLoadMath.estimatedRPE(WorkoutKind.HIIT, 0, 190.0))
        assertEquals(2, TrainingLoadMath.estimatedRPE(WorkoutKind.STRETCH, 0, 190.0))
    }

    @Test fun acwrComparesLastWeekWithUsualWeek() {
        val now = Clock.System.now()
        // 3 weeks of 300/day, then a week of 600/day → acute 4 200, chronic weekly (3·2 100 + 4 200) / 4 = 2 625.
        val entries = (0 until 28).map { offset ->
            TrainingLoadMath.LoadEntry(now.startOfDay().addingDays(-offset).setting(18, 0), if (offset < 7) 600.0 else 300.0)
        }
        val daily = TrainingLoadMath.dailyLoads(entries, 28, now)
        assertEquals(28, daily.size)
        val ratio = TrainingLoadMath.ratio(daily)
        assertEquals(4200.0, ratio.acute)
        assertEquals(2625.0, ratio.chronicWeekly)
        assertTrue(abs((ratio.value ?: 0.0) - 1.6) < 0.0001)
        assertEquals(LoadStatus.HIGH, ratio.status)
        assertEquals(LoadStatus.UNKNOWN, TrainingLoadMath.ratio(emptyList()).status)
        assertEquals(LoadStatus.OPTIMAL, LoadStatus.of(1.0))
        assertEquals(LoadStatus.LOW, LoadStatus.of(0.5))
    }
}

class RecoveryTests {
    @Test fun goodNumbersGiveHighRecovery() {
        val result = assertNotNull(RecoveryMath.compute(RecoveryMath.Input(66.0, 55.0, 54.0, 58.0, 88, 8.0)))
        assertTrue(result.score >= 80)
        assertEquals("Готов к нагрузке", result.label)
        assertEquals(4, result.components.size)
    }

    @Test fun badNightAndHighPulseGiveLowRecovery() {
        val result = assertNotNull(RecoveryMath.compute(RecoveryMath.Input(40.0, 60.0, 66.0, 58.0, 45, 3.0)))
        assertTrue(result.score < 34)
        assertEquals("Нужен отдых", result.label)
    }

    @Test fun missingPartsAreSkipped() {
        assertEquals(70, RecoveryMath.compute(RecoveryMath.Input(sleepScore = 70))?.score)
        assertNull(RecoveryMath.compute(RecoveryMath.Input()))
    }
}

class GoalsTests {
    @BeforeTest fun zone() = pinTestZone()

    private fun summary(kills: Int, deaths: Int, adr: Double, won: List<Boolean>): GameSummary = GameSummary.of(
        won.mapIndexed { index, win ->
            GameMatch(Uuid.random(), Clock.System.now().addingDays(-index), CSMap.MIRAGE, win,
                if (win) 13 else 9, if (win) 9 else 13, kills, deaths, 3, adr, if (win) 25 else -25)
        }
    )

    @Test fun eloProgressCountsFromStartingPoint() {
        val row = GoalMath.progress(GameGoals(elo = 3000, eloStart = 2800), 2900, summary(20, 20, 80.0, listOf(true))).first()
        assertEquals(GoalProgress.Metric.ELO, row.metric)
        assertTrue(abs(row.progress - 0.5) < 0.0001)
        assertFalse(row.isReached)
        assertTrue(row.note.contains("100"))
    }

    @Test fun reachedGoalsAreFull() {
        val rows = GoalMath.progress(GameGoals(elo = 2500, kd = 1.0, adr = 70.0, winRate = 50), 2600,
            summary(24, 20, 85.0, listOf(true, true, false)))
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.isReached && it.progress == 1.0 })
    }

    @Test fun emptyGoalsShowNothing() {
        assertTrue(GoalMath.progress(GameGoals(), 2000, summary(10, 10, 70.0, listOf(true))).isEmpty())
        assertTrue(GameGoals().isEmpty)
    }
}

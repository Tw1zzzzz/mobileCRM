package com.pulse.core.model

import com.pulse.core.util.addingDays
import com.pulse.core.util.localDateTime
import com.pulse.core.util.setting
import com.pulse.core.util.addingMinutes
import kotlinx.datetime.DayOfWeek
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

/** Ported from PulseTests/WeeklyReportTests.swift and PlayerSurveyTests.swift. */
class WeekRangeTests {
    @BeforeTest fun zone() = pinTestZone()

    private val wednesday = at(2025, 10, 15)

    @Test fun startsOnMondayAndSpansSevenDays() {
        val week = WeekRange.containing(wednesday)
        assertEquals(DayOfWeek.MONDAY, week.start.localDateTime().dayOfWeek)
        assertEquals(7, week.days.size)
        assertEquals(week.start.addingDays(7), week.end)
        assertTrue(week.contains(wednesday))
        assertFalse(week.contains(week.end))
    }

    @Test fun navigatesBetweenWeeks() {
        val week = WeekRange.containing(wednesday)
        assertEquals(week.start, week.previous.end)
        assertEquals(week.end, week.next.start)
        assertEquals(week, week.previous.next)
    }

    @Test fun titles() {
        assertTrue(WeekRange.containing(wednesday).title.startsWith("13–19"))
        val crossing = WeekRange.containing(at(2025, 10, 1))
        assertEquals("29 сентября – 5 октября", crossing.title)
    }

    @Test fun currentWeekCountsOnlyElapsedDays() {
        val thursday = at(2025, 10, 16, 9)
        val week = WeekRange.containing(thursday)
        assertEquals(4, week.elapsedDays(thursday))
        assertEquals(7, week.previous.elapsedDays(thursday))
        assertEquals(0, week.next.elapsedDays(thursday))
        assertEquals(100, WeeklyReportBuilder.score(480.0, 480, null, null, null, activeDays = 4, elapsedDays = 4))
    }
}

class WeeklyReportBuilderTests {
    @BeforeTest fun zone() = pinTestZone()

    private val week get() = WeekRange.containing(at(2025, 10, 15))

    private fun sleep(day: kotlin.time.Instant, minutes: Int): SleepSession {
        val end = day.setting(8, 0)
        return SleepSession(Uuid.random(), end.addingMinutes(-minutes), end)
    }

    private fun mood(day: kotlin.time.Instant, mood: Int, energy: Int) = MoodEntry(Uuid.random(), day, DaySlot.MORNING, mood, energy)

    private fun cognitive(day: kotlin.time.Instant, score: Int) =
        CognitiveResult(Uuid.random(), CognitiveTestKind.REACTION, day.setting(10, 0), score, "")

    private val input: WeeklyInputs
        get() = WeeklyInputs(
            sleepGoalMinutes = 480,
            // Last week: 6 h nights, low mood; this week: 7 h 30 m, better mood.
            sleep = week.previous.days.map { sleep(it, 360) } + week.days.map { sleep(it, 450) },
            mood = week.previous.days.map { mood(it, 5, 5) } + week.days.map { mood(it, 7, 6) },
            cognitive = listOf(cognitive(week.previous.start, 60), cognitive(week.start, 70), cognitive(week.start.addingDays(2), 74)),
            screenLimitMinutes = 240,
            screenMinutes = week.days.mapIndexed { index, day -> DailyValue(day, if (index < 3) 300.0 else 200.0) }
        )

    private fun build(input: WeeklyInputs) = WeeklyReportBuilder.build(input, week, now = at(2026, 1, 1))

    @Test fun emptyInputGivesEmptyReport() {
        val report = build(WeeklyInputs())
        assertTrue(report.isEmpty)
        assertNull(report.score)
        assertEquals(0, report.activeDays)
    }

    @Test fun comparesWithPreviousWeek() {
        val report = build(input)
        val sleepMetric = assertNotNull(report.metrics.firstOrNull { it.id == "sleep" })
        assertEquals(WeeklyMetric.Trend.BETTER, sleepMetric.trend)
        assertTrue(sleepMetric.change?.startsWith("+") == true)
        assertEquals(WeeklyMetric.Trend.BETTER, report.metrics.first { it.id == "mood" }.trend)
        assertEquals(7, report.activeDays)
        assertTrue(report.score!! > report.previousScore!!)
        assertTrue(report.wins.any { it.startsWith("Сон") })
    }

    @Test fun screenOverLimitBecomesTipAndGoal() {
        val report = build(input)
        val screenGoal = report.goals.first { it.id == "screen" }
        assertEquals(4, screenGoal.achieved)
        assertEquals(7, screenGoal.total)
        assertTrue(report.tips.any { it.contains("Лимит экранного времени") })
        assertEquals(7, report.goals.first { it.id == "sleep" }.achieved)
    }

    @Test fun tipsAreCappedAtThree() {
        val report = build(input.copy(sleep = week.days.map { sleep(it, 300) }, mood = week.days.map { mood(it, 4, 3) }))
        assertEquals(3, report.tips.size)
        assertTrue(report.tips[0].contains("До нормы сна"))
    }

    @Test fun questionnaireGoalsComeFirst() {
        val base = WeeklyInputs(sleep = week.days.map { sleep(it, 300) })
        assertTrue(build(base).tips.first().contains("До нормы сна"))
        assertTrue(build(base.copy(focus = listOf(FocusGoal.NUTRITION))).tips.first().contains("Записывай еду"))
    }

    @Test fun scoreCombinesAvailableParts() {
        assertNull(WeeklyReportBuilder.score(null, 480, null, null, null, 5))
        // sleep 100, mood 80, consistency 100 → 93
        assertEquals(93, WeeklyReportBuilder.score(480.0, 480, 8.0, null, null, 7))
    }
}

class TeamWeeklyBuilderTests {
    private fun row(name: String, readiness: Double, previous: Double, sleep: Int, kd: Double = 1.1) = TeamWeeklyRow(
        Uuid.random(), name, readiness, previous, sleep, sleep - 30, 3, 50.0, kd, kd - 0.1, TrainingLoadMath.ratio(emptyList())
    )

    @Test fun aggregatesTeamAndFlagsRisks() {
        val rows = listOf(row("alpha", 8.0, 7.0, 480, 1.4), row("bravo", 5.5, 6.0, 350))
        val report = TeamWeeklyBuilder.build(rows, WeekRange.containing(Clock.System.now()), wins = 4, previousWins = 2, previousMatches = 5)
        assertEquals(6.75, report.readiness)
        assertEquals(6, report.matches)
        assertEquals(67, report.winRate?.let { kotlin.math.round(it).toInt() })
        assertEquals(40.0, report.previousWinRate)
        assertEquals("alpha", report.rows.first().nickname)
        assertEquals(1, report.risks.size)
        assertTrue(report.risks[0].startsWith("bravo"))
        assertTrue(report.highlights.any { it.contains("alpha") })
    }
}

class MindsetMathTests {
    private fun answers(value: Int) = MindsetTest.questions.associate { it.id to value }

    @Test fun questionnaireIsBalanced() {
        assertEquals(24, MindsetTest.questions.size)
        assertEquals(24, MindsetTest.questions.map { it.id }.toSet().size)
        for (trait in MindsetTrait.entries) {
            val items = MindsetTest.questions.filter { it.trait == trait }
            assertEquals(4, items.size)
            assertEquals(2, items.count { it.reversed })
        }
    }

    @Test fun reversedItemsAreFlipped() {
        assertTrue(MindsetMath.scores(answers(5)).values.all { it == 50 })
        val best = MindsetTest.questions.associate { it.id to if (it.reversed) 1 else 5 }
        assertTrue(MindsetMath.scores(best).values.all { it == 100 })
    }

    @Test fun ignoresMissingAndInvalidAnswers() {
        assertTrue(MindsetMath.scores(emptyMap()).isEmpty())
        assertTrue(MindsetMath.scores(mapOf("q1" to 9)).isEmpty())
        assertEquals(100, MindsetMath.scores(mapOf("q1" to 5))[MindsetTrait.COMPOSURE])
        assertFalse(MindsetMath.isComplete(mapOf("q1" to 5)))
        assertTrue(MindsetMath.isComplete(answers(3)))
    }

    @Test fun demoAnswersRoundTrip() {
        val target = mapOf(MindsetTrait.COMPOSURE to 81, MindsetTrait.RESILIENCE to 44, MindsetTrait.FOCUS to 88,
            MindsetTrait.TEAMWORK to 63, MindsetTrait.LEADERSHIP to 31, MindsetTrait.DRIVE to 75)
        val scores = MindsetMath.scores(MindsetMath.answers(target))
        target.forEach { (trait, value) -> assertTrue(abs((scores[trait] ?: 0) - value) <= 7, trait.name) }
    }

    @Test fun leadingTraitStrengthsAndGrowthZones() {
        val profile = MindsetProfile(mapOf(MindsetTrait.COMPOSURE to 60, MindsetTrait.RESILIENCE to 40, MindsetTrait.FOCUS to 72,
            MindsetTrait.TEAMWORK to 65, MindsetTrait.LEADERSHIP to 90, MindsetTrait.DRIVE to 50))
        assertEquals("Лидерство", profile.archetype.title)
        assertEquals("Сильнее всего", profile.archetype.caption)
        assertEquals(listOf(MindsetTrait.LEADERSHIP, MindsetTrait.FOCUS), profile.strengths)
        assertEquals(listOf(MindsetTrait.RESILIENCE, MindsetTrait.DRIVE), profile.growthZones)
        assertEquals(listOf(60, 40, 72, 65, 90, 50), profile.orderedScores)
    }

    @Test fun flatProfile() {
        val profile = MindsetProfile(mapOf(MindsetTrait.COMPOSURE to 62, MindsetTrait.RESILIENCE to 58, MindsetTrait.FOCUS to 60,
            MindsetTrait.TEAMWORK to 65, MindsetTrait.LEADERSHIP to 56, MindsetTrait.DRIVE to 61))
        assertEquals("Ровный по всем чертам", profile.archetype.title)
        assertEquals(listOf(MindsetTrait.TEAMWORK), profile.strengths)
        assertTrue(profile.growthZones.isEmpty())
    }

    @Test fun texts() {
        assertEquals("В клатче 1 против 2 я думаю спокойно и по плану.", MindsetTest.questions.first().text)
        assertEquals("Поднять ELO и уровень", FocusGoal.RANK_UP.title)
        assertEquals("4 года", com.pulse.core.util.Formatters.years(4))
    }
}

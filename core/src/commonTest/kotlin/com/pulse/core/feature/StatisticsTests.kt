package com.pulse.core.feature

import com.pulse.core.model.CognitiveOutcome
import com.pulse.core.model.CognitiveTestKind
import com.pulse.core.model.DaySlot
import com.pulse.core.model.MealSource
import com.pulse.core.model.SleepSession
import com.pulse.core.model.at
import com.pulse.core.model.pinTestZone
import com.pulse.core.store.CognitiveStore
import com.pulse.core.store.NutritionStore
import com.pulse.core.store.ScreenTimeStore
import com.pulse.core.store.SleepStore
import com.pulse.core.store.WellbeingStore
import com.pulse.core.util.addingDays
import com.pulse.core.util.addingMinutes
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class StatisticsTests {
    @BeforeTest fun zone() = pinTestZone()

    private val now = at(2026, 10, 20, 18)

    private fun stores(days: Int): Stores {
        val sleep = SleepStore(null, (0 until days).map { offset ->
            val end = at(2026, 10, 20 - offset, 7)
            SleepSession(Uuid.random(), end.addingMinutes(-(360 + offset * 30)), end)
        })
        val wellbeing = WellbeingStore(null, emptyList(), emptyList())
        val cognitive = CognitiveStore(null, emptyList(), emptyList())
        (0 until days).forEach { offset ->
            val day = now.addingDays(-offset)
            wellbeing.saveMood(day, DaySlot.MORNING, 8 - offset % 4, 6)
            cognitive.record(CognitiveTestKind.REACTION, CognitiveOutcome(80 - offset * 5, ""), day)
        }
        val nutrition = NutritionStore(null, seed = emptyList())
        nutrition.addMeal("Обед", 600, 30, 20, 60, source = MealSource.MANUAL, author = null, date = now)
        return Stores(sleep, nutrition, wellbeing, cognitive, ScreenTimeStore(null, isDemo = false))
    }

    private fun build(period: StatsPeriod, stores: Stores) =
        StatisticsData.build(period, stores.sleep, stores.nutrition, stores.wellbeing, stores.cognitive, stores.screenTime, now)

    @Test fun periodDayCount() {
        assertEquals(7, StatsPeriod.Days(7).dayCount(now))
        assertEquals(15, StatsPeriod.Custom(at(2026, 10, 1), at(2026, 10, 15, 9)).dayCount(now))
        assertEquals(1, StatsPeriod.Custom(at(2026, 10, 15), at(2026, 10, 10)).dayCount(now))
    }

    @Test fun periodTitles() {
        assertEquals(listOf("7 дней", "30 дней", "90 дней", "1 год"), StatsPeriod.presets.map { it.title })
    }

    @Test fun seriesCoverOnlyDaysWithData() {
        val data = build(StatsPeriod.Days(7), stores(5))
        assertEquals(7, data.days.size)
        assertEquals(5, data.sleepHours.size)
        assertEquals(5, data.mood.size)
        assertEquals(5, data.cognitive.size)
        assertEquals(1, data.kcal.size)
        assertEquals(600, data.averageKcal)
        assertEquals(1, data.mealCount)
        assertEquals(5, data.sleepCount)
        // Oldest first, today's cognitive score last.
        assertEquals(80.0, data.cognitive.last().value)
    }

    @Test fun correlationsUseMatchingDays() {
        val data = build(StatsPeriod.Days(7), stores(5))
        val sleepToCognitive = data.correlations.first { it.factor == "Сон" && it.metric == "Когнитивная форма" }
        // Sleep grows by 30 min per day back while the score drops by 5 — a perfect inverse relation.
        assertEquals(-1.0, sleepToCognitive.value, 1e-9)
        assertTrue(data.correlations.none { it.factor == "Питание" }, "one meal day is not enough")
    }

    @Test fun emptyWhenNothingRecorded() {
        val empty = Stores(SleepStore(null, emptyList()), NutritionStore(null, seed = emptyList()), WellbeingStore(null, emptyList(), emptyList()),
            CognitiveStore(null, emptyList(), emptyList()), ScreenTimeStore(null, isDemo = false))
        val data = build(StatsPeriod.Days(30), empty)
        assertTrue(data.isEmpty)
        assertNull(data.averageSleep)
        assertTrue(data.correlations.isEmpty())
    }

    private data class Stores(val sleep: SleepStore, val nutrition: NutritionStore, val wellbeing: WellbeingStore, val cognitive: CognitiveStore, val screenTime: ScreenTimeStore)
}

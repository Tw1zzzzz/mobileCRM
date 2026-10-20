package com.pulse.core.feature

import com.pulse.core.model.CorrelationRow
import com.pulse.core.model.DailyValue
import com.pulse.core.model.NutritionTotals
import com.pulse.core.store.CognitiveStore
import com.pulse.core.store.NutritionStore
import com.pulse.core.store.ScreenTimeStore
import com.pulse.core.store.SleepStore
import com.pulse.core.store.WellbeingStore
import com.pulse.core.util.StatisticsMath
import com.pulse.core.util.addingDays
import com.pulse.core.util.daysUntil
import com.pulse.core.util.startOfDay
import kotlin.time.Instant

/** «Статистика» period: a preset of the last N days or a custom range (iOS `StatsPeriod`). */
sealed interface StatsPeriod {
    data class Days(val count: Int) : StatsPeriod
    data class Custom(val start: Instant, val end: Instant) : StatsPeriod

    val title: String
        get() = when (this) {
            is Days -> when (count) {
                7 -> "7 дней"
                30 -> "30 дней"
                90 -> "90 дней"
                365 -> "1 год"
                else -> "$count дн."
            }
            is Custom -> "Свой"
        }

    fun end(now: Instant): Instant = when (this) {
        is Days -> now
        is Custom -> end
    }

    fun dayCount(now: Instant): Int = when (this) {
        is Days -> count
        is Custom -> maxOf(start.startOfDay().daysUntil(end.startOfDay()) + 1, 1)
    }

    companion object {
        val presets: List<StatsPeriod> = listOf(Days(7), Days(30), Days(90), Days(365))
    }
}

/** Per-day series from every module for the selected period (oldest first), plus correlations. */
data class StatisticsData(
    val days: List<Instant>,
    val mood: List<DailyValue>,
    val energy: List<DailyValue>,
    val cognitive: List<DailyValue>,
    val sleepHours: List<DailyValue>,
    val screenHours: List<DailyValue>,
    val kcal: List<DailyValue>,
    val averageSleep: Int?,
    val sleepCount: Int,
    val averageScreenTime: Int,
    val screenTimeChange: Double?,
    val averageKcal: Int?,
    val mealCount: Int
) {
    val isEmpty: Boolean get() = mood.isEmpty() && cognitive.isEmpty() && sleepHours.isEmpty() && kcal.isEmpty()

    /** Pearson correlations between lifestyle factors and outcomes on matching days (≥ 3 pairs). */
    val correlations: List<CorrelationRow>
        get() = listOfNotNull(
            pair("Сон", sleepHours, "Когнитивная форма", cognitive),
            pair("Сон", sleepHours, "Настроение", mood),
            pair("Экранное время", screenHours, "Когнитивная форма", cognitive),
            pair("Экранное время", screenHours, "Энергия", energy),
            pair("Питание", kcal, "Энергия", energy),
            pair("Настроение", mood, "Когнитивная форма", cognitive)
        )

    private fun pair(factor: String, xs: List<DailyValue>, metric: String, ys: List<DailyValue>): CorrelationRow? {
        val lookup = ys.associate { it.date.startOfDay() to it.value }
        val matched = xs.mapNotNull { x -> lookup[x.date.startOfDay()]?.let { x.value to it } }
        val r = StatisticsMath.pearson(matched.map { it.first }, matched.map { it.second }) ?: return null
        return CorrelationRow(factor, metric, r)
    }

    companion object {
        fun build(
            period: StatsPeriod, sleep: SleepStore, nutrition: NutritionStore, wellbeing: WellbeingStore,
            cognitive: CognitiveStore, screenTime: ScreenTimeStore, now: Instant
        ): StatisticsData {
            val end = period.end(now).startOfDay()
            val count = period.dayCount(now)
            val days = (count - 1 downTo 0).map { end.addingDays(-it) }

            val moodPairs = days.mapNotNull { day -> wellbeing.averageMood(day)?.let { day to it } }
            val sleepHours = days.mapNotNull { day -> sleep.minutes(day)?.let { DailyValue(day, it / 60.0) } }

            val screen = screenTime.daily(count * 2, period.end(now))
            val current = screen.takeLast(count)
            val previous = screen.dropLast(count).takeLast(count)
            val averageScreen = (StatisticsMath.mean(current.map { it.value }) ?: 0.0).toInt()

            val kcal = days.mapNotNull { day ->
                val meals = nutrition.meals(day)
                if (meals.isEmpty()) null else DailyValue(day, NutritionTotals.of(meals).kcal.toDouble())
            }
            return StatisticsData(
                days = days,
                mood = moodPairs.map { DailyValue(it.first, it.second.mood) },
                energy = moodPairs.map { DailyValue(it.first, it.second.energy) },
                cognitive = days.mapNotNull { day -> cognitive.averageScore(day)?.let { DailyValue(day, it) } },
                sleepHours = sleepHours,
                screenHours = current.map { DailyValue(it.date, it.value / 60) },
                kcal = kcal,
                averageSleep = StatisticsMath.mean(sleepHours.map { it.value })?.let { (it * 60).toInt() },
                sleepCount = sleepHours.size,
                averageScreenTime = averageScreen,
                screenTimeChange = StatisticsMath.percentChange(StatisticsMath.mean(previous.map { it.value }) ?: 0.0, averageScreen.toDouble()),
                averageKcal = StatisticsMath.mean(kcal.map { it.value })?.toInt(),
                mealCount = days.sumOf { nutrition.meals(it).size }
            )
        }
    }
}

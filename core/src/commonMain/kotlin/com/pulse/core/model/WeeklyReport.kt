package com.pulse.core.model

import com.pulse.core.util.Formatters
import com.pulse.core.util.StatisticsMath
import com.pulse.core.util.Tint
import com.pulse.core.util.addingDays
import com.pulse.core.util.addingMinutes
import com.pulse.core.util.daysUntil
import com.pulse.core.util.localDate
import com.pulse.core.util.roundToIntSwift
import com.pulse.core.util.startOfDay
import com.pulse.core.util.startOfWeek
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Instant

/** A calendar week, Monday to Sunday. */
data class WeekRange(val start: Instant, /** Exclusive: the next Monday. */ val end: Instant) {
    val previous: WeekRange get() = containing(start.addingDays(-1))
    val next: WeekRange get() = containing(end.addingMinutes(1))
    val days: List<Instant> get() = (0 until 7).map { start.addingDays(it) }

    fun contains(date: Instant): Boolean = date >= start && date < end

    fun isCurrent(now: Instant = Clock.System.now()): Boolean = contains(now)

    /** Days of the week that have started by `now` (7 for past weeks, 0 for future ones). */
    fun elapsedDays(now: Instant = Clock.System.now()): Int = when {
        now < start -> 0
        now >= end -> 7
        else -> start.daysUntil(now) + 1
    }

    /** «6–12 октября» or «29 сентября – 5 октября». */
    val title: String
        get() {
            val last = end.addingDays(-1)
            val first = start.localDate()
            val lastDate = last.localDate()
            return if (first.year == lastDate.year && first.month == lastDate.month) {
                "${first.day}–${Formatters.dayMonth(last)}"
            } else {
                "${Formatters.dayMonth(start)} – ${Formatters.dayMonth(last)}"
            }
        }

    companion object {
        fun containing(date: Instant): WeekRange {
            val start = date.startOfWeek()
            return WeekRange(start, start.addingDays(7))
        }
    }
}

/** Everything the personal report needs, as plain values (built from the stores by the UI). */
data class WeeklyInputs(
    val sleep: List<SleepSession> = emptyList(),
    val sleepGoalMinutes: Int = 480,
    val workouts: List<Workout> = emptyList(),
    /** Daily training load covering at least the two compared weeks. */
    val dailyLoad: List<DailyValue> = emptyList(),
    val screenMinutes: List<DailyValue> = emptyList(),
    val screenLimitMinutes: Int = 300,
    val kcal: List<DailyValue> = emptyList(),
    val kcalGoal: Int = 2400,
    val mood: List<MoodEntry> = emptyList(),
    val cognitive: List<CognitiveResult> = emptyList(),
    val matches: List<GameMatch> = emptyList(),
    val elo: List<DailyValue> = emptyList(),
    /** Goals from the questionnaire: matching tips go first. */
    val focus: List<FocusGoal> = emptyList()
)

data class WeeklyMetric(
    val id: String,
    val title: String,
    val icon: String,
    val tint: Tint,
    val value: String,
    val detail: String,
    val change: String?,
    val trend: Trend
) {
    enum class Trend { BETTER, WORSE, SAME, NONE }
}

data class WeeklyGoalCheck(val id: String, val title: String, val achieved: Int, val total: Int) {
    val progress: Double get() = if (total > 0) achieved.toDouble() / total else 0.0
}

data class WeeklyReport(
    val week: WeekRange,
    val score: Int?,
    val previousScore: Int?,
    val activeDays: Int,
    /** 7 for finished weeks, fewer for the current one. */
    val elapsedDays: Int,
    val metrics: List<WeeklyMetric>,
    val goals: List<WeeklyGoalCheck>,
    val wins: List<String>,
    val concerns: List<String>,
    val tips: List<String>
) {
    val isEmpty: Boolean get() = metrics.isEmpty()
}

/** Builds the personal weekly report (pure; no stores here). */
object WeeklyReportBuilder {
    /** Week score 0–100 from what was logged: sleep vs goal, mood, energy, cognitive form, consistency. */
    fun score(
        sleepAverage: Double?, sleepGoal: Int, mood: Double?, energy: Double?, cognitive: Double?,
        activeDays: Int, elapsedDays: Int = 7
    ): Int? {
        val parts = mutableListOf<Double>()
        sleepAverage?.let { parts += min(it / max(sleepGoal, 60).toDouble(), 1.0) * 100 }
        mood?.let { parts += it * 10 }
        energy?.let { parts += it * 10 }
        cognitive?.let { parts += it }
        if (parts.isEmpty()) return null
        val days = max(elapsedDays, 1)
        parts += min(activeDays, days).toDouble() / days * 100
        return (parts.sum() / parts.size).roundToIntSwift()
    }

    fun build(input: WeeklyInputs, week: WeekRange, now: Instant = Clock.System.now()): WeeklyReport {
        val previous = week.previous
        val current = Slice(input, week, week.elapsedDays(now))
        val before = Slice(input, previous, previous.elapsedDays(now))

        val metrics = mutableListOf<WeeklyMetric>()
        val wins = mutableListOf<String>()
        val concerns = mutableListOf<String>()
        fun note(metric: WeeklyMetric?) {
            metric ?: return
            metrics += metric
            val change = metric.change ?: return
            when (metric.trend) {
                WeeklyMetric.Trend.BETTER -> wins += "${metric.title}: $change"
                WeeklyMetric.Trend.WORSE -> concerns += "${metric.title}: $change"
                else -> Unit
            }
        }

        note(sleepMetric(current, before, input.sleepGoalMinutes))
        note(trainingMetric(current, before))
        note(loadMetric(input, week))
        note(screenMetric(current, before, input.screenLimitMinutes))
        note(nutritionMetric(current, before, input.kcalGoal))
        note(moodMetric(current, before))
        note(cognitiveMetric(current, before))
        note(gameMetric(current, before, input.elo, week))

        return WeeklyReport(
            week = week,
            score = current.score(input.sleepGoalMinutes),
            previousScore = before.score(input.sleepGoalMinutes),
            activeDays = current.activeDays,
            elapsedDays = current.elapsedDays,
            metrics = metrics,
            goals = goalChecks(current, input),
            wins = wins.take(3),
            concerns = concerns.take(3),
            tips = tipsFor(current, input, week).take(3)
        )
    }

    /** One week of data. */
    class Slice(input: WeeklyInputs, week: WeekRange, val elapsedDays: Int = 7) {
        val sleepMinutes = input.sleep.filter { week.contains(it.end) }.map { it.durationMinutes }
        val workouts = input.workouts.filter { week.contains(it.date) }
        val screen = input.screenMinutes.filter { week.contains(it.date) && it.value > 0 }.map { it.value }
        val kcal = input.kcal.filter { week.contains(it.date) }.map { it.value }
        val mood = input.mood.filter { week.contains(it.day) }
        val cognitive = input.cognitive.filter { week.contains(it.date) }
        val matches = input.matches.filter { week.contains(it.date) }
        val activeDays: Int = (input.sleep.map { it.end } + input.workouts.map { it.date } + input.kcal.map { it.date } +
            input.mood.map { it.day } + input.cognitive.map { it.date })
            .filter { week.contains(it) }.map { it.startOfDay() }.toSet().size

        val sleepAverage: Double? get() = StatisticsMath.mean(sleepMinutes.map { it.toDouble() })
        val moodAverage: Double? get() = StatisticsMath.mean(mood.map { it.mood.toDouble() })
        val energyAverage: Double? get() = StatisticsMath.mean(mood.map { it.energy.toDouble() })
        val cognitiveAverage: Double? get() = StatisticsMath.mean(cognitive.map { it.score.toDouble() })

        fun score(goal: Int): Int? = score(sleepAverage, goal, moodAverage, energyAverage, cognitiveAverage, activeDays, elapsedDays)
    }

    // Metrics

    private fun trend(now: Double, before: Double?, higherIsBetter: Boolean, threshold: Double): WeeklyMetric.Trend {
        before ?: return WeeklyMetric.Trend.NONE
        val diff = now - before
        if (abs(diff) < threshold) return WeeklyMetric.Trend.SAME
        return if ((diff > 0) == higherIsBetter) WeeklyMetric.Trend.BETTER else WeeklyMetric.Trend.WORSE
    }

    private fun signedDuration(minutes: Int): String = "${if (minutes >= 0) "+" else "−"}${Formatters.duration(abs(minutes))}"

    fun sleepMetric(now: Slice, before: Slice, goal: Int): WeeklyMetric? {
        val average = now.sleepAverage ?: return null
        val previous = before.sleepAverage
        // More sleep is better until an hour above the goal.
        val higherIsBetter = average <= goal + 60
        val diff = previous?.let { (average - it).roundToIntSwift() }
        return WeeklyMetric(
            "sleep", "Сон", "moon", Tint.PURPLE,
            Formatters.duration(average.roundToIntSwift()),
            "в среднем за ночь · ${now.sleepMinutes.size} ноч.",
            diff?.let { signedDuration(it) },
            trend(average, previous, higherIsBetter, 10.0)
        )
    }

    fun trainingMetric(now: Slice, before: Slice): WeeklyMetric? {
        if (now.workouts.isEmpty() && before.workouts.isEmpty()) return null
        val minutes = now.workouts.sumOf { it.minutes }
        val previous = before.workouts.sumOf { it.minutes }
        return WeeklyMetric(
            "training", "Тренировки", "figure.run", Tint.ORANGE,
            "${now.workouts.size} · $minutes мин", "за неделю",
            if (before.workouts.isEmpty()) null else "${Formatters.signed(minutes - previous)} мин",
            trend(minutes.toDouble(), if (before.workouts.isEmpty()) null else previous.toDouble(), true, 15.0)
        )
    }

    fun loadMetric(input: WeeklyInputs, week: WeekRange): WeeklyMetric? {
        val upToWeekEnd = input.dailyLoad.filter { it.date < week.end }
        val thisWeek = upToWeekEnd.filter { week.contains(it.date) }.sumOf { it.value }
        if (thisWeek <= 0) return null
        val ratio = TrainingLoadMath.ratio(upToWeekEnd.takeLast(TrainingLoadMath.CHRONIC_DAYS))
        val status = ratio.status
        return WeeklyMetric(
            "load", "Нагрузка", "bolt.heart", status.tint,
            "${Formatters.grouped(thisWeek.toInt())} ед.",
            ratio.value?.let { "ACWR ${Formatters.decimal(it, 2)} · ${status.title.lowercase()}" } ?: status.title,
            null,
            when (status) {
                LoadStatus.HIGH -> WeeklyMetric.Trend.WORSE
                LoadStatus.OPTIMAL -> WeeklyMetric.Trend.BETTER
                else -> WeeklyMetric.Trend.NONE
            }
        )
    }

    fun screenMetric(now: Slice, before: Slice, limit: Int): WeeklyMetric? {
        val average = StatisticsMath.mean(now.screen) ?: return null
        val previous = StatisticsMath.mean(before.screen)
        val overLimit = now.screen.count { it > limit }
        val diff = previous?.let { (average - it).roundToIntSwift() }
        return WeeklyMetric(
            "screen", "Экранное время", "iphone", Tint.CYAN,
            Formatters.duration(average.roundToIntSwift()),
            if (overLimit == 0) "в день · лимит не превышен" else "в день · лимит превышен $overLimit дн.",
            diff?.let { signedDuration(it) },
            trend(average, previous, false, 15.0)
        )
    }

    fun nutritionMetric(now: Slice, before: Slice, goal: Int): WeeklyMetric? {
        val average = StatisticsMath.mean(now.kcal) ?: return null
        val previous = StatisticsMath.mean(before.kcal)
        // Closer to the goal is better.
        val distance = abs(average - goal)
        val previousDistance = previous?.let { abs(it - goal) }
        return WeeklyMetric(
            "nutrition", "Питание", "fork.knife", Tint.GREEN,
            "${Formatters.grouped(average.roundToIntSwift())} ккал",
            "в среднем · записано ${now.kcal.size} из 7 дней",
            previous?.let { "${Formatters.signedDecimal(average - it, 0)} ккал" },
            trend(-distance, previousDistance?.let { -it }, true, 50.0)
        )
    }

    fun moodMetric(now: Slice, before: Slice): WeeklyMetric? {
        val mood = now.moodAverage ?: return null
        val energy = now.energyAverage ?: return null
        val previous = before.moodAverage
        return WeeklyMetric(
            "mood", "Настроение", "face.smiling", Tint.YELLOW,
            "${Formatters.decimal(mood)} · ${Formatters.decimal(energy)}",
            "настроение · энергия из 10",
            previous?.let { Formatters.signedDecimal(mood - it, 1) },
            trend(mood, previous, true, 0.3)
        )
    }

    fun cognitiveMetric(now: Slice, before: Slice): WeeklyMetric? {
        val average = now.cognitiveAverage ?: return null
        val previous = before.cognitiveAverage
        val days = now.cognitive.map { it.date.startOfDay() }.toSet().size
        return WeeklyMetric(
            "cognitive", "Когнитивная форма", "brain.head.profile", Tint.GREEN,
            "${average.roundToIntSwift()} баллов", "тесты в $days дн.",
            previous?.let { "${Formatters.signedDecimal(average - it, 0)} б." },
            trend(average, previous, true, 2.0)
        )
    }

    fun gameMetric(now: Slice, before: Slice, elo: List<DailyValue>, week: WeekRange): WeeklyMetric? {
        if (now.matches.isEmpty()) return null
        val summary = GameSummary.of(now.matches)
        val previous = if (before.matches.isEmpty()) null else GameSummary.of(before.matches)
        val weekElo = elo.filter { week.contains(it.date) }
        val eloChange = weekElo.lastOrNull()?.value?.let { last ->
            (elo.lastOrNull { it.date < week.start } ?: weekElo.firstOrNull())?.let { (last - it.value).toInt() }
        }
        var detail = "${summary.matches} матчей · победы ${summary.winRate.roundToIntSwift()}%"
        if (eloChange != null) detail += " · ${Formatters.signed(eloChange)} ELO"
        return WeeklyMetric(
            "game", "Игра", "gamecontroller", Tint.CYAN,
            "K/D ${Formatters.decimal(summary.kd, 2)}", detail,
            previous?.let { "K/D ${Formatters.signedDecimal(summary.kd - it.kd, 2)}" },
            trend(summary.kd, previous?.kd, true, 0.05)
        )
    }

    // Goals and tips

    fun goalChecks(now: Slice, input: WeeklyInputs): List<WeeklyGoalCheck> {
        val checks = mutableListOf<WeeklyGoalCheck>()
        if (now.sleepMinutes.isNotEmpty()) {
            checks += WeeklyGoalCheck("sleep", "Ночей с нормой сна",
                now.sleepMinutes.count { it >= input.sleepGoalMinutes - 30 }, now.sleepMinutes.size)
        }
        if (now.screen.isNotEmpty()) {
            checks += WeeklyGoalCheck("screen", "Дней в лимите экрана",
                now.screen.count { it <= input.screenLimitMinutes }, now.screen.size)
        }
        if (now.kcal.isNotEmpty()) {
            val goal = input.kcalGoal.toDouble()
            checks += WeeklyGoalCheck("kcal", "Дней в цели по калориям (±15%)",
                now.kcal.count { abs(it - goal) <= goal * 0.15 }, now.kcal.size)
        }
        checks += WeeklyGoalCheck("days", "Дней с записями", now.activeDays, max(now.elapsedDays, now.activeDays))
        return checks
    }

    fun tipsFor(now: Slice, input: WeeklyInputs, week: WeekRange): List<String> {
        val tips = mutableListOf<Pair<FocusGoal?, String>>()
        now.sleepAverage?.let { sleep ->
            if (sleep < input.sleepGoalMinutes - 30) {
                val missing = Formatters.duration(input.sleepGoalMinutes - sleep.toInt())
                tips += FocusGoal.SLEEP to "До нормы сна не хватает $missing за ночь — попробуй ложиться на 30 минут раньше."
            }
        }
        if (now.screen.count { it > input.screenLimitMinutes } >= 3) {
            tips += FocusGoal.SCREEN to "Лимит экранного времени превышен 3+ дня — убери телефон за час до сна."
        }
        if (loadMetric(input, week)?.trend == WeeklyMetric.Trend.WORSE) {
            tips += FocusGoal.FITNESS to "Нагрузка растёт слишком быстро — запланируй лёгкий день и следи за восстановлением."
        }
        now.energyAverage?.let { energy ->
            if (energy < 5) tips += FocusGoal.ENERGY to "Энергия ниже 5 из 10 — проверь сон, воду и перерывы между игровыми сессиями."
        }
        val mood = now.moodAverage
        if (FocusGoal.TILT in input.focus && mood != null && mood < 6) {
            tips += FocusGoal.TILT to "Настроение ниже 6 из 10 — после поражений делай паузу 5 минут перед следующей игрой."
        }
        if (now.cognitive.map { it.date.startOfDay() }.toSet().size < 3) {
            tips += FocusGoal.FOCUS to "Проходи когнитивную батарею хотя бы 3 раза в неделю — так тренд будет точнее."
        }
        if (now.kcal.size < 4) {
            tips += FocusGoal.NUTRITION to "Записывай еду хотя бы 5 дней в неделю, чтобы видеть реальную картину питания."
        }
        if (tips.isEmpty()) return listOf("Отличная неделя — держи режим и дальше.")
        // Stable: tips for the player's own goals first, the rest keep their order.
        return tips.sortedBy { if (it.first in input.focus) 0 else 1 }.map { it.second }
    }
}

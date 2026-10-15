package com.pulse.core.model

import com.pulse.core.util.Formatters
import com.pulse.core.util.Tint
import com.pulse.core.util.addingDays
import com.pulse.core.util.isSameDay
import com.pulse.core.util.roundToIntSwift
import com.pulse.core.util.startOfDay
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Training load by the session-RPE method: load = perceived effort (1–10) × minutes.
 * ACWR = load of the last 7 days ÷ average weekly load of the last 28 days.
 */
object TrainingLoadMath {
    const val ACUTE_DAYS = 7
    const val CHRONIC_DAYS = 28

    /** Effort guess when the player did not rate the workout: from heart rate, else from the workout type. */
    fun estimatedRPE(kind: WorkoutKind, averageHeartRate: Int, maxHeartRate: Double): Int {
        if (averageHeartRate > 0 && maxHeartRate > 0) {
            val share = averageHeartRate / maxHeartRate
            return when {
                share < 0.6 -> 3
                share < 0.7 -> 4
                share < 0.8 -> 6
                share < 0.9 -> 8
                else -> 9
            }
        }
        return when (kind) {
            WorkoutKind.HIIT -> 8
            WorkoutKind.STRENGTH -> 6
            WorkoutKind.CARDIO -> 5
            WorkoutKind.YOGA, WorkoutKind.STRETCH -> 2
        }
    }

    data class LoadEntry(val date: Instant, val load: Double)

    /** Daily load for the last `days` days, oldest first (days without workouts are 0). */
    fun dailyLoads(entries: List<LoadEntry>, days: Int, now: Instant = Clock.System.now()): List<DailyValue> =
        (days - 1 downTo 0).map { offset ->
            val day = now.startOfDay().addingDays(-offset)
            DailyValue(day, entries.filter { it.date.isSameDay(day) }.sumOf { it.load })
        }

    data class Ratio(
        val acute: Double,
        val chronicWeekly: Double,
        /** null until there is a chronic base to compare with. */
        val value: Double?
    ) {
        val status: LoadStatus get() = value?.let { LoadStatus.of(it) } ?: LoadStatus.UNKNOWN
    }

    fun ratio(daily: List<DailyValue>): Ratio {
        val lastChronic = daily.takeLast(CHRONIC_DAYS)
        val acute = lastChronic.takeLast(ACUTE_DAYS).sumOf { it.value }
        val chronicWeekly = lastChronic.sumOf { it.value } / (CHRONIC_DAYS.toDouble() / ACUTE_DAYS)
        return Ratio(acute, chronicWeekly, if (chronicWeekly > 0) acute / chronicWeekly else null)
    }
}

enum class LoadStatus(val title: String, val tint: Tint, val advice: String) {
    UNKNOWN("Мало данных", Tint.TEXT_SECONDARY, "Записывай тренировки неделю-другую — появится сравнение с обычной нагрузкой."),
    LOW("Недогруз", Tint.CYAN, "Нагрузка ниже обычной. Можно добавить тренировку, если самочувствие хорошее."),
    OPTIMAL("Оптимально", Tint.GREEN, "Нагрузка в зоне 0,8–1,3 — организм успевает адаптироваться."),
    ELEVATED("Повышенная", Tint.YELLOW, "Нагрузка растёт быстрее обычного. Следи за сном и восстановлением."),
    HIGH("Риск перегруза", Tint.RED, "Резкий рост нагрузки — риск перетренированности и травм. Снизь объём на пару дней.");

    companion object {
        fun of(ratio: Double): LoadStatus = when {
            ratio < 0.8 -> LOW
            ratio < 1.3 -> OPTIMAL
            ratio < 1.5 -> ELEVATED
            else -> HIGH
        }
    }
}

/**
 * «Восстановление» 0–100 from the personal 30-day baselines:
 * HRV 40 %, sleep 30 %, resting pulse 20 %, self-reported energy 10 % (missing parts are skipped).
 */
object RecoveryMath {
    data class Component(val id: String, val title: String, val value: Double, val detail: String)

    data class Result(val score: Int, val components: List<Component>) {
        val label: String
            get() = when {
                score >= 67 -> "Готов к нагрузке"
                score >= 34 -> "Умеренная нагрузка"
                else -> "Нужен отдых"
            }

        val tint: Tint
            get() = when {
                score >= 67 -> Tint.GREEN
                score >= 34 -> Tint.YELLOW
                else -> Tint.RED
            }
    }

    data class Input(
        val hrv: Double? = null,
        val hrvBaseline: Double? = null,
        val restingHeartRate: Double? = null,
        val restingBaseline: Double? = null,
        val sleepScore: Int? = null,
        val energy: Double? = null
    )

    fun compute(input: Input): Result? {
        val parts = mutableListOf<Pair<Component, Double>>()
        val hrv = input.hrv
        val hrvBase = input.hrvBaseline
        if (hrv != null && hrvBase != null && hrvBase > 0) {
            // ±25 % against the personal norm spans the whole 0–100 range.
            val value = clamp(50 + 50 * (hrv / hrvBase - 1) / 0.25)
            val change = ((hrv / hrvBase - 1) * 100).roundToIntSwift()
            parts += Component("hrv", "Вариабельность пульса", value,
                "${hrv.roundToIntSwift()} мс · ${if (change >= 0) "+" else ""}$change% к норме") to 0.4
        }
        input.sleepScore?.let { score ->
            parts += Component("sleep", "Сон", clamp(score.toDouble()), "оценка $score") to 0.3
        }
        val resting = input.restingHeartRate
        val restingBase = input.restingBaseline
        if (resting != null && restingBase != null) {
            // +5 bpm above the norm → 0, −5 → 100.
            val value = clamp(50 - 50 * (resting - restingBase) / 5)
            val delta = (resting - restingBase).roundToIntSwift()
            parts += Component("rhr", "Пульс покоя", value,
                "${resting.roundToIntSwift()} уд/мин · ${if (delta >= 0) "+" else ""}$delta к норме") to 0.2
        }
        input.energy?.let { energy ->
            parts += Component("energy", "Самочувствие", clamp(energy * 10), "энергия ${Formatters.decimal(energy)} из 10") to 0.1
        }
        val weight = parts.sumOf { it.second }
        if (weight <= 0) return null
        val score = parts.sumOf { it.first.value * it.second } / weight
        return Result(score.roundToIntSwift(), parts.map { it.first })
    }

    private fun clamp(value: Double): Double = value.coerceIn(0.0, 100.0)
}

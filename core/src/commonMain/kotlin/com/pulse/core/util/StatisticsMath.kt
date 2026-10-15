package com.pulse.core.util

import kotlin.math.abs
import kotlin.math.sqrt

/** Pure statistics helpers used by the analytics screens. */
object StatisticsMath {
    fun mean(values: List<Double>): Double? = if (values.isEmpty()) null else values.sum() / values.size

    /** Pearson correlation coefficient for paired samples; null when undefined. */
    fun pearson(xs: List<Double>, ys: List<Double>): Double? {
        if (xs.size != ys.size || xs.size < 3) return null
        val meanX = mean(xs) ?: return null
        val meanY = mean(ys) ?: return null
        var numerator = 0.0
        var sumX = 0.0
        var sumY = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - meanX
            val dy = ys[i] - meanY
            numerator += dx * dy
            sumX += dx * dx
            sumY += dy * dy
        }
        val denominator = sqrt(sumX * sumY)
        if (denominator <= 0) return null
        return numerator / denominator
    }

    /** Percent change between two values ("-15%"). */
    fun percentChange(from: Double, to: Double): Double? = if (from == 0.0) null else (to - from) / from * 100

    fun clampedProgress(value: Double, goal: Double): Double =
        if (goal <= 0) 0.0 else (value / goal).coerceIn(0.0, 1.0)
}

enum class CorrelationStrength(val title: String) {
    STRONG("сильная"), MODERATE("умеренная"), WEAK("слабая");

    companion object {
        fun of(value: Double): CorrelationStrength = when {
            abs(value) >= 0.6 -> STRONG
            abs(value) >= 0.3 -> MODERATE
            else -> WEAK
        }
    }
}

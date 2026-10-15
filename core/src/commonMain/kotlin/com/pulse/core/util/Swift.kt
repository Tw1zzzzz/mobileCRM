package com.pulse.core.util

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.truncate
import kotlin.time.Instant

/** Swift `.rounded()`: halves go away from zero (Kotlin `round` goes to even). */
fun Double.roundedSwift(): Double {
    val whole = truncate(this)
    return if (abs(this - whole) == 0.5) whole + sign(this) else round(this)
}

/** Swift `Int(x.rounded())`. */
fun Double.roundToIntSwift(): Int = roundedSwift().toInt()

/** Swift `end.timeIntervalSince(start) / 60` (fractional minutes). */
fun minutesBetween(start: Instant, end: Instant): Double = (end - start).inWholeMilliseconds / 60_000.0

/** Swift `end.timeIntervalSince(start)` in seconds. */
fun secondsBetween(start: Instant, end: Instant): Double = (end - start).inWholeMilliseconds / 1_000.0

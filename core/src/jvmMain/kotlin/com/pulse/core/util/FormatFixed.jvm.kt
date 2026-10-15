package com.pulse.core.util

import java.math.BigDecimal
import java.math.RoundingMode

internal actual fun formatFixed(value: Double, fractionDigits: Int): String {
    if (value.isNaN() || value.isInfinite()) return value.toString()
    // BigDecimal(double) keeps the exact binary value, so rounding matches C printf ("%.1f").
    val text = BigDecimal(kotlin.math.abs(value)).setScale(fractionDigits, RoundingMode.HALF_EVEN).toPlainString()
    val negative = value < 0 || (value == 0.0 && 1.0 / value < 0)
    return if (negative) "-$text" else text
}

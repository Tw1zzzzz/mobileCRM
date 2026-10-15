package com.pulse.core.util

/**
 * Deterministic generator for demo data — bit-for-bit twin of iOS `SeededGenerator`
 * (SplitMix64 seeding + xorshift64), including Swift's `Int.random(in:)` / `Double.random(in:)`
 * algorithms, so both apps show the same demo numbers.
 */
class SeededGenerator(seed: ULong) {
    private var state: ULong

    init {
        var z = seed + 0x9E3779B97F4A7C15uL
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        z = z xor (z shr 31)
        state = if (z == 0uL) 0x9E3779B97F4A7C15uL else z
    }

    fun next(): ULong {
        state = state xor (state shl 13)
        state = state xor (state shr 7)
        state = state xor (state shl 17)
        return state
    }

    /** Swift `next(upperBound:)`: Lemire's multiply-shift with rejection. */
    private fun next(upperBound: ULong): ULong {
        var random = next()
        var (high, low) = multiplyFullWidth(random, upperBound)
        if (low < upperBound) {
            val threshold = (0uL - upperBound) % upperBound
            while (low < threshold) {
                random = next()
                val product = multiplyFullWidth(random, upperBound)
                high = product.first
                low = product.second
            }
        }
        return high
    }

    /** Swift `Int.random(in: range, using:)`. */
    fun int(range: IntRange): Int {
        val delta = (range.last.toLong() - range.first.toLong()).toULong()
        if (delta == ULong.MAX_VALUE) return next().toLong().toInt()
        return (range.first.toLong() + next(delta + 1uL).toLong()).toInt()
    }

    /** Swift `Double.random(in: range, using:)` for a closed range (stdlib algorithm, bit-exact). */
    fun double(range: ClosedFloatingPointRange<Double>): Double {
        val delta = range.endInclusive - range.start
        val maxSignificand = 1uL shl 53
        val random = next(maxSignificand + 1uL)
        if (random == maxSignificand) return range.endInclusive
        val unit = random.toDouble() * (Math.ulpOfOne / 2)
        return delta * unit + range.start
    }

    fun bool(): Boolean = int(0..1) == 1

    private object Math {
        const val ulpOfOne = 2.220446049250313E-16
    }

    private companion object {
        /** 64×64 → 128-bit unsigned product as (high, low). */
        fun multiplyFullWidth(a: ULong, b: ULong): Pair<ULong, ULong> {
            val mask32 = 0xFFFFFFFFuL
            val aLow = a and mask32
            val aHigh = a shr 32
            val bLow = b and mask32
            val bHigh = b shr 32
            val lowLow = aLow * bLow
            val highLow = aHigh * bLow
            val lowHigh = aLow * bHigh
            val highHigh = aHigh * bHigh
            val cross = (lowLow shr 32) + (highLow and mask32) + lowHigh
            val high = highHigh + (highLow shr 32) + (cross shr 32)
            val low = (cross shl 32) or (lowLow and mask32)
            return high to low
        }
    }
}

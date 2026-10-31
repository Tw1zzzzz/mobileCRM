package com.pulse.core.feature

import com.pulse.core.util.StatisticsMath
import kotlin.math.abs

/**
 * Correlation math of the staff «Статистика» page and the card's correlations: Pearson over per-day series, the
 * board's strength labels, the median split behind the «Что это значит» sentence, and the «key links».
 *
 * Pure functions. The TypeScript twin is `server/lib/staff/statistics.ts`; both are checked against
 * `server/test/fixtures/staff-statistics.json`. [pearson] is the app's [StatisticsMath.pearson]. Nothing here
 * rounds: the UI shows r with two decimals.
 */
object StaffStatistics {
    const val KEY_LINK_COUNT = 3

    data class PairedDays(val xs: List<Double>, val ys: List<Double>)

    /** Direction is "direct" / "inverse" once |r| >= 0.2, else null. */
    data class Strength(val label: String, val direction: String?)

    data class MedianSplit(val median: Double, val hiMean: Double, val loMean: Double)

    /** [group] is "state" or "game". */
    data class LinkMetric(val id: String, val group: String)

    /** [a] is the state metric of a cross pair; [cross] is one state and one game metric. */
    data class KeyLink(val a: String, val b: String, val r: Double, val cross: Boolean)

    /** Days present in both series, in ascending day order. */
    fun pairByDay(a: DaySeries, b: DaySeries): PairedDays {
        val days = a.keys.filter { it in b }.sorted()
        return PairedDays(days.map { a.getValue(it) }, days.map { b.getValue(it) })
    }

    /** Null for a size mismatch, fewer than 3 pairs or zero variance. */
    fun pearson(xs: List<Double>, ys: List<Double>): Double? = StatisticsMath.pearson(xs, ys)

    /** The board's labels, not the app's 0.6 / 0.3 ones; «мало данных» when r is null. */
    fun strength(r: Double?): Strength {
        if (r == null) return Strength("мало данных", null)
        val magnitude = abs(r)
        val label = when {
            magnitude >= 0.7 -> "Сильная"
            magnitude >= 0.4 -> "Заметная"
            magnitude >= 0.2 -> "Слабая"
            else -> "Связи почти нет"
        }
        val direction = when {
            magnitude < 0.2 -> null
            r > 0 -> "direct"
            else -> "inverse"
        }
        return Strength(label, direction)
    }

    fun correlate(a: DaySeries, b: DaySeries): Double? {
        val paired = pairByDay(a, b)
        return pearson(paired.xs, paired.ys)
    }

    /** Team mode: every player's paired days go into one sample. */
    fun correlatePooled(pairs: List<Pair<DaySeries, DaySeries>>): Double? {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for ((a, b) in pairs) {
            val paired = pairByDay(a, b)
            xs.addAll(paired.xs)
            ys.addAll(paired.ys)
        }
        return pearson(xs, ys)
    }

    /** median = sorted[floor(n/2)], «выше обычного» is xs >= median; null when either side is empty or n < 3. */
    fun medianSplit(xs: List<Double>, ys: List<Double>): MedianSplit? {
        val n = xs.size
        if (n != ys.size || n < 3) return null
        val median = xs.sorted()[n / 2]
        val hi = ArrayList<Double>()
        val lo = ArrayList<Double>()
        xs.forEachIndexed { i, x -> (if (x >= median) hi else lo).add(ys[i]) }
        if (hi.isEmpty() || lo.isEmpty()) return null
        return MedianSplit(median, hi.sum() / hi.size, lo.sum() / lo.size)
    }

    /**
     * Top 3 by |r| among the cross (state x game) pairs of [metrics] when any cross pair has an r, otherwise among
     * all pairs with an r. A pair without r is never a link; a game-first cross pair comes back state-first; equal
     * |r| keep the order of [metrics]. Either triangle of [matrix] may hold the r of a pair.
     */
    fun keyLinks(metrics: List<LinkMetric>, matrix: Map<String, Map<String, Double?>>): List<KeyLink> {
        val all = ArrayList<KeyLink>()
        for (i in metrics.indices) {
            for (j in i + 1 until metrics.size) {
                val first = metrics[i]
                val second = metrics[j]
                val r = matrix[first.id]?.get(second.id) ?: matrix[second.id]?.get(first.id) ?: continue
                val cross = first.group != second.group
                val swap = cross && first.group == "game"
                all.add(KeyLink(if (swap) second.id else first.id, if (swap) first.id else second.id, r, cross))
            }
        }
        val crossLinks = all.filter { it.cross }
        val pool = crossLinks.ifEmpty { all }
        return pool.sortedByDescending { abs(it.r) }.take(KEY_LINK_COUNT)
    }
}

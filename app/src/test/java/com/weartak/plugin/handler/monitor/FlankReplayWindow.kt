package com.weartak.plugin.handler.monitor

import kotlin.math.sqrt

internal const val FLANK_WINDOW_NANOS = 30_000_000_000L

internal class FlankReplayWindow {
    private data class Point(val nanos: Long, val x: Float, val y: Float, val z: Float)
    private val samples = ArrayDeque<Point>()

    fun accept(nanos: Long, x: Float, y: Float, z: Float, usable: Boolean) {
        val last = samples.lastOrNull()
        if (!usable || (last != null && nanos <= last.nanos)) {
            samples.clear()
            return
        }
        if (last != null && nanos - last.nanos > MAX_HARNESS_GAP_NANOS) samples.clear()
        samples.addLast(Point(nanos, x, y, z))
        while (samples.size > 1 && samples[1].nanos <= nanos - FLANK_WINDOW_NANOS) samples.removeFirst()
    }

    data class Candidates(
        val fullWindow: ReplayRhythm?,
        val stableHalves: ReplayRhythm?,
        val recoveryTrend: ReplayRhythm?,
    )

    fun candidate(nanos: Long, movement: ExertionLevel): Candidates? {
        if (movement != ExertionLevel.QUIET) return null
        val first = samples.firstOrNull() ?: return null
        if (samples.last().nanos != nanos || nanos - first.nanos < FLANK_WINDOW_NANOS) return null
        val period = (nanos - first.nanos).toDouble() / (samples.size - 1)
        if (period !in 5_000_000.0..100_000_000.0 ||
            samples.zipWithNext().any { (a, b) -> (b.nanos - a.nanos).toDouble() !in period * 0.8..period * 1.2 }
        ) return null

        fun variance(values: List<Double>): Double {
            val mean = values.average()
            return values.sumOf { (it - mean) * (it - mean) } / values.size
        }
        val normal = samples.map { it.z.toDouble() }
        val x = samples.map { it.x.toDouble() }
        val y = samples.map { it.y.toDouble() }
        val normalPower = variance(normal)
        val tangentialPower = variance(x) + variance(y)
        if (sqrt(normalPower) < 0.02 || normalPower < 3 * tangentialPower) return null
        fun rhythm(values: List<Double>) = replayRhythm(values, period, minSeconds = 0.5, maxSeconds = 5.0)
        val full = rhythm(normal)
        val midpoint = normal.size / 2
        val halves = listOf(normal.subList(0, midpoint), normal.subList(midpoint, normal.size))
        val stable = full != null && halves.all { half ->
            sqrt(variance(half)) >= 0.02 &&
                rhythm(half)?.hz?.let { it in (full.hz * 0.8)..(full.hz * 1.2) } == true
        }
        val recovery = if (stable) null else slowingRhythm(normal, x, y, ::variance, ::rhythm)
        return Candidates(full, full.takeIf { stable }, recovery)
    }

    private fun slowingRhythm(
        normal: List<Double>, x: List<Double>, y: List<Double>,
        variance: (List<Double>) -> Double, rhythm: (List<Double>) -> ReplayRhythm?,
    ): ReplayRhythm? {
        val segments = (0..2).map { index ->
            val from = index * normal.size / 3
            val to = (index + 1) * normal.size / 3
            val values = normal.subList(from, to)
            val power = variance(values)
            if (sqrt(power) < 0.02 || power < 3 * (variance(x.subList(from, to)) + variance(y.subList(from, to)))) {
                return null
            }
            rhythm(values) ?: return null
        }
        if (segments.zipWithNext().any { (earlier, later) -> later.hz / earlier.hz !in 0.65..0.98 }) return null
        return ReplayRhythm(segments.map { it.hz }.average(), segments.minOf { it.correlation })
    }
}

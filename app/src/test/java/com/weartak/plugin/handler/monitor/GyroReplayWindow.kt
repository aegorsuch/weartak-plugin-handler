package com.weartak.plugin.handler.monitor

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class GyroReplayWindow {
    private data class Point(val nanos: Long, val radiansPerSecond: Double)

    private val samples = ArrayDeque<Point>()

    fun accept(nanos: Long, axes: List<Double>?) {
        val last = samples.lastOrNull()
        if (last != null && nanos <= last.nanos) {
            samples.clear()
            return
        }
        val magnitude = axes?.let { sqrt(it.sumOf { axis -> axis * axis }) }
        if (magnitude == null || !magnitude.isFinite()) {
            samples.clear()
            return
        }
        if (last != null && nanos - last.nanos > MAX_HARNESS_GAP_NANOS) samples.clear()
        samples.addLast(Point(nanos, magnitude))
        while (samples.size > 1 && samples[1].nanos <= nanos - WINDOW_NANOS) samples.removeFirst()
    }

    fun features(nanos: Long): Features? {
        val first = samples.firstOrNull() ?: return null
        if (samples.last().nanos != nanos || nanos - first.nanos < WINDOW_NANOS) return null
        val period = (nanos - first.nanos).toDouble() / (samples.size - 1)
        // Lag-based rhythm only makes sense on a near-uniform grid.
        if (period !in 5_000_000.0..100_000_000.0 ||
            samples.zipWithNext().any { (a, b) -> (b.nanos - a.nanos).toDouble() !in period * 0.8..period * 1.2 }
        ) return null
        val values = samples.map { it.radiansPerSecond }
        val rms = sqrt(values.sumOf { it * it } / values.size)
        val oneSecondSamples = (1e9 / period).roundToInt()
        val squared = values.map { it * it }
        var shortPower = squared.take(oneSecondSamples).sum()
        var peakPower = shortPower
        for (index in oneSecondSamples until squared.size) {
            shortPower += squared[index] - squared[index - oneSecondSamples]
            peakPower = maxOf(peakPower, shortPower)
        }
        val rhythm = replayRhythm(values, period, minSeconds = 0.25, maxSeconds = 1.5)
        return Features(rms, rhythm?.hz, rhythm?.correlation, sqrt(peakPower / oneSecondSamples))
    }

    data class Features(
        val rmsRadS: Double,
        val repetitionHz: Double?,
        val correlation: Double?,
        val peakSecondRmsRadS: Double,
    )

    private companion object {
        const val WINDOW_NANOS = 30_000_000_000L
    }
}

internal data class ReplayRhythm(val hz: Double, val correlation: Double)

internal fun replayRhythm(
    values: List<Double>,
    periodNanos: Double,
    minSeconds: Double,
    maxSeconds: Double,
): ReplayRhythm? {
    val mean = values.average()
    val centered = values.map { it - mean }
    val minLag = ceil(minSeconds * 1e9 / periodNanos).toInt().coerceAtLeast(2)
    val maxLag = minOf(floor(maxSeconds * 1e9 / periodNanos).toInt(), values.size / 3)
    if (minLag > maxLag) return null
    val correlations = (minLag - 1..maxLag + 1).associateWith { lag ->
        var dot = 0.0
        var leftPower = 0.0
        var rightPower = 0.0
        for (index in 0 until centered.size - lag) {
            val a = centered[index]
            val b = centered[index + lag]
            dot += a * b
            leftPower += a * a
            rightPower += b * b
        }
        val denominator = sqrt(leftPower * rightPower)
        if (denominator > 0) dot / denominator else 0.0
    }
    val peaks = (minLag..maxLag).filter { lag ->
        val value = correlations.getValue(lag)
        value >= 0.6 && value > correlations.getValue(lag - 1) &&
            value >= correlations.getValue(lag + 1)
    }
    val strongest = peaks.maxOfOrNull(correlations::getValue)
    val selected = strongest?.let { best -> peaks.first { correlations.getValue(it) >= best * 0.9 } }
    return selected?.let { ReplayRhythm(1e9 / (it * periodNanos), correlations.getValue(it)) }
}

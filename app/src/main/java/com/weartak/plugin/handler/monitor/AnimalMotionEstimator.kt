package com.weartak.plugin.handler.monitor

import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

enum class ExertionLevel {
    QUIET,
    ACTIVE,
    HIGH,
}

data class AnimalMotionEstimate(
    val breathsPerMinute: Int?,
    val respirationConfidence: Double,
    val movementRmsMetersPerSecondSquared: Double,
    val exertionLevel: ExertionLevel,
    val highMovementAlert: Boolean,
)

/**
 * Motion-derived estimate. It is intentionally conservative: irregular sampling, low confidence,
 * and sustained high movement do not produce a breathing-rate value.
 */
class AnimalMotionEstimator(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val estimateIntervalMillis: Long = DEFAULT_ESTIMATE_INTERVAL_MILLIS,
) {
    private data class Sample(
        val timestampNanos: Long,
        val x: Double,
        val y: Double,
        val z: Double,
    )

    private val samples = ArrayDeque<Sample>()
    private var lastEstimateNanos = Long.MIN_VALUE
    private var consecutiveHighWindows = 0

    fun addSample(timestampNanos: Long, x: Float, y: Float, z: Float): AnimalMotionEstimate? {
        if (timestampNanos < 0L || !x.isFinite() || !y.isFinite() || !z.isFinite()) return null
        val previous = samples.peekLast()
        if (previous != null && timestampNanos <= previous.timestampNanos) return null

        samples.addLast(Sample(timestampNanos, x.toDouble(), y.toDouble(), z.toDouble()))
        val bufferCutoff = timestampNanos - MAX_BUFFER_NANOS
        while (samples.peekFirst()?.timestampNanos?.let { it < bufferCutoff } == true) {
            samples.removeFirst()
        }

        val intervalNanos = estimateIntervalMillis * NANOS_PER_MILLI
        if (lastEstimateNanos != Long.MIN_VALUE && timestampNanos - lastEstimateNanos < intervalNanos) {
            return null
        }

        val windowStart = timestampNanos - windowMillis * NANOS_PER_MILLI
        val window = samples.filter { it.timestampNanos >= windowStart }
        if (window.size < MIN_WINDOW_SAMPLES ||
            window.last().timestampNanos - window.first().timestampNanos < windowMillis * NANOS_PER_MILLI
        ) {
            return null
        }
        lastEstimateNanos = timestampNanos

        val durationSeconds =
            (window.last().timestampNanos - window.first().timestampNanos).toDouble() / NANOS_PER_SECOND
        val sampleRateHz = (window.size - 1) / durationSeconds
        val largestGapNanos = window.zipWithNext { left, right -> right.timestampNanos - left.timestampNanos }.max()
        if (sampleRateHz !in MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ ||
            largestGapNanos > MAX_SAMPLE_GAP_NANOS
        ) {
            return null
        }

        val axes = listOf(window.map { it.x }, window.map { it.y }, window.map { it.z })
        val demeanedAxes = axes.map { axis ->
            val mean = axis.average()
            axis.map { it - mean }
        }
        val movementRms = sqrt(
            demeanedAxes.sumOf { axis -> axis.sumOf { it * it } } / window.size,
        )
        val exertion = when {
            movementRms >= HIGH_MOVEMENT_THRESHOLD -> ExertionLevel.HIGH
            movementRms >= ACTIVE_MOVEMENT_THRESHOLD -> ExertionLevel.ACTIVE
            else -> ExertionLevel.QUIET
        }
        consecutiveHighWindows = if (exertion == ExertionLevel.HIGH) consecutiveHighWindows + 1 else 0

        val respiration = if (exertion == ExertionLevel.HIGH) {
            null
        } else {
            estimateRespiration(demeanedAxes, sampleRateHz)
        }

        return AnimalMotionEstimate(
            breathsPerMinute = respiration?.first,
            respirationConfidence = respiration?.second ?: 0.0,
            movementRmsMetersPerSecondSquared = movementRms,
            exertionLevel = exertion,
            highMovementAlert = consecutiveHighWindows >= HIGH_MOVEMENT_ALERT_WINDOWS,
        )
    }

    fun reset() {
        samples.clear()
        lastEstimateNanos = Long.MIN_VALUE
        consecutiveHighWindows = 0
    }

    private fun estimateRespiration(axes: List<List<Double>>, sampleRateHz: Double): Pair<Int, Double>? {
        val minLag = ceil(sampleRateHz * MIN_BREATH_PERIOD_SECONDS).toInt().coerceAtLeast(2)
        val maxLag = minOf(
            floor(sampleRateHz * MAX_BREATH_PERIOD_SECONDS).toInt(),
            axes.first().size / 3,
        )
        if (maxLag <= minLag) return null

        val candidates = mutableListOf<Pair<Int, Double>>()
        for (axis in axes) {
            for (lag in minLag..maxLag) {
                var crossProduct = 0.0
                var leftPower = 0.0
                var rightPower = 0.0
                for (index in 0 until axis.size - lag) {
                    val left = axis[index]
                    val right = axis[index + lag]
                    crossProduct += left * right
                    leftPower += left * left
                    rightPower += right * right
                }
                val denominator = sqrt(leftPower * rightPower)
                val correlation = if (denominator > 0.0) crossProduct / denominator else 0.0
                candidates += lag to correlation
            }
        }

        val bestCorrelation = candidates.maxOfOrNull { it.second } ?: return null
        if (bestCorrelation < MIN_RESPIRATION_CONFIDENCE) return null
        val selectedLag = candidates
            .filter { it.second >= bestCorrelation - AUTOCORRELATION_TIE_TOLERANCE }
            .minOfOrNull { it.first } ?: return null
        val breathsPerMinute = (60.0 * sampleRateHz / selectedLag).toInt()
        if (breathsPerMinute !in MIN_BREATHS_PER_MINUTE..MAX_BREATHS_PER_MINUTE) return null
        return breathsPerMinute to bestCorrelation.coerceIn(0.0, 1.0)
    }

    private companion object {
        const val DEFAULT_WINDOW_MILLIS = 30_000L
        const val DEFAULT_ESTIMATE_INTERVAL_MILLIS = 5_000L
        const val MIN_WINDOW_SAMPLES = 90
        const val MIN_SAMPLE_RATE_HZ = 10.0
        const val MAX_SAMPLE_RATE_HZ = 200.0
        const val MAX_SAMPLE_GAP_NANOS = 250_000_000L
        const val MIN_BREATH_PERIOD_SECONDS = 0.5
        const val MAX_BREATH_PERIOD_SECONDS = 10.0
        const val MIN_BREATHS_PER_MINUTE = 6
        const val MAX_BREATHS_PER_MINUTE = 120
        const val MIN_RESPIRATION_CONFIDENCE = 0.35
        const val AUTOCORRELATION_TIE_TOLERANCE = 0.02
        const val ACTIVE_MOVEMENT_THRESHOLD = 0.35
        const val HIGH_MOVEMENT_THRESHOLD = 1.25
        const val HIGH_MOVEMENT_ALERT_WINDOWS = 3
        const val MAX_BUFFER_NANOS = 40_000_000_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

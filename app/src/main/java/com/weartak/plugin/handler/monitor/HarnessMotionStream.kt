package com.weartak.plugin.handler.monitor

import kotlin.math.abs

internal const val MAX_HARNESS_GAP_NANOS = 250_000_000L

internal class HarnessMotionStream(private val sensorRange: Float) {
    private val estimator = AnimalMotionEstimator()
    private var previousSampleNanos: Long? = null
    var gapCount = 0L
        private set
    var maxGapNanos = 0L
        private set
    var sampleCount = 0L
        private set
    var rejectedSamples = 0L
        private set
    var lastGoodSampleNanos: Long? = null
        private set
    var estimate: AnimalMotionEstimate? = null
        private set
    var measuredAtNanos = 0L
        private set
    var recovering = false
        private set

    fun onSample(timestamp: Long, receivedAt: Long, x: Float, y: Float, z: Float, reliable: Boolean) {
        sampleCount++
        val previous = previousSampleNanos
        val ordered = timestamp >= 0L && timestamp <= receivedAt && (previous == null || timestamp > previous)
        if (ordered) {
            if (previous != null) {
                val gap = timestamp - previous
                maxGapNanos = maxOf(maxGapNanos, gap)
                if (gap > MAX_HARNESS_GAP_NANOS) gapCount++
            }
            previousSampleNanos = timestamp
        }
        val usable = ordered && receivedAt - timestamp <= MAX_HARNESS_GAP_NANOS && reliable &&
            x.isFinite() && y.isFinite() && z.isFinite() &&
            (x != 0f || y != 0f || z != 0f) &&
            abs(x) < sensorRange && abs(y) < sensorRange && abs(z) < sensorRange
        if (!usable) {
            rejectedSamples++
            invalidate()
            return
        }
        val lastGood = lastGoodSampleNanos
        if (lastGood != null && timestamp - lastGood > MAX_HARNESS_GAP_NANOS) invalidate()
        lastGoodSampleNanos = timestamp
        estimator.addSample(timestamp, x, y, z)?.let {
            estimate = it
            measuredAtNanos = timestamp
            recovering = false
        }
        if (estimator.windowRejected) invalidate()
    }

    fun invalidate() {
        estimator.reset()
        lastGoodSampleNanos = null
        estimate = null
        recovering = true
    }
}

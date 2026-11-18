package com.weartak.plugin.handler.monitor

import android.os.SystemClock

internal data class MonitoringSnapshot(
    val monitoring: Boolean = false,
    val sensorUnavailable: Boolean = false,
    val estimate: AnimalMotionEstimate? = null,
    val measuredAtNanos: Long = 0L,
    val lastGoodSampleNanos: Long? = null,
    val recovering: Boolean = false,
) {
    fun freshEstimate(nowNanos: Long): AnimalMotionEstimate? {
        val lastGood = lastGoodSampleNanos ?: return null
        return estimate?.takeIf {
            monitoring && !recovering && nowNanos - lastGood in 0..MAX_HARNESS_GAP_NANOS &&
                nowNanos - measuredAtNanos in 0..10_000_000_000L
        }
    }
}

internal object HandlerRuntime {
    const val EXERTION_FRACTION = "handler_exertion_fraction"

    @Volatile
    private var state = MonitoringSnapshot()

    fun snapshot() = state

    fun start() {
        state = MonitoringSnapshot(monitoring = true)
    }

    fun stop(sensorUnavailable: Boolean = false) {
        state = MonitoringSnapshot(sensorUnavailable = sensorUnavailable)
    }

    fun update(stream: HarnessMotionStream) {
        if (state.monitoring) {
            state = MonitoringSnapshot(
                monitoring = true,
                estimate = stream.estimate,
                measuredAtNanos = stream.measuredAtNanos,
                lastGoodSampleNanos = stream.lastGoodSampleNanos,
                recovering = stream.recovering,
            )
        }
    }

    fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

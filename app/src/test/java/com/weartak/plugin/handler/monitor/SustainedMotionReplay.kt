package com.weartak.plugin.handler.monitor

import kotlin.math.expm1
import kotlin.math.ln1p

internal class SustainedMotionReplay {
    private var previousAt: Long? = null
    private var filtered = 0.0

    fun observe(at: Long, rms: Double): Double {
        val score = boundedMotionScore(rms)
        val previous = previousAt
        filtered = if (previous != null && at - previous in 1..MAX_ESTIMATE_INTERVAL_NANOS) {
            val weight = -expm1(-(at - previous) / TIME_CONSTANT_NANOS)
            filtered + weight * (score - filtered)
        } else {
            score
        }
        previousAt = at
        return filtered
    }

    fun reset() {
        previousAt = null
    }

    private companion object {
        const val TIME_CONSTANT_NANOS = 30_000_000_000.0
        const val MAX_ESTIMATE_INTERVAL_NANOS = 10_000_000_000L
    }
}

internal fun boundedMotionScore(rms: Double): Double = (ln1p(rms) / ln1p(12.0)).coerceAtMost(1.0)

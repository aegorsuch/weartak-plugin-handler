package com.weartak.plugin.handler.monitor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimalMotionEstimatorTest {
    @Test
    fun periodicLateralMotion_estimatesRespirationRate() {
        val estimate = feedSignal(
            durationSeconds = 40,
            amplitude = 0.2,
            frequencyHz = 0.4,
        )

        assertNotNull(estimate)
        assertTrue(abs(estimate!!.breathsPerMinute!! - 24) <= 2)
        assertTrue(estimate.respirationConfidence >= 0.35)
        assertEquals(ExertionLevel.QUIET, estimate.exertionLevel)
    }

    @Test
    fun stationarySignal_doesNotInventBreathingRate() {
        val estimator = AnimalMotionEstimator()
        var estimate: AnimalMotionEstimate? = null
        for (index in 0..800) {
            estimate = estimator.addSample(index * SAMPLE_PERIOD_NANOS, 0f, 0f, 9.81f) ?: estimate
        }

        assertNotNull(estimate)
        assertNull(estimate!!.breathsPerMinute)
        assertEquals(ExertionLevel.QUIET, estimate.exertionLevel)
        assertFalse(estimate.highMovementAlert)
        assertEquals(0.0, estimate.exertionFraction, 0.0001)
    }

    @Test
    fun jitteredSamples_produceScoreOnlyAfterFullWindow() {
        val estimator = AnimalMotionEstimator()
        var timestamp = 0L
        repeat(810) { index ->
            val seconds = timestamp / 1e9
            val x = sin(2 * PI * 2 * seconds).toFloat()
            val estimate = estimator.addSample(timestamp, x, 0f, 9.81f)
            if (timestamp < 30_000_000_000L) {
                assertNull(estimate)
            } else if (estimate != null) {
                assertEquals(0.28, estimate.exertionFraction, 0.02)
                return
            }
            timestamp += 40_070_000L + (index % 7 - 3) * 1_000_000L
        }
        throw AssertionError("Healthy samples never produced an exertion reading")
    }

    @Test
    fun dropout_doesNotPublishAWindowWithAMissingSegment() {
        val estimator = AnimalMotionEstimator()
        for (index in 0..800) {
            if (index in 350..375) continue
            assertNull(estimator.addSample(index * SAMPLE_PERIOD_NANOS, 0f, 0f, 9.81f))
        }
    }

    @Test
    fun sustainedHighMovement_setsLocalAlertAndSuppressesRespirationEstimate() {
        val estimator = AnimalMotionEstimator()
        var estimate: AnimalMotionEstimate? = null
        for (index in 0..1_125) {
            val seconds = index.toDouble() / SAMPLE_RATE_HZ
            val x = (2.4 * sin(2.0 * PI * 2.0 * seconds)).toFloat()
            estimate = estimator.addSample(index * SAMPLE_PERIOD_NANOS, x, 0f, 9.81f) ?: estimate
        }

        assertNotNull(estimate)
        assertEquals(ExertionLevel.HIGH, estimate!!.exertionLevel)
        assertTrue(estimate.highMovementAlert)
        assertNull(estimate.breathsPerMinute)
    }

    private fun feedSignal(durationSeconds: Int, amplitude: Double, frequencyHz: Double): AnimalMotionEstimate? {
        val estimator = AnimalMotionEstimator()
        var estimate: AnimalMotionEstimate? = null
        val sampleCount = durationSeconds * SAMPLE_RATE_HZ
        for (index in 0..sampleCount) {
            val seconds = index.toDouble() / SAMPLE_RATE_HZ
            val x = (amplitude * sin(2.0 * PI * frequencyHz * seconds)).toFloat()
            estimate = estimator.addSample(index * SAMPLE_PERIOD_NANOS, x, 0f, 9.81f) ?: estimate
        }
        return estimate
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 25
        const val SAMPLE_PERIOD_NANOS = 40_000_000L
    }
}

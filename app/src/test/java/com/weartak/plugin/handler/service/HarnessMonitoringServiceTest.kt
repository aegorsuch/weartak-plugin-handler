package com.weartak.plugin.handler.service

import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.SystemClock
import com.weartak.plugin.handler.monitor.HandlerRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HarnessMonitoringServiceTest {
    @After
    fun clearRuntime() = HandlerRuntime.stop()

    @Test
    fun explicitStartProducesScoreWithoutActivityAndDestroyClearsIt() {
        val controller = Robolectric.buildService(HarnessMonitoringService::class.java).create()
        val service = controller.get()
        val sensors = shadowOf(service.getSystemService(SensorManager::class.java))
        val accelerometer = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
        shadowOf(accelerometer).setMaximumRange(40f)
        sensors.addSensor(accelerometer)
        try {
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
            assertFalse(HandlerRuntime.snapshot().monitoring)

            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(startIntent(), 0, 2))
            assertTrue(HandlerRuntime.snapshot().monitoring)
            assertTrue(sensors.hasListener(service))

            repeat(751) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(40))
                val event = ShadowSensorManager.createSensorEvent(3, Sensor.TYPE_ACCELEROMETER)
                event.timestamp = SystemClock.elapsedRealtimeNanos()
                event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
                event.values[2] = 9.81f
                sensors.sendSensorEventToListeners(event)
            }
            assertNotNull(HandlerRuntime.snapshot().freshEstimate(SystemClock.elapsedRealtimeNanos()))
            service.onAccuracyChanged(accelerometer, SensorManager.SENSOR_STATUS_UNRELIABLE)
            assertTrue(HandlerRuntime.snapshot().recovering)
            assertFalse(HandlerRuntime.snapshot().estimate != null)
        } finally {
            controller.destroy()
        }
        assertFalse(HandlerRuntime.snapshot().monitoring)
        assertFalse(sensors.hasListener(service))
    }

    @Test
    fun unavailableAccelerometerDoesNotEnableMonitoring() {
        val controller = Robolectric.buildService(HarnessMonitoringService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(startIntent(), 0, 1)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertFalse(HandlerRuntime.snapshot().monitoring)
            assertTrue(HandlerRuntime.snapshot().sensorUnavailable)
        } finally {
            controller.destroy()
        }
    }

    private fun startIntent() = Intent(HarnessMonitoringService.ACTION_START)
}

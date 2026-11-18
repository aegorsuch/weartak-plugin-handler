package com.weartak.plugin.handler.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import com.weartak.plugin.handler.MainActivity
import com.weartak.plugin.handler.R
import com.weartak.plugin.handler.monitor.HandlerRuntime
import com.weartak.plugin.handler.monitor.HarnessMotionStream

class HarnessMonitoringService : Service(), SensorEventListener {
    private lateinit var sensors: SensorManager
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var stream: HarnessMotionStream
    private lateinit var battery: BatteryManager
    private var startedAt = 0L
    private lateinit var startBattery: BatteryReading
    private var firstReceivedAt: Long? = null
    private var lastReceivedAt: Long? = null
    private var maxCallbackGap = 0L
    private var sampling = false

    override fun onCreate() {
        super.onCreate()
        sensors = getSystemService(SensorManager::class.java)
        battery = getSystemService(BatteryManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSampling()
            stopSelf()
        } else if (intent?.action == ACTION_START && !sampling) {
            startForeground(NOTIFICATION_ID, buildNotification())
            val sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (sensor == null || !sensors.registerListener(this, sensor, SAMPLE_PERIOD_MICROS)) {
                Log.w(TAG, "Cannot register harness accelerometer")
                HandlerRuntime.stop(sensorUnavailable = true)
                stopSelf()
            } else {
                stream = HarnessMotionStream(sensor.maximumRange)
                startedAt = HandlerRuntime.nowNanos()
                startBattery = readBattery()
                firstReceivedAt = null
                lastReceivedAt = null
                maxCallbackGap = 0L
                // Non-wakeup accelerometers need the CPU awake while this explicit session runs.
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WearTAKHandler:harness").apply { acquire() }
                sampling = true
                HandlerRuntime.start()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onSensorChanged(event: SensorEvent) {
        if (!sampling) return
        val now = HandlerRuntime.nowNanos()
        if (firstReceivedAt == null) firstReceivedAt = now
        lastReceivedAt?.let { maxCallbackGap = maxOf(maxCallbackGap, now - it) }
        lastReceivedAt = now
        stream.onSample(
            event.timestamp, now, event.values[0], event.values[1], event.values[2],
            reliable = event.accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE,
        )
        HandlerRuntime.update(stream)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sampling && accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            stream.invalidate()
            HandlerRuntime.update(stream)
        }
    }

    override fun onDestroy() {
        stopSampling()
        super.onDestroy()
    }

    private fun stopSampling() {
        if (!sampling) return
        sampling = false
        HandlerRuntime.stop()
        sensors.unregisterListener(this)
        logSessionSummary()
        wakeLock?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private data class BatteryReading(val percent: Int?, val chargeMicroAh: Int?, val charging: Boolean)

    private fun readBattery() = BatteryReading(
        battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 },
        battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it >= 0 },
        battery.isCharging,
    )

    private fun logSessionSummary() {
        val end = HandlerRuntime.nowNanos()
        val endBattery = readBattery()
        Log.i(
            TAG,
            "session durationMs=${toMs(end - startedAt)} samples=${stream.sampleCount}" +
                " rejected=${stream.rejectedSamples} gapsOver250ms=${stream.gapCount}" +
                " maxGapMs=${toMs(stream.maxGapNanos)}" +
                " maxCallbackGapMs=${toMs(maxCallbackGap)}" +
                " firstCallbackMs=${firstReceivedAt?.let { toMs(it - startedAt) }}" +
                " lastCallbackToStopMs=${lastReceivedAt?.let { toMs(end - it) }}" +
                " batteryStart=$startBattery batteryEnd=$endBattery",
        )
    }

    private fun toMs(nanos: Long) = nanos / 1_000_000L

    private fun buildNotification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.harness_monitoring), NotificationManager.IMPORTANCE_LOW),
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HarnessMonitoringService::class.java).setAction(ACTION_STOP), flags,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.harness_monitoring))
            .setContentText(getString(R.string.harness_screen_off))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.harness_stop), stop).build())
            .build()
    }

    companion object {
        const val ACTION_START = "com.weartak.plugin.handler.START_HARNESS"
        private const val ACTION_STOP = "com.weartak.plugin.handler.STOP_HARNESS"
        private const val TAG = "HarnessMonitoring"
        private const val CHANNEL_ID = "harness_monitoring"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_PERIOD_MICROS = 40_000
    }
}

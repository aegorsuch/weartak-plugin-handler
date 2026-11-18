package com.weartak.plugin.handler

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.weartak.plugin.handler.monitor.AnimalMotionEstimate
import com.weartak.plugin.handler.monitor.AnimalMotionEstimator
import com.weartak.plugin.handler.monitor.ExertionLevel
import com.weartak.plugin.handler.monitor.HandlerRuntime
import java.util.Locale

class MainActivity : Activity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private val estimator = AnimalMotionEstimator()
    private var monitoring = false
    private var lastHighMovementAlert = false
    private lateinit var statusText: TextView
    private lateinit var estimateText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        setContentView(createContent())
        if (accelerometer == null) {
            statusText.text = "Accelerometer unavailable"
            startButton.isEnabled = false
        }
    }

    override fun onResume() {
        super.onResume()
        if (monitoring) registerSensor()
    }

    override fun onPause() {
        stopMonitoring()
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val estimate = estimator.addSample(
            timestampNanos = event.timestamp,
            x = event.values[0],
            y = event.values[1],
            z = event.values[2],
        ) ?: return
        HandlerRuntime.setEstimate(estimate)
        render(estimate)
        if (estimate.highMovementAlert && !lastHighMovementAlert) vibrate()
        lastHighMovementAlert = estimate.highMovementAlert
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun createContent(): ScrollView {
        statusText = TextView(this).apply {
            text = "Secure the watch against the animal's side. Keep placement steady."
            textSize = 14f
            gravity = Gravity.CENTER
        }
        estimateText = TextView(this).apply {
            text = "Breathing: waiting for 30 seconds of data\nMovement: --"
            textSize = 18f
            gravity = Gravity.CENTER
        }
        startButton = Button(this).apply {
            text = "Start monitoring"
            setOnClickListener { startMonitoring() }
        }
        stopButton = Button(this).apply {
            text = "Stop"
            isEnabled = false
            setOnClickListener { stopMonitoring() }
        }
        return ScrollView(this).apply {
            isFillViewport = true
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(24, 28, 24, 28)
                    addView(statusText)
                    addView(estimateText)
                    addView(startButton)
                    addView(stopButton)
                },
            )
        }
    }

    private fun startMonitoring() {
        if (monitoring || accelerometer == null) return
        estimator.reset()
        HandlerRuntime.setEstimate(null)
        lastHighMovementAlert = false
        monitoring = true
        HandlerRuntime.setMonitoring(true)
        statusText.text = "Monitoring while this screen is open"
        startButton.isEnabled = false
        stopButton.isEnabled = true
        registerSensor()
    }

    private fun registerSensor() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun stopMonitoring() {
        if (!monitoring) return
        sensorManager.unregisterListener(this)
        monitoring = false
        HandlerRuntime.setMonitoring(false)
        statusText.text = "Monitoring stopped"
        startButton.isEnabled = accelerometer != null
        stopButton.isEnabled = false
    }

    private fun render(estimate: AnimalMotionEstimate) {
        val breathing = estimate.breathsPerMinute?.let {
            "Breathing: $it /min (${(estimate.respirationConfidence * 100).toInt()}% signal confidence)"
        } ?: "Breathing: uncertain during this motion window"
        val movement = "Movement: ${estimate.exertionLevel.name.lowercase(Locale.US)} " +
            "(${String.format(Locale.US, "%.2f", estimate.movementRmsMetersPerSecondSquared)} m/s²)"
        val alert = if (estimate.highMovementAlert) "\nSustained high movement — check the animal." else ""
        estimateText.text = "$breathing\n$movement$alert"
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createOneShot(250L, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

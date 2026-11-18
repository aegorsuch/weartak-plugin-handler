package com.weartak.plugin.handler

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.weartak.plugin.handler.monitor.HandlerRuntime
import com.weartak.plugin.handler.service.HarnessMonitoringService
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var score: TextView
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private var startFailed = false
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        score = TextView(this).apply {
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            gravity = Gravity.CENTER
        }
        toggle = Button(this).apply {
            minHeight = dp(48)
            setOnClickListener {
                val intent = Intent(this@MainActivity, HarnessMonitoringService::class.java)
                if (HandlerRuntime.snapshot().monitoring) {
                    stopService(intent)
                } else {
                    startFailed = false
                    try {
                        ContextCompat.startForegroundService(
                            this@MainActivity, intent.setAction(HarnessMonitoringService.ACTION_START),
                        )
                    } catch (error: IllegalStateException) {
                        Log.w("WearTAKHandler", "Cannot start harness monitoring", error)
                        startFailed = true
                    } catch (error: SecurityException) {
                        Log.w("WearTAKHandler", "Harness service permission denied", error)
                        startFailed = true
                    }
                }
                render()
            }
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(16), dp(20), dp(16))
                addView(TextView(this@MainActivity).apply {
                    setText(R.string.harness_exertion)
                    gravity = Gravity.CENTER
                })
                addView(score)
                addView(status)
                addView(toggle)
            })
        })
    }

    override fun onStart() {
        super.onStart()
        refresh.run()
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        super.onStop()
    }

    private fun render() {
        val state = HandlerRuntime.snapshot()
        val estimate = state.freshEstimate(HandlerRuntime.nowNanos())
        score.text = estimate?.let {
            getString(R.string.harness_score, (it.exertionFraction * 100).roundToInt())
        } ?: getString(R.string.harness_no_score)
        status.setText(
            when {
                estimate?.highMovementAlert == true -> R.string.harness_high_movement
                estimate != null -> R.string.harness_active
                state.recovering -> R.string.harness_recovering
                state.monitoring && state.estimate != null -> R.string.harness_signal_lost
                state.monitoring -> R.string.harness_collecting
                startFailed -> R.string.harness_start_failed
                state.sensorUnavailable -> R.string.harness_sensor_unavailable
                else -> R.string.harness_off
            },
        )
        toggle.setText(if (state.monitoring) R.string.harness_stop else R.string.harness_start)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

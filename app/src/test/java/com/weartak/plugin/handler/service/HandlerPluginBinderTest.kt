package com.weartak.plugin.handler.service

import android.os.SystemClock
import com.weartak.plugin.handler.monitor.HandlerRuntime
import com.weartak.plugin.handler.monitor.HarnessMotionStream
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine
import com.weartak.plugin.sdk.model.PluginLifecycleState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HandlerPluginBinderTest {
    private val lifecycle = PluginLifecycleMachine()
    private val binder = HandlerPluginBinder(lifecycle)

    @After
    fun clearRuntime() = HandlerRuntime.stop()

    @Test
    fun publishesOnlyFreshEnabledScore_thenDropsItWhenStopped() {
        val key = "handler_exertion_fraction"
        lifecycle.dispatch(PluginLifecycleEvent.BIND_ATTACHED)
        binder.initializePlugin(null, null)
        binder.startPlugin()
        assertEquals(PluginLifecycleState.RUNNING, binder.getStatus().state)
        assertFalse(binder.getStatus().extras.containsKey(key))
        HandlerRuntime.start()
        assertFalse(binder.getStatus().extras.containsKey(key))
        val stream = HarnessMotionStream(40f)
        repeat(751) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(40))
            val now = SystemClock.elapsedRealtimeNanos()
            stream.onSample(now, now, 0f, 0f, 9.81f, reliable = true)
        }
        HandlerRuntime.update(stream)
        assertEquals(0.0, binder.getStatus().extras.getDouble(key, -1.0), 0.0001)
        ShadowSystemClock.advanceBy(Duration.ofMillis(250))
        assertEquals(0.0, binder.getStatus().extras.getDouble(key, -1.0), 0.0001)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        assertFalse(binder.getStatus().extras.containsKey(key))
        HandlerRuntime.stop()
        HandlerRuntime.update(stream)
        assertFalse(binder.getStatus().extras.containsKey(key))
    }
}

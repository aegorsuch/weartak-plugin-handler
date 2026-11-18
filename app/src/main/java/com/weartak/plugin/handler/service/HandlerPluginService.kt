package com.weartak.plugin.handler.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine

class HandlerPluginService : Service() {
    private val lifecycle = PluginLifecycleMachine()
    private val binder by lazy { HandlerPluginBinder(lifecycle) }

    override fun onBind(intent: Intent?): IBinder {
        lifecycle.dispatch(PluginLifecycleEvent.BIND_ATTACHED)
        return binder
    }

    override fun onDestroy() {
        lifecycle.dispatch(PluginLifecycleEvent.RESET)
        super.onDestroy()
    }
}

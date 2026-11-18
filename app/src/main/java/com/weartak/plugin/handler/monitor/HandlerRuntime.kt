package com.weartak.plugin.handler.monitor

object HandlerRuntime {
    @Volatile
    var monitoring: Boolean = false
        private set

    @Volatile
    var latestEstimate: AnimalMotionEstimate? = null
        private set

    fun setMonitoring(value: Boolean) {
        monitoring = value
    }

    fun setEstimate(value: AnimalMotionEstimate?) {
        latestEstimate = value
    }
}

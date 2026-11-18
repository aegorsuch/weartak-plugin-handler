package com.weartak.plugin.handler.service

import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import com.weartak.plugin.handler.BuildConfig
import com.weartak.plugin.handler.monitor.HandlerRuntime
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine
import com.weartak.plugin.sdk.model.PluginCapabilitiesParcel
import com.weartak.plugin.sdk.model.PluginInfoParcel
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.model.PluginLifecycleState
import com.weartak.plugin.sdk.model.PluginMainUiSchemaParcel
import com.weartak.plugin.sdk.model.PluginStatusParcel
import com.weartak.plugin.sdk.model.SettingsApplyResultParcel
import com.weartak.plugin.sdk.model.SettingsSchemaParcel
import com.weartak.plugin.sdk.model.SettingsValidationErrorParcel
import com.weartak.plugin.sdk.validation.ContractValidationResult
import com.weartak.plugin.sdk.validation.PluginMetadataValidator
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2
import com.weartak.plugin.sdk.v3.aidl.IWearTakPluginHostControlV3

class HandlerPluginBinder(
    private val lifecycle: PluginLifecycleMachine,
) : IWearTakPlugin.Stub() {
    private var hostCallback: IWearTakHostCallback? = null

    override fun getPluginInfo() = PluginInfoParcel(
        pluginId = BuildConfig.APPLICATION_ID,
        displayName = DISPLAY_NAME,
        versionName = BuildConfig.VERSION_NAME,
        packageName = BuildConfig.APPLICATION_ID,
        description = "Standalone animal breathing and movement estimator",
    )

    override fun getCapabilities() = PluginCapabilitiesParcel(externalMainActivity = true)

    override fun getApiVersion(): Int = WearTakPluginContract.CONTRACT_API_VERSION

    override fun getStatus(): PluginStatusParcel {
        val state = lifecycle.snapshot()
        val monitoring = HandlerRuntime.snapshot()
        val message = when {
            monitoring.monitoring -> "Harness monitoring active"
            state == PluginLifecycleState.SHUTDOWN -> "Plugin shut down"
            else -> "Open WearTAK Handler on the watch to start monitoring"
        }
        val extras = Bundle().apply {
            putBoolean("monitoring", monitoring.monitoring)
            monitoring.freshEstimate(HandlerRuntime.nowNanos())?.let { estimate ->
                putDouble(HandlerRuntime.EXERTION_FRACTION, estimate.exertionFraction)
                estimate.breathsPerMinute?.let { putInt("breaths_per_minute", it) }
                putDouble("movement_rms_mps2", estimate.movementRmsMetersPerSecondSquared)
                putString("exertion_level", estimate.exertionLevel.name)
                putBoolean("high_movement_alert", estimate.highMovementAlert)
            }
        }
        return PluginStatusParcel(state = state, message = message, extras = extras)
    }

    override fun initializePlugin(init: PluginInitParcel?, control: IWearTakPluginControl?) {
        initialize(init)
    }

    override fun initializePluginV2(
        init: PluginInitParcel?,
        control: IWearTakPluginControl?,
        controlV2: IWearTakPluginHostControlV2?,
    ) {
        initialize(init)
    }

    override fun initializePluginV3(
        init: PluginInitParcel?,
        control: IWearTakPluginControl?,
        controlV2: IWearTakPluginHostControlV2?,
        controlV3: IWearTakPluginHostControlV3?,
    ) {
        initialize(init)
    }

    override fun startPlugin() {
        lifecycle.dispatch(PluginLifecycleEvent.HOST_STARTED)
        notifyStatus()
    }

    override fun stopPlugin() {
        lifecycle.dispatch(PluginLifecycleEvent.HOST_STOPPED)
        notifyStatus()
    }

    override fun shutdownPlugin() {
        lifecycle.dispatch(PluginLifecycleEvent.HOST_SHUTDOWN)
        hostCallback = null
    }

    override fun registerHostCallback(callback: IWearTakHostCallback?) {
        hostCallback = callback
        notifyStatus()
    }

    override fun unregisterHostCallback(callback: IWearTakHostCallback?) {
        if (hostCallback === callback) hostCallback = null
    }

    override fun getSettingsSchema() = SettingsSchemaParcel(title = DISPLAY_NAME)

    override fun getSettingsValues() = Bundle()

    override fun onHostSettingsChanged(hostSettings: com.weartak.plugin.sdk.model.HostSettingsParcel?) = Unit

    override fun applySettingsWithResult(values: Bundle?, applyMode: Int): SettingsApplyResultParcel {
        val accepted = values == null || values.isEmpty
        return SettingsApplyResultParcel(
            success = accepted,
            applyMode = applyMode,
            values = Bundle(),
            errors = if (accepted) {
                emptyList()
            } else {
                listOf(
                    SettingsValidationErrorParcel(
                        code = "UNSUPPORTED_SETTINGS",
                        message = "WearTAK Handler does not expose host-managed settings",
                    ),
                )
            },
        )
    }

    private fun initialize(init: PluginInitParcel?) {
        if (init != null) {
            when (
                val result = PluginMetadataValidator.validateInitAgainstPlugin(
                    init,
                    declaredPluginId = BuildConfig.APPLICATION_ID,
                )
            ) {
                is ContractValidationResult.Err -> Log.w(TAG, "Plugin init validation failed: ${result.issues}")
                is ContractValidationResult.Ok -> Unit
            }
        }
        lifecycle.dispatch(PluginLifecycleEvent.INITIALIZED)
        notifyStatus()
    }

    override fun applySettings(values: Bundle?) {
        if (values?.isEmpty == false) {
            Log.w(TAG, "Ignoring unsupported plugin settings")
        }
    }

    override fun getMainUiSchema() = PluginMainUiSchemaParcel()

    override fun getMainUiState() = getStatus().extras

    override fun invokeMainUiAction(actionId: String?, args: Bundle?) {
        Log.w(TAG, "Unsupported main UI action: $actionId")
    }

    private fun notifyStatus() {
        try {
            hostCallback?.onPluginStatusChanged(getStatus())
        } catch (error: RemoteException) {
            Log.w(TAG, "Unable to notify WearTAK host of plugin status", error)
            hostCallback = null
        }
    }

    private companion object {
        const val DISPLAY_NAME = "WearTAK Handler"
        const val TAG = "WearTAKHandler"
    }
}

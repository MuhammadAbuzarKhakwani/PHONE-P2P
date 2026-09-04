package com.jingcjie.wifi_direct_cable.gateway

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.jingcjie.wifi_direct_cable.diagnostics.DiagnosticsLogger
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Phone 1's cellular side: reports state, and performs the call operations that
 * standard Android actually permits.
 *
 * Everything here is a public Android API. Nothing requires root, and nothing
 * attempts to work around a platform restriction. Where an operation is not
 * available the gateway reports it as unavailable rather than failing silently or
 * pretending it worked — see [GatewayCapabilities].
 *
 * **This class does not touch call audio and cannot.** Routing live cellular call
 * audio to another device needs the signature-level `CAPTURE_AUDIO_OUTPUT`
 * permission. See `docs/ANDROID_LIMITATIONS.md` §3.
 */
class TelephonyGateway(
    private val context: Context,
    private val listener: Listener
) : CellularGateway {

    interface Listener {
        fun onCallEvent(event: CallStateTracker.CallEvent)
        fun onStatusChanged(status: GatewayStatus)
    }

    private val appContext = context.applicationContext
    private val tracker = CallStateTracker()
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    private val telephonyManager: TelephonyManager? =
        appContext.getSystemService(TelephonyManager::class.java)

    private val telecomManager: TelecomManager? =
        appContext.getSystemService(TelecomManager::class.java)

    private var telephonyCallback: TelephonyCallback? = null
    private var registered = false

    // ------------------------------------------------------------- lifecycle --

    /**
     * Starts listening for call-state changes.
     *
     * Requires `READ_PHONE_STATE`; without it the gateway still reports SIM state
     * and can still dial, but cannot report call progress. That is surfaced as the
     * missing `telephony.state` capability rather than as a silent gap.
     */
    fun start() {
        if (registered) return
        val manager = telephonyManager ?: return
        if (!isGranted(Manifest.permission.READ_PHONE_STATE)) {
            DiagnosticsLogger.log(
                "gateway",
                "Not listening for call state: READ_PHONE_STATE not granted"
            )
            return
        }

        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallState(TelephonyCodes.callStateName(state))
            }
        }

        try {
            manager.registerTelephonyCallback(callbackExecutor, callback)
            telephonyCallback = callback
            registered = true
            DiagnosticsLogger.log("gateway", "Listening for call state")
        } catch (exception: SecurityException) {
            DiagnosticsLogger.log(
                "gateway",
                "Failed to register call state listener",
                mapOf("errorType" to exception.javaClass.simpleName)
            )
        }
    }

    fun stop() {
        val manager = telephonyManager
        val callback = telephonyCallback
        if (manager != null && callback != null && registered) {
            runCatching { manager.unregisterTelephonyCallback(callback) }
        }
        telephonyCallback = null
        registered = false
    }

    fun shutdown() {
        stop()
        callbackExecutor.shutdownNow()
    }

    private fun handleCallState(stateName: String) {
        val event = tracker.onTelephonyState(stateName) { UUID.randomUUID().toString() }
        if (event != null) {
            DiagnosticsLogger.log(
                "gateway",
                "Call state changed",
                mapOf("state" to event.state, "outgoing" to event.outgoing)
            )
            listener.onCallEvent(event)
        }
        listener.onStatusChanged(readStatus())
    }

    // ----------------------------------------------------------------- status --

    /**
     * Reads current cellular state.
     *
     * Every optional value is read defensively: OEMs return nulls and throw
     * `SecurityException` from places the documentation does not mention, and a
     * gateway that crashes while reporting its own status is worse than one that
     * reports a field as unavailable.
     */
    override fun readStatus(gatewayVersion: String, deviceName: String): GatewayStatus {
        val manager = telephonyManager
        val simState = manager?.let { TelephonyCodes.simStateName(it.simState) }
            ?: TelephonyCodes.SIM_UNKNOWN

        val environment = environment(simState)
        val capabilities = GatewayCapabilities.compute(environment)
        val canReadPhoneState = environment.readPhoneStateGranted

        return GatewayStatus(
            simState = simState,
            callState = readCallState(manager, canReadPhoneState),
            carrier = manager?.networkOperatorName?.takeIf { it.isNotBlank() },
            networkType = readNetworkType(manager, canReadPhoneState),
            signalLevel = readSignalLevel(manager, canReadPhoneState),
            mobileDataState = readDataState(manager),
            batteryPercent = readBatteryPercent(),
            charging = readCharging(),
            gatewayVersion = gatewayVersion,
            deviceName = deviceName,
            capabilities = capabilities.capabilities,
            unavailableReasons = capabilities.unavailableReasons
        )
    }

    fun environment(simState: String = currentSimState()): GatewayEnvironment = GatewayEnvironment(
        hasTelephonyFeature = appContext.packageManager
            .hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
        simUsable = TelephonyCodes.simUsable(simState),
        readPhoneStateGranted = isGranted(Manifest.permission.READ_PHONE_STATE),
        callPhoneGranted = isGranted(Manifest.permission.CALL_PHONE),
        answerPhoneCallsGranted = isGranted(Manifest.permission.ANSWER_PHONE_CALLS),
        sendSmsGranted = isGranted(Manifest.permission.SEND_SMS),
        readSmsGranted = isGranted(Manifest.permission.READ_SMS),
        isDefaultSmsHandler = isDefaultSmsHandler(),
        apiLevel = Build.VERSION.SDK_INT
    )

    private fun currentSimState(): String =
        telephonyManager?.let { TelephonyCodes.simStateName(it.simState) }
            ?: TelephonyCodes.SIM_UNKNOWN

    private fun readCallState(manager: TelephonyManager?, permitted: Boolean): String {
        if (manager == null || !permitted) return TelephonyCodes.CALL_UNKNOWN
        return try {
            TelephonyCodes.callStateName(manager.callStateForSubscription)
        } catch (exception: SecurityException) {
            TelephonyCodes.CALL_UNKNOWN
        }
    }

    private fun readNetworkType(manager: TelephonyManager?, permitted: Boolean): String? {
        if (manager == null || !permitted) return null
        return try {
            TelephonyCodes.networkGeneration(manager.dataNetworkType)
        } catch (exception: SecurityException) {
            null
        }
    }

    private fun readSignalLevel(manager: TelephonyManager?, permitted: Boolean): Int? {
        if (manager == null || !permitted) return null
        return try {
            manager.signalStrength?.level
        } catch (exception: SecurityException) {
            null
        } catch (exception: Exception) {
            // Some OEM builds throw from this path; a missing bar count is not
            // worth taking the gateway down for.
            null
        }
    }

    private fun readDataState(manager: TelephonyManager?): String? = try {
        manager?.let { TelephonyCodes.dataStateName(it.dataState) }
    } catch (exception: SecurityException) {
        null
    }

    private fun batteryIntent(): Intent? = appContext.registerReceiver(
        null,
        IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    )

    private fun readBatteryPercent(): Int? {
        val intent = batteryIntent() ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100) / scale
    }

    private fun readCharging(): Boolean? {
        val intent = batteryIntent() ?: return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        if (status < 0) return null
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun isDefaultSmsHandler(): Boolean = try {
        Telephony.Sms.getDefaultSmsPackage(appContext) == appContext.packageName
    } catch (exception: Exception) {
        false
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ operations --

    /**
     * Places an outgoing call on behalf of the remote client.
     *
     * The number is validated first — see [PhoneNumberValidator] for why that is
     * a security control and not just input hygiene.
     *
     * @return the event to send back: `dialing` on success, `failed` otherwise.
     */
    override fun placeCall(rawNumber: String?): CallStateTracker.CallEvent {
        if (tracker.isTracking()) {
            // Reject without disturbing the call already in progress. Routing this
            // through tracker.onFailed() would reset the tracker and lose the
            // ongoing call's id and start time.
            return failed(CallStateTracker.REASON_BUSY)
        }

        val environment = environment()
        if (!environment.hasTelephonyFeature) {
            return failed(CallStateTracker.REASON_UNSUPPORTED)
        }
        if (!environment.callPhoneGranted) {
            return failed(CallStateTracker.REASON_PERMISSION_DENIED)
        }
        if (!environment.simUsable) {
            return failed(CallStateTracker.REASON_NO_SIM)
        }

        val validation = PhoneNumberValidator.validate(rawNumber)
        if (validation is PhoneNumberValidator.Result.Invalid) {
            DiagnosticsLogger.log(
                "gateway",
                "Rejected call request",
                mapOf("reason" to validation.reason)
            )
            return failed(CallStateTracker.REASON_INVALID_NUMBER)
        }

        val dialable = (validation as PhoneNumberValidator.Result.Valid).dialable
        val manager = telecomManager ?: return failed(CallStateTracker.REASON_UNSUPPORTED)

        val callId = UUID.randomUUID().toString()
        return try {
            // Uri.fromParts, not Uri.parse: it escapes the number as an opaque
            // part rather than letting it be reinterpreted as URI syntax.
            manager.placeCall(Uri.fromParts("tel", dialable, null), Bundle())
            DiagnosticsLogger.log(
                "gateway",
                "Placing call",
                mapOf("number" to PhoneNumberValidator.mask(dialable), "callId" to callId)
            )
            tracker.onOutgoingRequested(callId)
        } catch (exception: SecurityException) {
            failed(CallStateTracker.REASON_PERMISSION_DENIED)
        } catch (exception: Exception) {
            DiagnosticsLogger.log(
                "gateway",
                "Call request failed",
                mapOf("errorType" to exception.javaClass.simpleName)
            )
            failed(CallStateTracker.REASON_PLATFORM_ERROR)
        }
    }

    /** Answers a ringing call. Requires `ANSWER_PHONE_CALLS`. */
    override fun answerCall(): CallStateTracker.CallEvent? {
        if (!isGranted(Manifest.permission.ANSWER_PHONE_CALLS)) {
            return failed(CallStateTracker.REASON_PERMISSION_DENIED)
        }
        val manager = telecomManager ?: return failed(CallStateTracker.REASON_UNSUPPORTED)
        return try {
            manager.acceptRingingCall()
            // No event here: the real confirmation is the telephony state moving
            // to OFFHOOK, which the listener reports. Emitting "active" now would
            // be claiming success before the platform has agreed.
            null
        } catch (exception: SecurityException) {
            failed(CallStateTracker.REASON_PERMISSION_DENIED)
        } catch (exception: Exception) {
            failed(CallStateTracker.REASON_PLATFORM_ERROR)
        }
    }

    /**
     * Ends or rejects the current call.
     *
     * `TelecomManager.endCall()` requires `ANSWER_PHONE_CALLS` and API 28+. It is
     * deprecated in favour of an `InCallService`, which would mean becoming the
     * device's default dialer; that is out of scope, so this uses the supported
     * path and reports failure honestly when the platform refuses.
     */
    @Suppress("DEPRECATION")
    override fun endCall(): CallStateTracker.CallEvent? {
        if (Build.VERSION.SDK_INT < GatewayCapabilities.MIN_API_END_CALL) {
            return failed(CallStateTracker.REASON_UNSUPPORTED)
        }
        if (!isGranted(Manifest.permission.ANSWER_PHONE_CALLS)) {
            return failed(CallStateTracker.REASON_PERMISSION_DENIED)
        }
        val manager = telecomManager ?: return failed(CallStateTracker.REASON_UNSUPPORTED)
        return try {
            val ended = manager.endCall()
            if (!ended) failed(CallStateTracker.REASON_PLATFORM_ERROR) else null
        } catch (exception: SecurityException) {
            failed(CallStateTracker.REASON_PERMISSION_DENIED)
        } catch (exception: Exception) {
            failed(CallStateTracker.REASON_PLATFORM_ERROR)
        }
    }

    /**
     * Builds a failure event **without** mutating the tracker.
     *
     * Pre-flight rejections (no permission, bad number, line busy) must not clear
     * tracking state: either there is no call to clear, or there is one already in
     * progress that this rejection has nothing to do with. Only a call that
     * actually started and then died goes through [CallStateTracker.onFailed].
     */
    private fun failed(reason: String): CallStateTracker.CallEvent =
        CallStateTracker.CallEvent(
            callId = tracker.currentCallId() ?: CallStateTracker.UNKNOWN_CALL,
            state = CallStateTracker.State.FAILED,
            reason = reason,
            outgoing = true
        )
}

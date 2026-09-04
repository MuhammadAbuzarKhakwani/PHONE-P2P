package com.jingcjie.wifi_direct_cable.gateway

import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * What Phone 1 reports about its cellular state.
 *
 * ## The null contract
 *
 * A null field means **"this device or Android version does not expose this
 * value"**, and it is omitted from the JSON entirely. The client renders a
 * missing field as *"Unavailable on this device"*.
 *
 * This is the whole point of the type. It would be easy to default `signalLevel`
 * to 0 or `carrier` to `"Unknown"`, and both would be lies that look like data —
 * a user cannot tell a genuine zero-bar reading from a value the platform never
 * returned. Every optional field here is optional because there are real devices,
 * Android versions, or permission states where the value genuinely is not
 * obtainable.
 *
 * [simState] and [callState] are non-null because `TelephonyManager.getSimState()`
 * and the call-state listener always return something, even if that something is
 * "unknown".
 */
data class GatewayStatus(
    /** Always present; `unknown` is itself a valid answer. */
    val simState: String,
    /** Always present. */
    val callState: String,
    val carrier: String? = null,
    val networkType: String? = null,
    /** 0..4 signal bars, or null when unavailable. */
    val signalLevel: Int? = null,
    val mobileDataState: String? = null,
    val batteryPercent: Int? = null,
    /** True when the gateway is charging, or null if unknown. */
    val charging: Boolean? = null,
    val gatewayVersion: String = "",
    val deviceName: String = "",
    val capabilities: List<String> = emptyList(),
    /**
     * Machine-readable reasons a capability is missing, for the diagnostics page,
     * e.g. `"telephony.dial" -> "permission_not_granted"`.
     */
    val unavailableReasons: Map<String, String> = emptyMap()
) {

    /** Null fields are omitted, never defaulted. See the class comment. */
    fun toJson(): JSONObject {
        val json = JSONObject()
            .put(FIELD_SIM_STATE, simState)
            .put(FIELD_CALL_STATE, callState)
            .put(FIELD_GATEWAY_VERSION, gatewayVersion)
            .put(FIELD_DEVICE_NAME, deviceName)
            .put(FIELD_CAPABILITIES, JSONArray().also { array -> capabilities.forEach(array::put) })

        carrier?.let { json.put(FIELD_CARRIER, it) }
        networkType?.let { json.put(FIELD_NETWORK_TYPE, it) }
        signalLevel?.let { json.put(FIELD_SIGNAL_LEVEL, it) }
        mobileDataState?.let { json.put(FIELD_MOBILE_DATA, it) }
        batteryPercent?.let { json.put(FIELD_BATTERY, it) }
        charging?.let { json.put(FIELD_CHARGING, it) }

        if (unavailableReasons.isNotEmpty()) {
            val reasons = JSONObject()
            unavailableReasons.forEach { (key, value) -> reasons.put(key, value) }
            json.put(FIELD_UNAVAILABLE, reasons)
        }
        return json
    }

    fun hasCapability(capability: String): Boolean = capabilities.contains(capability)

    companion object {
        const val FIELD_SIM_STATE = "simState"
        const val FIELD_CALL_STATE = "callState"
        const val FIELD_CARRIER = "carrier"
        const val FIELD_NETWORK_TYPE = "networkType"
        const val FIELD_SIGNAL_LEVEL = "signalLevel"
        const val FIELD_MOBILE_DATA = "mobileData"
        const val FIELD_BATTERY = "batteryPercent"
        const val FIELD_CHARGING = "charging"
        const val FIELD_GATEWAY_VERSION = "gatewayVersion"
        const val FIELD_DEVICE_NAME = "deviceName"
        const val FIELD_CAPABILITIES = "capabilities"
        const val FIELD_UNAVAILABLE = "unavailable"

        fun fromJson(json: JSONObject): GatewayStatus = GatewayStatus(
            simState = json.optString(FIELD_SIM_STATE, TelephonyCodes.SIM_UNKNOWN),
            callState = json.optString(FIELD_CALL_STATE, TelephonyCodes.CALL_UNKNOWN),
            carrier = json.optStringOrNull(FIELD_CARRIER),
            networkType = json.optStringOrNull(FIELD_NETWORK_TYPE),
            signalLevel = json.optIntOrNull(FIELD_SIGNAL_LEVEL),
            mobileDataState = json.optStringOrNull(FIELD_MOBILE_DATA),
            batteryPercent = json.optIntOrNull(FIELD_BATTERY),
            charging = if (json.has(FIELD_CHARGING)) json.optBoolean(FIELD_CHARGING) else null,
            gatewayVersion = json.optString(FIELD_GATEWAY_VERSION, ""),
            deviceName = json.optString(FIELD_DEVICE_NAME, ""),
            capabilities = json.optJSONArray(FIELD_CAPABILITIES)?.let { array ->
                (0 until array.length()).map { array.optString(it) }
            } ?: emptyList(),
            unavailableReasons = json.optJSONObject(FIELD_UNAVAILABLE)?.let { reasons ->
                reasons.keys().asSequence().associateWith { reasons.optString(it) }
            } ?: emptyMap()
        )

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key).takeIf(String::isNotEmpty) else null

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (has(key) && !isNull(key)) optInt(key) else null
    }
}

/**
 * Maps Android's telephony integer constants to the stable strings used on the
 * wire.
 *
 * Kept separate from any `TelephonyManager` *instance* call so it can be unit
 * tested on a plain JVM: the constants below are compile-time `static final int`
 * values, which inline, unlike the framework methods that return them.
 */
object TelephonyCodes {

    const val SIM_UNKNOWN = "unknown"
    const val SIM_ABSENT = "absent"
    const val SIM_LOCKED = "locked"
    const val SIM_READY = "ready"
    const val SIM_NOT_READY = "notReady"
    const val SIM_DISABLED = "disabled"
    const val SIM_ERROR = "error"
    const val SIM_RESTRICTED = "restricted"

    const val CALL_IDLE = "idle"
    const val CALL_RINGING = "ringing"
    const val CALL_OFFHOOK = "offhook"
    const val CALL_UNKNOWN = "unknown"

    const val DATA_DISCONNECTED = "disconnected"
    const val DATA_CONNECTING = "connecting"
    const val DATA_CONNECTED = "connected"
    const val DATA_SUSPENDED = "suspended"
    const val DATA_DISCONNECTING = "disconnecting"
    const val DATA_UNKNOWN = "unknown"

    fun simStateName(state: Int): String = when (state) {
        TelephonyManager.SIM_STATE_ABSENT -> SIM_ABSENT
        TelephonyManager.SIM_STATE_PIN_REQUIRED,
        TelephonyManager.SIM_STATE_PUK_REQUIRED,
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> SIM_LOCKED
        TelephonyManager.SIM_STATE_READY -> SIM_READY
        TelephonyManager.SIM_STATE_NOT_READY -> SIM_NOT_READY
        TelephonyManager.SIM_STATE_PERM_DISABLED -> SIM_DISABLED
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> SIM_ERROR
        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> SIM_RESTRICTED
        else -> SIM_UNKNOWN
    }

    /** True when a SIM is present and usable for calls. */
    fun simUsable(simState: String): Boolean = simState == SIM_READY

    fun callStateName(state: Int): String = when (state) {
        TelephonyManager.CALL_STATE_IDLE -> CALL_IDLE
        TelephonyManager.CALL_STATE_RINGING -> CALL_RINGING
        TelephonyManager.CALL_STATE_OFFHOOK -> CALL_OFFHOOK
        else -> CALL_UNKNOWN
    }

    fun dataStateName(state: Int): String = when (state) {
        TelephonyManager.DATA_DISCONNECTED -> DATA_DISCONNECTED
        TelephonyManager.DATA_CONNECTING -> DATA_CONNECTING
        TelephonyManager.DATA_CONNECTED -> DATA_CONNECTED
        TelephonyManager.DATA_SUSPENDED -> DATA_SUSPENDED
        TelephonyManager.DATA_DISCONNECTING -> DATA_DISCONNECTING
        else -> DATA_UNKNOWN
    }

    /**
     * A readable network generation.
     *
     * Deliberately coarse. `TelephonyManager.getNetworkType()` returns a long tail
     * of radio technologies whose exact names mean little to a user and vary by
     * OEM, and the precise value needs `READ_PHONE_STATE` on many versions.
     * Bucketing to a generation is honest and stable.
     */
    fun networkGeneration(networkType: Int): String? = when (networkType) {
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN,
        TelephonyManager.NETWORK_TYPE_GSM -> "2G"

        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"

        TelephonyManager.NETWORK_TYPE_LTE,
        TelephonyManager.NETWORK_TYPE_IWLAN -> "LTE"

        TelephonyManager.NETWORK_TYPE_NR -> "5G"

        // NETWORK_TYPE_UNKNOWN and anything added in a future release: report
        // nothing rather than guessing a generation.
        else -> null
    }
}

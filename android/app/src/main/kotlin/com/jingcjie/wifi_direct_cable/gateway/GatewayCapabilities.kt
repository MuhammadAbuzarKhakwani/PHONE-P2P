package com.jingcjie.wifi_direct_cable.gateway

import com.jingcjie.wifi_direct_cable.protocol.ProtocolConstants

/**
 * Everything the capability rules depend on, gathered so the rules themselves are
 * a pure function and can be unit tested without a device.
 */
data class GatewayEnvironment(
    val hasTelephonyFeature: Boolean,
    val simUsable: Boolean,
    val readPhoneStateGranted: Boolean,
    val callPhoneGranted: Boolean,
    val answerPhoneCallsGranted: Boolean,
    val sendSmsGranted: Boolean,
    val readSmsGranted: Boolean,
    val isDefaultSmsHandler: Boolean,
    val apiLevel: Int
)

/**
 * Decides what Phone 1 truthfully advertises it can do.
 *
 * The brief's rule is that a capability the device cannot actually perform must
 * not be offered, and that the reason must be visible rather than the feature
 * silently missing. So this returns both the capability list and a parallel map
 * of *why* each absent capability is absent, which the diagnostics page renders.
 *
 * A capability here means "this device, with the permissions it has right now,
 * can do this". It is recomputed rather than cached, because runtime permissions
 * can be revoked while the app is running and a stale capability list would make
 * the client offer a control that fails.
 */
object GatewayCapabilities {

    const val REASON_NO_TELEPHONY = "no_telephony_hardware"
    const val REASON_PERMISSION = "permission_not_granted"
    const val REASON_NO_SIM = "no_usable_sim"
    const val REASON_DEFAULT_SMS_HANDLER = "requires_default_sms_handler"
    const val REASON_API_LEVEL = "not_supported_on_this_android_version"
    const val REASON_PRIVILEGED = "requires_privileged_or_system_app"

    /** Lowest API level that exposes `TelecomManager.endCall()`. */
    const val MIN_API_END_CALL = 28

    data class Result(
        val capabilities: List<String>,
        val unavailableReasons: Map<String, String>
    )

    fun compute(environment: GatewayEnvironment): Result {
        val capabilities = mutableListOf<String>()
        val reasons = linkedMapOf<String, String>()

        fun offer(capability: String, available: Boolean, reason: String) {
            if (available) capabilities.add(capability) else reasons[capability] = reason
        }

        // Without telephony hardware nothing else can be true, and saying so once
        // is clearer than repeating it against every capability.
        if (!environment.hasTelephonyFeature) {
            return Result(
                capabilities = emptyList(),
                unavailableReasons = linkedMapOf(
                    ProtocolConstants.CAPABILITY_GATEWAY to REASON_NO_TELEPHONY,
                    ProtocolConstants.CAPABILITY_CELLULAR_CALL_AUDIO to REASON_PRIVILEGED
                )
            )
        }

        capabilities.add(ProtocolConstants.CAPABILITY_GATEWAY)
        capabilities.add(ProtocolConstants.CAPABILITY_GATEWAY_STATUS)

        offer(
            ProtocolConstants.CAPABILITY_TELEPHONY_STATE,
            environment.readPhoneStateGranted,
            REASON_PERMISSION
        )

        // Dialling needs both the permission and a SIM that can actually place a
        // call. Advertising it with no SIM would offer a button that always fails.
        offer(
            ProtocolConstants.CAPABILITY_TELEPHONY_DIAL,
            environment.callPhoneGranted && environment.simUsable,
            if (!environment.callPhoneGranted) REASON_PERMISSION else REASON_NO_SIM
        )

        offer(
            ProtocolConstants.CAPABILITY_TELEPHONY_ANSWER,
            environment.answerPhoneCallsGranted && environment.apiLevel >= MIN_API_END_CALL,
            if (!environment.answerPhoneCallsGranted) REASON_PERMISSION else REASON_API_LEVEL
        )

        offer(
            ProtocolConstants.CAPABILITY_SMS_SEND,
            environment.sendSmsGranted && environment.simUsable,
            if (!environment.sendSmsGranted) REASON_PERMISSION else REASON_NO_SIM
        )

        // Reading SMS needs the permission *and* the default-handler role. Google
        // Play restricts READ_SMS to the default SMS app, so a build that merely
        // holds the permission is not enough in practice.
        offer(
            ProtocolConstants.CAPABILITY_SMS_READ,
            environment.readSmsGranted && environment.isDefaultSmsHandler,
            if (!environment.readSmsGranted) REASON_PERMISSION else REASON_DEFAULT_SMS_HANDLER
        )

        // Never advertised by any build, on any device. Routing live cellular call
        // audio needs the signature-level CAPTURE_AUDIO_OUTPUT permission; the
        // VOICE_CALL audio sources are closed to third-party apps and
        // AudioPlaybackCaptureConfiguration explicitly excludes the call stream.
        // Listed as explicitly unavailable so the client can say so, rather than
        // the capability merely being absent and looking like an oversight.
        reasons[ProtocolConstants.CAPABILITY_CELLULAR_CALL_AUDIO] = REASON_PRIVILEGED

        return Result(capabilities, reasons)
    }

    /**
     * Human-readable explanation for the diagnostics page.
     *
     * Kept here next to the reasons themselves so a new reason cannot be added
     * without an accompanying explanation.
     */
    fun explain(reason: String): String = when (reason) {
        REASON_NO_TELEPHONY -> "This device has no cellular hardware."
        REASON_PERMISSION -> "The required permission has not been granted."
        REASON_NO_SIM -> "No usable SIM is present."
        REASON_DEFAULT_SMS_HANDLER ->
            "Android restricts this to the app set as the default SMS handler."
        REASON_API_LEVEL -> "This Android version does not support the operation."
        REASON_PRIVILEGED ->
            "Not available to a normal app. Routing live cellular call audio " +
                "requires a privileged or system-signed build."
        else -> "Unavailable on this device."
    }
}

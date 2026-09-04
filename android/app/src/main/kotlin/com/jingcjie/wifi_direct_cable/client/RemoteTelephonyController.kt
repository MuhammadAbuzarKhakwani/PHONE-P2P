package com.jingcjie.wifi_direct_cable.client

import com.jingcjie.wifi_direct_cable.diagnostics.DiagnosticsLogger
import com.jingcjie.wifi_direct_cable.gateway.CallStateTracker
import com.jingcjie.wifi_direct_cable.gateway.GatewayStatus
import com.jingcjie.wifi_direct_cable.gateway.PhoneNumberValidator
import com.jingcjie.wifi_direct_cable.protocol.ProtocolConstants
import com.jingcjie.wifi_direct_cable.protocol.ProtocolFrame
import com.jingcjie.wifi_direct_cable.protocol.ProtocolFrameType
import org.json.JSONObject
import java.util.UUID

/**
 * Phone 2's side of the remote SIM link: sends call commands and tracks what the
 * gateway reports back.
 *
 * ## Failing locally rather than round-tripping
 *
 * Requests that cannot possibly succeed are refused here, before anything goes on
 * the wire — an invalid number, a gateway that has not advertised the matching
 * capability, an unauthenticated session. Sending them anyway would work (the
 * gateway rejects them too, and must), but the user would wait for a network
 * round trip to be told something this device already knew, and a failure that
 * arrives late reads like a flaky link rather than a clear "not supported here".
 *
 * The gateway's own checks remain the security boundary. Nothing here is trusted
 * to protect the SIM; this is about telling the user the truth quickly.
 *
 * Transport-agnostic and Android-free so the whole thing is unit tested.
 */
class RemoteTelephonyController(
    private val isSecure: () -> Boolean,
    private val sendFrame: (ProtocolFrameType, JSONObject) -> Unit,
    private val listener: Listener
) {

    interface Listener {
        fun onGatewayStatus(status: GatewayStatus)
        fun onCallState(snapshot: CallSnapshot)

        /** Conversations and messages the gateway holds. */
        fun onSmsList(conversations: List<Map<String, Any?>>, messages: List<Map<String, Any?>>)

        /** Outcome of a send request. [status] is `sent` or `failed`. */
        fun onSmsEvent(requestId: String?, status: String, reason: String?, segments: Int?)
    }

    /** A parsed `call.state` event. */
    data class CallSnapshot(
        val callId: String,
        val state: String,
        val reason: String? = null,
        val durationMs: Long? = null,
        /**
         * False for every outgoing call: Android cannot tell an unprivileged
         * gateway when the callee actually answered. The UI must present an
         * outgoing call's timer as time since dialling, not talk time.
         */
        val answerConfirmed: Boolean = false,
        val outgoing: Boolean = false,
        val requestId: String? = null
    ) {
        val isTerminal: Boolean
            get() = state == CallStateTracker.State.ENDED || state == CallStateTracker.State.FAILED

        fun toMap(): Map<String, Any?> = mapOf(
            "callId" to callId,
            "state" to state,
            "reason" to reason,
            "durationMs" to durationMs,
            "answerConfirmed" to answerConfirmed,
            "outgoing" to outgoing,
            "requestId" to requestId
        )

        companion object {
            fun fromJson(json: JSONObject): CallSnapshot = CallSnapshot(
                callId = json.optString("callId", CallStateTracker.UNKNOWN_CALL),
                state = json.optString("state", CallStateTracker.State.FAILED),
                reason = json.optString("reason", "").takeIf(String::isNotEmpty),
                durationMs = if (json.has("durationMs")) json.optLong("durationMs") else null,
                answerConfirmed = json.optBoolean("answerConfirmed", false),
                outgoing = json.optBoolean("outgoing", false),
                requestId = json.optString("requestId", "").takeIf(String::isNotEmpty)
            )
        }
    }

    @Volatile
    private var lastStatus: GatewayStatus? = null

    @Volatile
    private var activeCall: CallSnapshot? = null

    fun lastStatus(): GatewayStatus? = lastStatus

    fun activeCall(): CallSnapshot? = activeCall

    /** Cleared when the session drops, so a stale gateway state is never shown. */
    fun reset() {
        lastStatus = null
        activeCall = null
    }

    // -------------------------------------------------------------- inbound --

    /** @return true if [frame] was a client-side gateway frame and was consumed. */
    fun handle(frame: ProtocolFrame): Boolean {
        val metadata = if (frame.metadataJson.isBlank()) {
            JSONObject()
        } else {
            runCatching { JSONObject(frame.metadataJson) }.getOrElse { JSONObject() }
        }

        return when (frame.type) {
            ProtocolFrameType.GATEWAY_STATUS -> {
                val status = GatewayStatus.fromJson(metadata)
                lastStatus = status
                DiagnosticsLogger.log(
                    "client",
                    "Gateway status received",
                    mapOf("simState" to status.simState, "capabilities" to status.capabilities.size)
                )
                listener.onGatewayStatus(status)
                true
            }

            ProtocolFrameType.SMS_LIST -> {
                listener.onSmsList(
                    jsonArrayToMaps(metadata.optJSONArray("conversations")),
                    jsonArrayToMaps(metadata.optJSONArray("messages"))
                )
                true
            }

            ProtocolFrameType.SMS_EVENT -> {
                listener.onSmsEvent(
                    requestId = metadata.optString("requestId", "").takeIf(String::isNotEmpty),
                    status = metadata.optString("status", "failed"),
                    reason = metadata.optString("reason", "").takeIf(String::isNotEmpty),
                    segments = if (metadata.has("segments")) metadata.optInt("segments") else null
                )
                true
            }

            ProtocolFrameType.CALL_STATE -> {
                val snapshot = CallSnapshot.fromJson(metadata)
                activeCall = if (snapshot.isTerminal) null else snapshot
                DiagnosticsLogger.log(
                    "client",
                    "Remote call state",
                    mapOf("state" to snapshot.state, "reason" to (snapshot.reason ?: ""))
                )
                listener.onCallState(snapshot)
                true
            }

            else -> false
        }
    }

    // ------------------------------------------------------------- outbound --

    fun requestStatus(): Boolean {
        if (!isSecure()) return false
        sendFrame(ProtocolFrameType.GATEWAY_STATUS_REQUEST, JSONObject())
        return true
    }

    /**
     * Asks the gateway to dial.
     *
     * @return the request id, correlating the eventual `call.state` events, or
     *         null if the request was refused locally — in which case [listener]
     *         has already been given a `failed` snapshot explaining why.
     */
    fun placeCall(rawNumber: String?): String? {
        if (!isSecure()) return refuse(GATE_NOT_AUTHENTICATED)

        val validation = PhoneNumberValidator.validate(rawNumber)
        if (validation is PhoneNumberValidator.Result.Invalid) {
            return refuse(CallStateTracker.REASON_INVALID_NUMBER)
        }

        if (!supports(ProtocolConstants.CAPABILITY_TELEPHONY_DIAL)) {
            return refuse(unavailableReason(ProtocolConstants.CAPABILITY_TELEPHONY_DIAL))
        }

        if (activeCall != null) {
            return refuse(CallStateTracker.REASON_BUSY)
        }

        val requestId = UUID.randomUUID().toString()
        val dialable = (validation as PhoneNumberValidator.Result.Valid).dialable
        DiagnosticsLogger.log(
            "client",
            "Requesting call",
            mapOf("number" to PhoneNumberValidator.mask(dialable), "requestId" to requestId)
        )
        sendFrame(
            ProtocolFrameType.CALL_REQUEST,
            JSONObject().put("requestId", requestId).put("number", dialable)
        )
        return requestId
    }

    /**
     * Asks the gateway for its conversations.
     *
     * @return false when the gateway has not advertised [ProtocolConstants.CAPABILITY_SMS_READ] —
     *         which is the common case, since Android restricts reading SMS to the
     *         device's default SMS handler. Nothing is sent in that case.
     */
    fun requestSmsList(limit: Int = 200): Boolean {
        if (!isSecure() || !supports(ProtocolConstants.CAPABILITY_SMS_READ)) return false
        sendFrame(ProtocolFrameType.SMS_LIST_REQUEST, JSONObject().put("limit", limit))
        return true
    }

    /**
     * Asks the gateway to send an SMS.
     *
     * @return the request id, or null if refused locally. The recipient is
     *         validated here as well as on the gateway, so an MMI sequence never
     *         leaves this device.
     */
    fun sendSms(address: String?, body: String?): String? {
        if (!isSecure()) {
            listener.onSmsEvent(null, "failed", GATE_NOT_AUTHENTICATED, null)
            return null
        }
        if (!supports(ProtocolConstants.CAPABILITY_SMS_SEND)) {
            listener.onSmsEvent(
                null,
                "failed",
                unavailableReason(ProtocolConstants.CAPABILITY_SMS_SEND),
                null
            )
            return null
        }
        if (PhoneNumberValidator.validate(address) is PhoneNumberValidator.Result.Invalid) {
            listener.onSmsEvent(null, "failed", CallStateTracker.REASON_INVALID_NUMBER, null)
            return null
        }
        if (body.isNullOrEmpty()) {
            listener.onSmsEvent(null, "failed", "empty_body", null)
            return null
        }

        val requestId = UUID.randomUUID().toString()
        sendFrame(
            ProtocolFrameType.SMS_SEND_REQUEST,
            JSONObject()
                .put("requestId", requestId)
                .put("address", address)
                .put("body", body)
        )
        return requestId
    }

    fun answerCall(): Boolean = sendCallCommand(
        ProtocolFrameType.CALL_ANSWER,
        ProtocolConstants.CAPABILITY_TELEPHONY_ANSWER
    )

    fun rejectCall(): Boolean = sendCallCommand(
        ProtocolFrameType.CALL_REJECT,
        ProtocolConstants.CAPABILITY_TELEPHONY_ANSWER
    )

    fun hangUp(): Boolean = sendCallCommand(
        ProtocolFrameType.CALL_HANGUP,
        ProtocolConstants.CAPABILITY_TELEPHONY_ANSWER
    )

    /**
     * True when the gateway has told us it can do this.
     *
     * A gateway that has not reported its status yet supports nothing, so the UI
     * shows controls as unavailable until the first `gateway.status` arrives
     * rather than offering buttons that might fail.
     */
    fun supports(capability: String): Boolean =
        lastStatus?.hasCapability(capability) == true

    /**
     * Why a capability is unavailable, as reported by the gateway. Falls back to
     * a generic reason when the gateway has not been heard from at all.
     */
    fun unavailableReason(capability: String): String =
        lastStatus?.unavailableReasons?.get(capability) ?: GATE_NO_STATUS

    private fun sendCallCommand(type: ProtocolFrameType, capability: String): Boolean {
        if (!isSecure()) {
            refuse(GATE_NOT_AUTHENTICATED)
            return false
        }
        if (!supports(capability)) {
            refuse(unavailableReason(capability))
            return false
        }
        sendFrame(type, JSONObject())
        return true
    }

    /** Reports a locally-refused request as a failed call state, and returns null. */
    private fun refuse(reason: String): String? {
        val snapshot = CallSnapshot(
            callId = activeCall?.callId ?: CallStateTracker.UNKNOWN_CALL,
            state = CallStateTracker.State.FAILED,
            reason = reason,
            outgoing = true
        )
        DiagnosticsLogger.log("client", "Refused call request locally", mapOf("reason" to reason))
        listener.onCallState(snapshot)
        return null
    }

    private fun jsonArrayToMaps(array: org.json.JSONArray?): List<Map<String, Any?>> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { item ->
                item.keys().asSequence().associateWith { key -> item.opt(key) }
            }
        }
    }

    companion object {
        const val GATE_NOT_AUTHENTICATED = "session_not_authenticated"

        /** The gateway has not sent a status yet, so nothing can be assumed. */
        const val GATE_NO_STATUS = "gateway_status_unknown"
    }
}

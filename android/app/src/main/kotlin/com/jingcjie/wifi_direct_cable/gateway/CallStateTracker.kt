package com.jingcjie.wifi_direct_cable.gateway

import org.json.JSONObject

/**
 * Turns Android's three telephony call states into the five states the protocol
 * reports to the client.
 *
 * ## What Android actually tells us
 *
 * An unprivileged app sees only `IDLE`, `RINGING` and `OFFHOOK` from
 * `TelephonyManager`. It does **not** see:
 *
 * - whether the far end has answered an outgoing call, and
 * - the difference between "dialling" and "connected".
 *
 * `OFFHOOK` means *the line is in use*. On an outgoing call the radio goes
 * off-hook while the remote phone is still ringing, so treating `OFFHOOK` as
 * "answered" would start a call timer that is simply wrong.
 *
 * So [CallEvent.answerConfirmed] is false for every outgoing call, and the client
 * must present the duration as time since dialling rather than talk time.
 * Distinguishing the two requires an `InCallService`, which requires becoming the
 * device's default dialer — a much larger commitment than this feature warrants,
 * and out of scope here.
 *
 * Deliberately pure: no `Context`, no `TelephonyManager`, so every transition is
 * unit tested.
 */
class CallStateTracker(private val clock: () -> Long = System::currentTimeMillis) {

    /** Protocol-level call states. */
    object State {
        const val DIALING = "dialing"
        const val RINGING = "ringing"
        const val ACTIVE = "active"
        const val ENDED = "ended"
        const val FAILED = "failed"
    }

    data class CallEvent(
        val callId: String,
        val state: String,
        /** Set on [State.FAILED]. */
        val reason: String? = null,
        /** Milliseconds since the call began, set on [State.ENDED]. */
        val durationMs: Long? = null,
        /**
         * True only when the platform genuinely told us the call is connected —
         * which, for an unprivileged app, is never on an outgoing call. See the
         * class comment.
         */
        val answerConfirmed: Boolean = false,
        val outgoing: Boolean = false
    ) {
        fun toJson(): JSONObject {
            val json = JSONObject()
                .put("callId", callId)
                .put("state", state)
                .put("answerConfirmed", answerConfirmed)
                .put("outgoing", outgoing)
            reason?.let { json.put("reason", it) }
            durationMs?.let { json.put("durationMs", it) }
            return json
        }
    }

    private var callId: String? = null
    private var outgoing = false
    private var startedAtMs = 0L
    private var lastTelephonyState = TelephonyCodes.CALL_IDLE
    private var finished = false

    /** The call currently being tracked, or null when idle. */
    fun currentCallId(): String? = callId

    fun isTracking(): Boolean = callId != null && !finished

    fun durationMs(): Long = if (startedAtMs == 0L) 0L else clock() - startedAtMs

    /**
     * The gateway has asked the platform to place a call.
     *
     * Emitted immediately, before any telephony state change, so the client's UI
     * responds at once rather than after the radio catches up.
     */
    fun onOutgoingRequested(newCallId: String): CallEvent {
        callId = newCallId
        outgoing = true
        finished = false
        startedAtMs = clock()
        return CallEvent(newCallId, State.DIALING, outgoing = true)
    }

    /**
     * Records a telephony state change.
     *
     * @param telephonyState one of [TelephonyCodes.CALL_IDLE], `CALL_RINGING`,
     *        `CALL_OFFHOOK`.
     * @param incomingCallIdFactory supplies an id when an incoming call appears
     *        that this tracker did not initiate.
     * @return the event to send to the client, or null if nothing changed that
     *         the client needs to know about.
     */
    fun onTelephonyState(
        telephonyState: String,
        incomingCallIdFactory: () -> String
    ): CallEvent? {
        val previous = lastTelephonyState
        lastTelephonyState = telephonyState
        if (previous == telephonyState) return null

        return when (telephonyState) {
            TelephonyCodes.CALL_RINGING -> {
                // An incoming call. If we were not already tracking one, this is a
                // new inbound call and needs an id of its own.
                if (!isTracking()) {
                    callId = incomingCallIdFactory()
                    outgoing = false
                    finished = false
                    startedAtMs = clock()
                }
                CallEvent(callId!!, State.RINGING, outgoing = outgoing)
            }

            TelephonyCodes.CALL_OFFHOOK -> {
                if (!isTracking()) {
                    // A call started outside the app, e.g. the user dialled on the
                    // gateway itself. Report it rather than pretending the line is
                    // free; the client needs to know the SIM is busy.
                    callId = incomingCallIdFactory()
                    outgoing = false
                    finished = false
                    startedAtMs = clock()
                }
                CallEvent(
                    callId = callId!!,
                    state = State.ACTIVE,
                    // Only an inbound call that we watched go RINGING -> OFFHOOK is
                    // genuinely confirmed as answered. Outgoing calls go off-hook
                    // while the far end is still ringing.
                    answerConfirmed = !outgoing && previous == TelephonyCodes.CALL_RINGING,
                    outgoing = outgoing
                )
            }

            TelephonyCodes.CALL_IDLE -> {
                val endingCallId = callId ?: return null
                val event = CallEvent(
                    callId = endingCallId,
                    state = State.ENDED,
                    durationMs = durationMs(),
                    outgoing = outgoing
                )
                reset()
                event
            }

            else -> null
        }
    }

    /**
     * The call could not be placed at all — a rejected request, a missing
     * permission, an invalid number.
     */
    fun onFailed(reason: String): CallEvent {
        val failedCallId = callId ?: UNKNOWN_CALL
        val event = CallEvent(
            callId = failedCallId,
            state = State.FAILED,
            reason = reason,
            outgoing = outgoing
        )
        reset()
        return event
    }

    fun reset() {
        callId = null
        outgoing = false
        startedAtMs = 0L
        finished = true
    }

    companion object {
        const val UNKNOWN_CALL = "unknown"

        // Failure reasons, mirrored in docs/PROTOCOL.md.
        const val REASON_PERMISSION_DENIED = "permission_denied"
        const val REASON_UNSUPPORTED = "unsupported_on_device"
        const val REASON_NO_SIM = "no_sim"
        const val REASON_INVALID_NUMBER = "invalid_number"
        const val REASON_REJECTED = "rejected_by_gateway"
        const val REASON_BUSY = "gateway_busy"
        const val REASON_PLATFORM_ERROR = "platform_error"
    }
}

package com.jingcjie.wifi_direct_cable.gateway

import com.jingcjie.wifi_direct_cable.diagnostics.DiagnosticsLogger
import com.jingcjie.wifi_direct_cable.protocol.ProtocolFrame
import com.jingcjie.wifi_direct_cable.protocol.ProtocolFrameType
import org.json.JSONObject
import java.util.UUID

/**
 * Routes the gateway and telephony frames arriving on the control channel.
 *
 * ## The security gate
 *
 * Every telephony command is refused unless the session is running the v3
 * authenticated framing. This is the reason the security layer was built before
 * this one: a remote "place a call" command on an unauthenticated link would let
 * any nearby device that completed a Wi-Fi Direct connection dial on Phone 1's
 * SIM, at the SIM owner's expense, with no pairing and no consent.
 *
 * The gate is enforced here rather than being left to the caller, so there is a
 * single place where the rule lives and it cannot be forgotten by a future call
 * site.
 *
 * Status requests are gated too. Carrier, signal, and SIM state are information
 * about a real person's device and are not handed to an unauthenticated peer.
 */
class GatewaySessionHandler(
    private val gateway: CellularGateway,
    private val sms: SmsGatewayApi,
    private val isSecure: () -> Boolean,
    private val sendFrame: (ProtocolFrameType, JSONObject) -> Unit,
    private val gatewayVersion: String = "",
    private val deviceName: String = ""
) {

    /**
     * @return true if [frame] was a gateway frame and has been dealt with, so the
     *         caller should not fall through to its own handling.
     */
    fun handle(frame: ProtocolFrame): Boolean {
        val type = frame.type
        if (type !in HANDLED) return false

        if (!isSecure()) {
            DiagnosticsLogger.log(
                "gateway",
                "Refused a gateway frame on an unauthenticated session",
                mapOf("frameType" to type.protocolName)
            )
            sendFrame(
                ProtocolFrameType.CALL_STATE,
                JSONObject()
                    .put("callId", CallStateTracker.UNKNOWN_CALL)
                    .put("state", CallStateTracker.State.FAILED)
                    .put("reason", REASON_NOT_AUTHENTICATED)
            )
            return true
        }

        val metadata = if (frame.metadataJson.isBlank()) {
            JSONObject()
        } else {
            runCatching { JSONObject(frame.metadataJson) }.getOrElse { JSONObject() }
        }

        when (type) {
            ProtocolFrameType.GATEWAY_STATUS_REQUEST -> sendStatus()

            ProtocolFrameType.CALL_REQUEST -> {
                val requestId = metadata.optString("requestId", UUID.randomUUID().toString())
                // The number is validated inside placeCall; see PhoneNumberValidator
                // for why that is a security control and not input tidying.
                val event = gateway.placeCall(metadata.optString("number", ""))
                sendFrame(
                    ProtocolFrameType.CALL_STATE,
                    event.toJson().put("requestId", requestId)
                )
                sendStatus()
            }

            ProtocolFrameType.CALL_ANSWER -> {
                gateway.answerCall()?.let { sendFrame(ProtocolFrameType.CALL_STATE, it.toJson()) }
            }

            // Reject and hang up are the same platform operation: end whatever
            // call is current. They stay distinct on the wire because they mean
            // different things to the user and read differently in diagnostics.
            ProtocolFrameType.CALL_REJECT,
            ProtocolFrameType.CALL_HANGUP -> {
                gateway.endCall()?.let { sendFrame(ProtocolFrameType.CALL_STATE, it.toJson()) }
            }

            ProtocolFrameType.SMS_LIST_REQUEST -> {
                val limit = metadata.optInt("limit", SmsGateway.DEFAULT_LIMIT)
                val conversations = sms.readConversations(limit)
                sendFrame(
                    ProtocolFrameType.SMS_LIST,
                    JSONObject()
                        .put("conversations", SmsConversation.listToJson(conversations))
                        .put(
                            "messages",
                            org.json.JSONArray().also { array ->
                                sms.readRecent(limit).forEach { array.put(it.toJson()) }
                            }
                        )
                )
            }

            ProtocolFrameType.SMS_SEND_REQUEST -> {
                val requestId = metadata.optString("requestId", UUID.randomUUID().toString())
                val result = sms.send(
                    metadata.optString("address", ""),
                    metadata.optString("body", "")
                )
                val event = JSONObject().put("requestId", requestId)
                when (result) {
                    is SmsSendResult.Sent -> event
                        // "sent" means accepted for sending. Delivery reports are
                        // carrier-dependent and not registered here, so the client
                        // must not present this as delivered.
                        .put("status", "sent")
                        .put("segments", result.segments)

                    is SmsSendResult.Failed -> event
                        .put("status", "failed")
                        .put("reason", result.reason)
                }
                sendFrame(ProtocolFrameType.SMS_EVENT, event)
            }

            else -> return false
        }
        return true
    }

    /** Pushes the current status, unprompted, e.g. after a call-state change. */
    fun sendStatus() {
        val status = gateway.readStatus(gatewayVersion, deviceName)
        sendFrame(ProtocolFrameType.GATEWAY_STATUS, status.toJson())
    }

    /** Forwards a call event raised by the platform rather than by a request. */
    fun sendCallEvent(event: CallStateTracker.CallEvent) {
        if (!isSecure()) return
        sendFrame(ProtocolFrameType.CALL_STATE, event.toJson())
    }

    companion object {
        const val REASON_NOT_AUTHENTICATED = "session_not_authenticated"

        private val HANDLED = setOf(
            ProtocolFrameType.GATEWAY_STATUS_REQUEST,
            ProtocolFrameType.CALL_REQUEST,
            ProtocolFrameType.CALL_ANSWER,
            ProtocolFrameType.CALL_REJECT,
            ProtocolFrameType.CALL_HANGUP,
            ProtocolFrameType.SMS_LIST_REQUEST,
            ProtocolFrameType.SMS_SEND_REQUEST
        )
    }
}

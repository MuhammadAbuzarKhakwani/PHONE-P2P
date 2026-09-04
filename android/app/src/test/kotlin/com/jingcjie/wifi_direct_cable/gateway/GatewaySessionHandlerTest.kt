package com.jingcjie.wifi_direct_cable.gateway

import com.jingcjie.wifi_direct_cable.protocol.ProtocolChannel
import com.jingcjie.wifi_direct_cable.protocol.ProtocolFrame
import com.jingcjie.wifi_direct_cable.protocol.ProtocolFrameType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewaySessionHandlerTest {

    private class FakeGateway : CellularGateway {
        var placeCallCount = 0
        var answerCount = 0
        var endCount = 0
        var lastNumber: String? = null
        var statusReads = 0

        /** Set to make the fake report a specific outcome. */
        var placeCallResult: CallStateTracker.CallEvent =
            CallStateTracker.CallEvent("call-1", CallStateTracker.State.DIALING, outgoing = true)

        override fun readStatus(gatewayVersion: String, deviceName: String): GatewayStatus {
            statusReads++
            return GatewayStatus(
                simState = TelephonyCodes.SIM_READY,
                callState = TelephonyCodes.CALL_IDLE,
                gatewayVersion = gatewayVersion,
                deviceName = deviceName
            )
        }

        override fun placeCall(rawNumber: String?): CallStateTracker.CallEvent {
            placeCallCount++
            lastNumber = rawNumber
            return placeCallResult
        }

        override fun answerCall(): CallStateTracker.CallEvent? {
            answerCount++
            return null
        }

        override fun endCall(): CallStateTracker.CallEvent? {
            endCount++
            return null
        }
    }

    private class FakeSms : SmsGatewayApi {
        var sendCount = 0
        var readCount = 0
        var lastAddress: String? = null
        var lastBody: String? = null
        var sendResult: SmsSendResult = SmsSendResult.Sent(1)
        var conversations: List<SmsConversation> = emptyList()
        var messages: List<SmsMessageSummary> = emptyList()

        override fun send(rawAddress: String?, body: String?): SmsSendResult {
            sendCount++
            lastAddress = rawAddress
            lastBody = body
            return sendResult
        }

        override fun readConversations(limit: Int): List<SmsConversation> {
            readCount++
            return conversations
        }

        override fun readRecent(limit: Int): List<SmsMessageSummary> = messages
    }

    private val gateway = FakeGateway()
    private val sms = FakeSms()
    private val sent = mutableListOf<Pair<ProtocolFrameType, JSONObject>>()
    private var secure = true

    private fun handler() = GatewaySessionHandler(
        gateway = gateway,
        sms = sms,
        isSecure = { secure },
        sendFrame = { type, json -> sent.add(type to json) },
        gatewayVersion = "3.0.0",
        deviceName = "Pixel"
    )

    private fun frame(type: ProtocolFrameType, metadata: String = "") = ProtocolFrame(
        type = type,
        channel = ProtocolChannel.CONTROL,
        metadataJson = metadata
    )

    // ------------------------------------------------------- the security gate --

    /**
     * The most important test in this package.
     *
     * If a call command is ever accepted on an unauthenticated session, any nearby
     * device that completes a Wi-Fi Direct connection can dial on the gateway's
     * SIM without pairing and at the SIM owner's expense.
     */
    @Test
    fun `call commands are refused on an unauthenticated session`() {
        secure = false
        val handled = handler().handle(frame(ProtocolFrameType.CALL_REQUEST, """{"number":"5551234567"}"""))

        assertTrue("the frame must be consumed, not passed on", handled)
        assertEquals("the gateway must never be reached", 0, gateway.placeCallCount)

        val (type, json) = sent.single()
        assertEquals(ProtocolFrameType.CALL_STATE, type)
        assertEquals(CallStateTracker.State.FAILED, json.getString("state"))
        assertEquals(GatewaySessionHandler.REASON_NOT_AUTHENTICATED, json.getString("reason"))
    }

    @Test
    fun `every gateway frame type is refused on an unauthenticated session`() {
        secure = false
        val handler = handler()
        for (type in listOf(
            ProtocolFrameType.GATEWAY_STATUS_REQUEST,
            ProtocolFrameType.CALL_REQUEST,
            ProtocolFrameType.CALL_ANSWER,
            ProtocolFrameType.CALL_REJECT,
            ProtocolFrameType.CALL_HANGUP,
            ProtocolFrameType.SMS_LIST_REQUEST,
            ProtocolFrameType.SMS_SEND_REQUEST
        )) {
            assertTrue(handler.handle(frame(type)))
        }

        assertEquals(0, gateway.placeCallCount)
        assertEquals(0, gateway.answerCount)
        assertEquals(0, gateway.endCount)
        // Carrier and SIM state are personal data and must not leak either.
        assertEquals(0, gateway.statusReads)
        // Message contents are the most sensitive thing the gateway holds.
        assertEquals(0, sms.sendCount)
        assertEquals(0, sms.readCount)
    }

    @Test
    fun `unsolicited call events are not pushed to an unauthenticated peer`() {
        secure = false
        handler().sendCallEvent(
            CallStateTracker.CallEvent("call-1", CallStateTracker.State.RINGING)
        )
        assertTrue(sent.isEmpty())
    }

    // ------------------------------------------------------------- dispatch --

    @Test
    fun `a status request is answered with the current status`() {
        assertTrue(handler().handle(frame(ProtocolFrameType.GATEWAY_STATUS_REQUEST)))

        val (type, json) = sent.single()
        assertEquals(ProtocolFrameType.GATEWAY_STATUS, type)
        assertEquals(TelephonyCodes.SIM_READY, json.getString(GatewayStatus.FIELD_SIM_STATE))
        assertEquals("3.0.0", json.getString(GatewayStatus.FIELD_GATEWAY_VERSION))
    }

    @Test
    fun `a call request reaches the gateway and echoes the request id`() {
        val handled = handler().handle(
            frame(ProtocolFrameType.CALL_REQUEST, """{"requestId":"req-9","number":"+15551234567"}""")
        )

        assertTrue(handled)
        assertEquals(1, gateway.placeCallCount)
        assertEquals("+15551234567", gateway.lastNumber)

        val callState = sent.first { it.first == ProtocolFrameType.CALL_STATE }.second
        assertEquals("req-9", callState.getString("requestId"))
        assertEquals(CallStateTracker.State.DIALING, callState.getString("state"))

        // A status push follows so the client sees the line go busy.
        assertTrue(sent.any { it.first == ProtocolFrameType.GATEWAY_STATUS })
    }

    @Test
    fun `a failed call request is reported back with its reason`() {
        gateway.placeCallResult = CallStateTracker.CallEvent(
            callId = CallStateTracker.UNKNOWN_CALL,
            state = CallStateTracker.State.FAILED,
            reason = CallStateTracker.REASON_INVALID_NUMBER
        )

        handler().handle(frame(ProtocolFrameType.CALL_REQUEST, """{"number":"*#06#"}"""))

        val callState = sent.first { it.first == ProtocolFrameType.CALL_STATE }.second
        assertEquals(CallStateTracker.State.FAILED, callState.getString("state"))
        assertEquals(CallStateTracker.REASON_INVALID_NUMBER, callState.getString("reason"))
    }

    @Test
    fun `answer reject and hangup reach the gateway`() {
        val handler = handler()
        handler.handle(frame(ProtocolFrameType.CALL_ANSWER))
        handler.handle(frame(ProtocolFrameType.CALL_REJECT))
        handler.handle(frame(ProtocolFrameType.CALL_HANGUP))

        assertEquals(1, gateway.answerCount)
        // Reject and hang up are the same platform operation.
        assertEquals(2, gateway.endCount)
    }

    @Test
    fun `a successful answer sends nothing until the platform confirms`() {
        // Emitting "active" on request would claim success before the radio agreed.
        handler().handle(frame(ProtocolFrameType.CALL_ANSWER))
        assertTrue(sent.isEmpty())
    }

    // ------------------------------------------------------------------ SMS --

    @Test
    fun `an SMS send request reaches the gateway and reports acceptance`() {
        sms.sendResult = SmsSendResult.Sent(segments = 2)

        assertTrue(
            handler().handle(
                frame(
                    ProtocolFrameType.SMS_SEND_REQUEST,
                    """{"requestId":"req-1","address":"+15551234567","body":"hello"}"""
                )
            )
        )

        assertEquals(1, sms.sendCount)
        assertEquals("+15551234567", sms.lastAddress)
        assertEquals("hello", sms.lastBody)

        val (type, json) = sent.single()
        assertEquals(ProtocolFrameType.SMS_EVENT, type)
        assertEquals("req-1", json.getString("requestId"))
        // "sent" means accepted for sending, not delivered.
        assertEquals("sent", json.getString("status"))
        assertEquals(2, json.getInt("segments"))
    }

    @Test
    fun `a failed SMS send reports its reason`() {
        sms.sendResult = SmsSendResult.Failed(SmsRequestValidator.REASON_BODY_TOO_LONG)

        handler().handle(
            frame(ProtocolFrameType.SMS_SEND_REQUEST, """{"address":"+15551234567","body":"x"}""")
        )

        val json = sent.single().second
        assertEquals("failed", json.getString("status"))
        assertEquals(SmsRequestValidator.REASON_BODY_TOO_LONG, json.getString("reason"))
    }

    @Test
    fun `an SMS list request returns conversations and messages`() {
        sms.messages = listOf(
            SmsMessageSummary(1, 10, "+15551234567", "hi", 100, incoming = true, read = false)
        )
        sms.conversations = SmsConversation.group(sms.messages)

        assertTrue(handler().handle(frame(ProtocolFrameType.SMS_LIST_REQUEST)))

        val (type, json) = sent.single()
        assertEquals(ProtocolFrameType.SMS_LIST, type)
        assertEquals(1, json.getJSONArray("conversations").length())
        assertEquals(1, json.getJSONArray("messages").length())
        assertEquals(
            10L,
            json.getJSONArray("conversations").getJSONObject(0).getLong("threadId")
        )
    }

    @Test
    fun `non-gateway frames are not consumed`() {
        assertFalse(handler().handle(frame(ProtocolFrameType.HEARTBEAT_PING)))
        assertFalse(handler().handle(frame(ProtocolFrameType.CONTROL_MESSAGE)))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `malformed metadata does not throw`() {
        // A hostile or buggy peer must not be able to crash the gateway with bad
        // JSON; the request simply proceeds with no fields.
        assertTrue(handler().handle(frame(ProtocolFrameType.CALL_REQUEST, "{not json at all")))
        assertEquals(1, gateway.placeCallCount)
        assertEquals("", gateway.lastNumber)
    }

    @Test
    fun `an authenticated peer receives unsolicited call events`() {
        handler().sendCallEvent(
            CallStateTracker.CallEvent("call-1", CallStateTracker.State.RINGING)
        )
        val (type, json) = sent.single()
        assertEquals(ProtocolFrameType.CALL_STATE, type)
        assertEquals(CallStateTracker.State.RINGING, json.getString("state"))
        assertFalse("a normal event carries no failure reason", json.has("reason"))
    }
}

package com.jingcjie.wifi_direct_cable.client

import com.jingcjie.wifi_direct_cable.gateway.CallStateTracker
import com.jingcjie.wifi_direct_cable.gateway.GatewayCapabilities
import com.jingcjie.wifi_direct_cable.gateway.GatewayStatus
import com.jingcjie.wifi_direct_cable.gateway.TelephonyCodes
import com.jingcjie.wifi_direct_cable.protocol.ProtocolChannel
import com.jingcjie.wifi_direct_cable.protocol.ProtocolConstants
import com.jingcjie.wifi_direct_cable.protocol.ProtocolFrame
import com.jingcjie.wifi_direct_cable.protocol.ProtocolFrameType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTelephonyControllerTest {

    private val sent = mutableListOf<Pair<ProtocolFrameType, JSONObject>>()
    private val statuses = mutableListOf<GatewayStatus>()
    private val calls = mutableListOf<RemoteTelephonyController.CallSnapshot>()
    private val smsLists =
        mutableListOf<Pair<List<Map<String, Any?>>, List<Map<String, Any?>>>>()
    private val smsEvents = mutableListOf<Triple<String, String?, Int?>>()
    private var secure = true

    private val controller = RemoteTelephonyController(
        isSecure = { secure },
        sendFrame = { type, json -> sent.add(type to json) },
        listener = object : RemoteTelephonyController.Listener {
            override fun onGatewayStatus(status: GatewayStatus) {
                statuses.add(status)
            }

            override fun onCallState(snapshot: RemoteTelephonyController.CallSnapshot) {
                calls.add(snapshot)
            }

            override fun onSmsList(
                conversations: List<Map<String, Any?>>,
                messages: List<Map<String, Any?>>
            ) {
                smsLists.add(conversations to messages)
            }

            override fun onSmsEvent(
                requestId: String?,
                status: String,
                reason: String?,
                segments: Int?
            ) {
                smsEvents.add(Triple(status, reason, segments))
            }
        }
    )

    private fun frame(type: ProtocolFrameType, metadata: JSONObject) = ProtocolFrame(
        type = type,
        channel = ProtocolChannel.CONTROL,
        metadataJson = metadata.toString()
    )

    /** Feeds in a gateway status advertising [capabilities]. */
    private fun givenGateway(
        vararg capabilities: String,
        unavailable: Map<String, String> = emptyMap()
    ) {
        controller.handle(
            frame(
                ProtocolFrameType.GATEWAY_STATUS,
                GatewayStatus(
                    simState = TelephonyCodes.SIM_READY,
                    callState = TelephonyCodes.CALL_IDLE,
                    capabilities = capabilities.toList(),
                    unavailableReasons = unavailable
                ).toJson()
            )
        )
    }

    private fun givenDialCapableGateway() =
        givenGateway(ProtocolConstants.CAPABILITY_TELEPHONY_DIAL, ProtocolConstants.CAPABILITY_TELEPHONY_ANSWER)

    private fun lastRefusal() = calls.last().also {
        assertEquals(CallStateTracker.State.FAILED, it.state)
    }

    // -------------------------------------------------------------- inbound --

    @Test
    fun `a gateway status is parsed and retained`() {
        givenGateway(ProtocolConstants.CAPABILITY_TELEPHONY_DIAL)

        assertEquals(1, statuses.size)
        assertEquals(TelephonyCodes.SIM_READY, controller.lastStatus()?.simState)
        assertTrue(controller.supports(ProtocolConstants.CAPABILITY_TELEPHONY_DIAL))
    }

    @Test
    fun `a call state event is parsed and tracked`() {
        controller.handle(
            frame(
                ProtocolFrameType.CALL_STATE,
                JSONObject()
                    .put("callId", "call-1")
                    .put("state", CallStateTracker.State.ACTIVE)
                    .put("outgoing", true)
                    .put("answerConfirmed", false)
            )
        )

        assertEquals("call-1", controller.activeCall()?.callId)
        assertEquals(CallStateTracker.State.ACTIVE, calls.single().state)
    }

    @Test
    fun `a terminal call state clears the active call`() {
        controller.handle(
            frame(
                ProtocolFrameType.CALL_STATE,
                JSONObject().put("callId", "call-1").put("state", CallStateTracker.State.ACTIVE)
            )
        )
        assertNotNull(controller.activeCall())

        controller.handle(
            frame(
                ProtocolFrameType.CALL_STATE,
                JSONObject()
                    .put("callId", "call-1")
                    .put("state", CallStateTracker.State.ENDED)
                    .put("durationMs", 30_000L)
            )
        )

        assertNull(controller.activeCall())
        assertEquals(30_000L, calls.last().durationMs)
    }

    @Test
    fun `an outgoing call never reports a confirmed answer`() {
        // Mirrors the gateway's honesty: the UI must show time since dialling,
        // not talk time, because Android cannot report the callee answering.
        controller.handle(
            frame(
                ProtocolFrameType.CALL_STATE,
                JSONObject()
                    .put("callId", "call-1")
                    .put("state", CallStateTracker.State.ACTIVE)
                    .put("outgoing", true)
                    .put("answerConfirmed", false)
            )
        )
        assertFalse(calls.single().answerConfirmed)
        assertTrue(calls.single().outgoing)
    }

    @Test
    fun `unrelated frames are not consumed`() {
        assertFalse(controller.handle(frame(ProtocolFrameType.HEARTBEAT_PING, JSONObject())))
    }

    @Test
    fun `malformed metadata does not throw`() {
        val bad = ProtocolFrame(
            type = ProtocolFrameType.CALL_STATE,
            channel = ProtocolChannel.CONTROL,
            metadataJson = "{broken"
        )
        assertTrue(controller.handle(bad))
        assertEquals(CallStateTracker.State.FAILED, calls.single().state)
    }

    // ------------------------------------------------------ local refusals --
    //
    // These fail before anything goes on the wire. The gateway rejects them too
    // and remains the security boundary; refusing here just tells the user the
    // truth immediately instead of after a round trip.

    @Test
    fun `dialling is refused when the session is not authenticated`() {
        secure = false
        givenDialCapableGateway()

        assertNull(controller.placeCall("5551234567"))
        assertTrue("nothing may go on the wire", sent.isEmpty())
        assertEquals(RemoteTelephonyController.GATE_NOT_AUTHENTICATED, lastRefusal().reason)
    }

    @Test
    fun `dialling is refused before the gateway has reported its status`() {
        // Nothing is assumed about a gateway we have not heard from.
        assertNull(controller.placeCall("5551234567"))
        assertTrue(sent.isEmpty())
        assertEquals(RemoteTelephonyController.GATE_NO_STATUS, lastRefusal().reason)
    }

    @Test
    fun `dialling is refused when the gateway cannot dial, with its own reason`() {
        givenGateway(
            ProtocolConstants.CAPABILITY_GATEWAY_STATUS,
            unavailable = mapOf(
                ProtocolConstants.CAPABILITY_TELEPHONY_DIAL to GatewayCapabilities.REASON_NO_SIM
            )
        )

        assertNull(controller.placeCall("5551234567"))
        assertTrue(sent.isEmpty())
        // The reason surfaced is the gateway's, not a generic one.
        assertEquals(GatewayCapabilities.REASON_NO_SIM, lastRefusal().reason)
    }

    @Test
    fun `an MMI code is refused locally and never reaches the wire`() {
        givenDialCapableGateway()

        assertNull(controller.placeCall("**21*5551234567#"))
        assertTrue("a forwarding code must not be transmitted", sent.isEmpty())
        assertEquals(CallStateTracker.REASON_INVALID_NUMBER, lastRefusal().reason)
    }

    @Test
    fun `a second call is refused while one is active`() {
        givenDialCapableGateway()
        controller.handle(
            frame(
                ProtocolFrameType.CALL_STATE,
                JSONObject().put("callId", "call-1").put("state", CallStateTracker.State.ACTIVE)
            )
        )

        assertNull(controller.placeCall("5551234567"))
        assertEquals(CallStateTracker.REASON_BUSY, lastRefusal().reason)
    }

    @Test
    fun `answering is refused when the gateway cannot answer`() {
        givenGateway(ProtocolConstants.CAPABILITY_TELEPHONY_DIAL)

        assertFalse(controller.answerCall())
        assertTrue(sent.isEmpty())
    }

    // -------------------------------------------------------------- sending --

    @Test
    fun `a valid call request is sent with a normalised number`() {
        givenDialCapableGateway()

        val requestId = controller.placeCall("+1 (555) 123-4567")

        assertNotNull(requestId)
        val (type, json) = sent.single()
        assertEquals(ProtocolFrameType.CALL_REQUEST, type)
        assertEquals("+15551234567", json.getString("number"))
        assertEquals(requestId, json.getString("requestId"))
    }

    @Test
    fun `answer reject and hangup are sent when supported`() {
        givenDialCapableGateway()

        assertTrue(controller.answerCall())
        assertTrue(controller.rejectCall())
        assertTrue(controller.hangUp())

        assertEquals(
            listOf(
                ProtocolFrameType.CALL_ANSWER,
                ProtocolFrameType.CALL_REJECT,
                ProtocolFrameType.CALL_HANGUP
            ),
            sent.map { it.first }
        )
    }

    @Test
    fun `a status request is only sent on an authenticated session`() {
        secure = false
        assertFalse(controller.requestStatus())
        assertTrue(sent.isEmpty())

        secure = true
        assertTrue(controller.requestStatus())
        assertEquals(ProtocolFrameType.GATEWAY_STATUS_REQUEST, sent.single().first)
    }

    // ------------------------------------------------------------------ SMS --

    @Test
    fun `an SMS list is parsed and forwarded`() {
        controller.handle(
            frame(
                ProtocolFrameType.SMS_LIST,
                JSONObject()
                    .put(
                        "conversations",
                        org.json.JSONArray().put(JSONObject().put("threadId", 7).put("snippet", "hi"))
                    )
                    .put("messages", org.json.JSONArray().put(JSONObject().put("id", 1)))
            )
        )

        val (conversations, messages) = smsLists.single()
        assertEquals(1, conversations.size)
        assertEquals(7, conversations.first()["threadId"])
        assertEquals(1, messages.size)
    }

    @Test
    fun `an SMS event is parsed and forwarded`() {
        controller.handle(
            frame(
                ProtocolFrameType.SMS_EVENT,
                JSONObject().put("status", "sent").put("segments", 3)
            )
        )

        val (status, reason, segments) = smsEvents.single()
        assertEquals("sent", status)
        assertNull(reason)
        assertEquals(3, segments)
    }

    @Test
    fun `sending SMS is refused when the gateway cannot send`() {
        givenGateway(
            ProtocolConstants.CAPABILITY_GATEWAY_STATUS,
            unavailable = mapOf(
                ProtocolConstants.CAPABILITY_SMS_SEND to GatewayCapabilities.REASON_PERMISSION
            )
        )

        assertNull(controller.sendSms("+15551234567", "hi"))
        assertTrue(sent.isEmpty())
        assertEquals(GatewayCapabilities.REASON_PERMISSION, smsEvents.single().second)
    }

    @Test
    fun `an SMS to an MMI code never reaches the wire`() {
        givenGateway(ProtocolConstants.CAPABILITY_SMS_SEND)

        assertNull(controller.sendSms("**21*5551234567#", "hi"))
        assertTrue(sent.isEmpty())
        assertEquals(CallStateTracker.REASON_INVALID_NUMBER, smsEvents.single().second)
    }

    @Test
    fun `an empty SMS body is refused locally`() {
        givenGateway(ProtocolConstants.CAPABILITY_SMS_SEND)

        assertNull(controller.sendSms("+15551234567", ""))
        assertTrue(sent.isEmpty())
        assertEquals("empty_body", smsEvents.single().second)
    }

    @Test
    fun `a valid SMS is sent with a request id`() {
        givenGateway(ProtocolConstants.CAPABILITY_SMS_SEND)

        val requestId = controller.sendSms("+15551234567", "hello")

        assertNotNull(requestId)
        val (type, json) = sent.single()
        assertEquals(ProtocolFrameType.SMS_SEND_REQUEST, type)
        assertEquals(requestId, json.getString("requestId"))
        assertEquals("hello", json.getString("body"))
    }

    @Test
    fun `an SMS list is not requested when the gateway cannot read SMS`() {
        // The common case: Android restricts reading SMS to the default handler.
        givenGateway(ProtocolConstants.CAPABILITY_SMS_SEND)

        assertFalse(controller.requestSmsList())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `an SMS list is requested when the gateway can read SMS`() {
        givenGateway(ProtocolConstants.CAPABILITY_SMS_READ)

        assertTrue(controller.requestSmsList(50))
        val (type, json) = sent.single()
        assertEquals(ProtocolFrameType.SMS_LIST_REQUEST, type)
        assertEquals(50, json.getInt("limit"))
    }

    @Test
    fun `reset clears stale gateway state`() {
        givenDialCapableGateway()
        controller.handle(
            frame(
                ProtocolFrameType.CALL_STATE,
                JSONObject().put("callId", "call-1").put("state", CallStateTracker.State.ACTIVE)
            )
        )

        controller.reset()

        // After a dropped session nothing about the old gateway may be shown, and
        // capabilities must not be assumed to carry over.
        assertNull(controller.lastStatus())
        assertNull(controller.activeCall())
        assertFalse(controller.supports(ProtocolConstants.CAPABILITY_TELEPHONY_DIAL))
    }
}

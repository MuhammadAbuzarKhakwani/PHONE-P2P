package com.jingcjie.wifi_direct_cable.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateTrackerTest {

    private var now = 1_000L
    private val tracker = CallStateTracker { now }
    private var incomingIds = 0

    private fun nextIncomingId(): String = "incoming-${++incomingIds}"

    private fun state(name: String) = tracker.onTelephonyState(name, ::nextIncomingId)

    // ------------------------------------------------------------- outgoing --

    @Test
    fun `an outgoing call reports dialing immediately`() {
        val event = tracker.onOutgoingRequested("call-1")

        assertEquals(CallStateTracker.State.DIALING, event.state)
        assertEquals("call-1", event.callId)
        assertTrue(event.outgoing)
        assertTrue(tracker.isTracking())
    }

    /**
     * The honest-reporting case.
     *
     * On an outgoing call the radio goes off-hook while the far end is still
     * ringing. Android gives an unprivileged app no way to see the moment the
     * callee answers, so `answerConfirmed` must stay false — otherwise the client
     * would start a talk-time counter that is simply wrong.
     */
    @Test
    fun `an outgoing call never claims the answer was confirmed`() {
        tracker.onOutgoingRequested("call-1")
        val active = state(TelephonyCodes.CALL_OFFHOOK)!!

        assertEquals(CallStateTracker.State.ACTIVE, active.state)
        assertFalse(
            "Android cannot tell an unprivileged app that the callee answered",
            active.answerConfirmed
        )
        assertTrue(active.outgoing)
    }

    @Test
    fun `an outgoing call ends with a duration`() {
        tracker.onOutgoingRequested("call-1")
        state(TelephonyCodes.CALL_OFFHOOK)

        now += 42_000L
        val ended = state(TelephonyCodes.CALL_IDLE)!!

        assertEquals(CallStateTracker.State.ENDED, ended.state)
        assertEquals("call-1", ended.callId)
        assertEquals(42_000L, ended.durationMs)
        assertFalse(tracker.isTracking())
    }

    @Test
    fun `a call rejected before dialling does not become active`() {
        val failure = tracker.onFailed(CallStateTracker.REASON_INVALID_NUMBER)

        assertEquals(CallStateTracker.State.FAILED, failure.state)
        assertEquals(CallStateTracker.REASON_INVALID_NUMBER, failure.reason)
        assertFalse(tracker.isTracking())
    }

    // ------------------------------------------------------------- incoming --

    @Test
    fun `an incoming call is tracked with its own id`() {
        val ringing = state(TelephonyCodes.CALL_RINGING)!!

        assertEquals(CallStateTracker.State.RINGING, ringing.state)
        assertEquals("incoming-1", ringing.callId)
        assertFalse(ringing.outgoing)
    }

    @Test
    fun `an answered incoming call is confirmed`() {
        state(TelephonyCodes.CALL_RINGING)
        val active = state(TelephonyCodes.CALL_OFFHOOK)!!

        assertEquals(CallStateTracker.State.ACTIVE, active.state)
        // RINGING -> OFFHOOK is the one transition that genuinely proves the call
        // connected, so here the flag is earned.
        assertTrue(active.answerConfirmed)
        assertEquals("incoming-1", active.callId)
    }

    @Test
    fun `a missed incoming call ends without going active`() {
        state(TelephonyCodes.CALL_RINGING)
        now += 15_000L
        val ended = state(TelephonyCodes.CALL_IDLE)!!

        assertEquals(CallStateTracker.State.ENDED, ended.state)
        assertEquals(15_000L, ended.durationMs)
    }

    @Test
    fun `a call started on the gateway itself is still reported`() {
        // The user dialled on Phone 1 directly. The client needs to know the SIM
        // is busy, so this is reported rather than ignored.
        val active = state(TelephonyCodes.CALL_OFFHOOK)!!

        assertEquals(CallStateTracker.State.ACTIVE, active.state)
        assertFalse(active.outgoing)
        assertFalse("no RINGING was observed, so nothing is confirmed", active.answerConfirmed)
        assertNotNull(tracker.currentCallId())
    }

    // ------------------------------------------------------------ transitions --

    @Test
    fun `a repeated state emits nothing`() {
        state(TelephonyCodes.CALL_RINGING)
        assertNull("a duplicate state change must not re-notify", state(TelephonyCodes.CALL_RINGING))
    }

    @Test
    fun `going idle while already idle emits nothing`() {
        assertNull(state(TelephonyCodes.CALL_IDLE))
    }

    @Test
    fun `an unknown state is ignored`() {
        assertNull(state(TelephonyCodes.CALL_UNKNOWN))
    }

    @Test
    fun `a second call after the first ended gets a fresh id`() {
        tracker.onOutgoingRequested("call-1")
        state(TelephonyCodes.CALL_OFFHOOK)
        state(TelephonyCodes.CALL_IDLE)

        val ringing = state(TelephonyCodes.CALL_RINGING)!!
        assertEquals("incoming-1", ringing.callId)
        assertTrue(tracker.isTracking())
    }

    @Test
    fun `duration is zero before a call starts`() {
        assertEquals(0L, tracker.durationMs())
    }

    // ---------------------------------------------------------------- json --

    @Test
    fun `the event serialises the fields the client needs`() {
        tracker.onOutgoingRequested("call-1")
        state(TelephonyCodes.CALL_OFFHOOK)
        now += 5_000L
        val json = state(TelephonyCodes.CALL_IDLE)!!.toJson()

        assertEquals("call-1", json.getString("callId"))
        assertEquals(CallStateTracker.State.ENDED, json.getString("state"))
        assertEquals(5_000L, json.getLong("durationMs"))
        assertTrue(json.getBoolean("outgoing"))
        assertFalse(json.getBoolean("answerConfirmed"))
    }

    @Test
    fun `a failure event carries its reason and omits duration`() {
        val json = tracker.onFailed(CallStateTracker.REASON_PERMISSION_DENIED).toJson()

        assertEquals(CallStateTracker.State.FAILED, json.getString("state"))
        assertEquals(CallStateTracker.REASON_PERMISSION_DENIED, json.getString("reason"))
        assertFalse(json.has("durationMs"))
    }
}

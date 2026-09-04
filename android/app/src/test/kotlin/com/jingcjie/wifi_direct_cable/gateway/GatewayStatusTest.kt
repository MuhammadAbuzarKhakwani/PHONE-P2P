package com.jingcjie.wifi_direct_cable.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayStatusTest {

    @Test
    fun `unavailable fields are omitted rather than defaulted`() {
        // The core contract. Defaulting signalLevel to 0 or carrier to "Unknown"
        // would put a value on the client's screen that looks like a reading but
        // is not one — a user cannot tell a real zero-bar signal from a value the
        // platform never returned.
        val status = GatewayStatus(
            simState = TelephonyCodes.SIM_READY,
            callState = TelephonyCodes.CALL_IDLE
        )
        val json = status.toJson()

        assertFalse(json.has(GatewayStatus.FIELD_CARRIER))
        assertFalse(json.has(GatewayStatus.FIELD_SIGNAL_LEVEL))
        assertFalse(json.has(GatewayStatus.FIELD_NETWORK_TYPE))
        assertFalse(json.has(GatewayStatus.FIELD_MOBILE_DATA))
        assertFalse(json.has(GatewayStatus.FIELD_BATTERY))
        assertFalse(json.has(GatewayStatus.FIELD_CHARGING))

        // ...but the always-present fields are always present.
        assertTrue(json.has(GatewayStatus.FIELD_SIM_STATE))
        assertTrue(json.has(GatewayStatus.FIELD_CALL_STATE))
    }

    @Test
    fun `a zero signal level survives the round trip and is not confused with absent`() {
        val zeroBars = GatewayStatus(
            simState = TelephonyCodes.SIM_READY,
            callState = TelephonyCodes.CALL_IDLE,
            signalLevel = 0
        )
        val json = zeroBars.toJson()

        assertTrue("a genuine zero reading must be sent", json.has(GatewayStatus.FIELD_SIGNAL_LEVEL))
        assertEquals(0, GatewayStatus.fromJson(json).signalLevel)

        // Whereas an unavailable reading decodes back to null, not 0.
        val unavailable = GatewayStatus(
            simState = TelephonyCodes.SIM_READY,
            callState = TelephonyCodes.CALL_IDLE
        )
        assertNull(GatewayStatus.fromJson(unavailable.toJson()).signalLevel)
    }

    @Test
    fun `a full status round trips`() {
        val original = GatewayStatus(
            simState = TelephonyCodes.SIM_READY,
            callState = TelephonyCodes.CALL_OFFHOOK,
            carrier = "Example Mobile",
            networkType = "LTE",
            signalLevel = 3,
            mobileDataState = TelephonyCodes.DATA_CONNECTED,
            batteryPercent = 74,
            charging = true,
            gatewayVersion = "3.0.0",
            deviceName = "Pixel",
            capabilities = listOf("gateway", "telephony.dial"),
            unavailableReasons = mapOf("sms.read" to GatewayCapabilities.REASON_DEFAULT_SMS_HANDLER)
        )

        assertEquals(original, GatewayStatus.fromJson(original.toJson()))
    }

    @Test
    fun `charging false is preserved and not treated as absent`() {
        val notCharging = GatewayStatus(
            simState = TelephonyCodes.SIM_READY,
            callState = TelephonyCodes.CALL_IDLE,
            charging = false
        )
        assertEquals(false, GatewayStatus.fromJson(notCharging.toJson()).charging)
    }

    @Test
    fun `hasCapability reflects the advertised list`() {
        val status = GatewayStatus(
            simState = TelephonyCodes.SIM_READY,
            callState = TelephonyCodes.CALL_IDLE,
            capabilities = listOf("telephony.dial")
        )
        assertTrue(status.hasCapability("telephony.dial"))
        assertFalse(status.hasCapability("telephony.audio.cellular"))
    }

    @Test
    fun `decoding an empty object yields unknown rather than throwing`() {
        val status = GatewayStatus.fromJson(org.json.JSONObject())
        assertEquals(TelephonyCodes.SIM_UNKNOWN, status.simState)
        assertEquals(TelephonyCodes.CALL_UNKNOWN, status.callState)
        assertNull(status.carrier)
        assertTrue(status.capabilities.isEmpty())
    }

    @Test
    fun `simUsable is true only for a ready SIM`() {
        assertTrue(TelephonyCodes.simUsable(TelephonyCodes.SIM_READY))
        assertFalse(TelephonyCodes.simUsable(TelephonyCodes.SIM_ABSENT))
        assertFalse(TelephonyCodes.simUsable(TelephonyCodes.SIM_LOCKED))
        assertFalse(TelephonyCodes.simUsable(TelephonyCodes.SIM_UNKNOWN))
    }

    @Test
    fun `an unknown network type reports nothing rather than guessing`() {
        // Future radio technologies must not be silently bucketed as 2G.
        assertNull(TelephonyCodes.networkGeneration(Int.MAX_VALUE))
        assertNull(TelephonyCodes.networkGeneration(0)) // NETWORK_TYPE_UNKNOWN
    }
}

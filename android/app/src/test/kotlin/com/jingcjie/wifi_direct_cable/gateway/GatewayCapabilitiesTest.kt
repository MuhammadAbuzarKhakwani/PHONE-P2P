package com.jingcjie.wifi_direct_cable.gateway

import com.jingcjie.wifi_direct_cable.protocol.ProtocolConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayCapabilitiesTest {

    /** A fully-permitted gateway on a phone with a working SIM. */
    private fun fullyCapable() = GatewayEnvironment(
        hasTelephonyFeature = true,
        simUsable = true,
        readPhoneStateGranted = true,
        callPhoneGranted = true,
        answerPhoneCallsGranted = true,
        sendSmsGranted = true,
        readSmsGranted = true,
        isDefaultSmsHandler = true,
        apiLevel = 33
    )

    private fun capabilitiesOf(environment: GatewayEnvironment) =
        GatewayCapabilities.compute(environment).capabilities

    private fun reasonFor(environment: GatewayEnvironment, capability: String) =
        GatewayCapabilities.compute(environment).unavailableReasons[capability]

    @Test
    fun `a fully permitted gateway advertises every supported capability`() {
        val capabilities = capabilitiesOf(fullyCapable())

        assertTrue(capabilities.contains(ProtocolConstants.CAPABILITY_GATEWAY))
        assertTrue(capabilities.contains(ProtocolConstants.CAPABILITY_GATEWAY_STATUS))
        assertTrue(capabilities.contains(ProtocolConstants.CAPABILITY_TELEPHONY_STATE))
        assertTrue(capabilities.contains(ProtocolConstants.CAPABILITY_TELEPHONY_DIAL))
        assertTrue(capabilities.contains(ProtocolConstants.CAPABILITY_TELEPHONY_ANSWER))
        assertTrue(capabilities.contains(ProtocolConstants.CAPABILITY_SMS_SEND))
        assertTrue(capabilities.contains(ProtocolConstants.CAPABILITY_SMS_READ))
    }

    /**
     * The single most important assertion in the gateway.
     *
     * Routing live cellular call audio is not possible for an unprivileged app on
     * any Android version. If this ever starts passing as "advertised", the app is
     * claiming a capability it cannot deliver — which is exactly what the brief
     * forbids.
     */
    @Test
    fun `cellular call audio is never advertised, even when everything is granted`() {
        val result = GatewayCapabilities.compute(fullyCapable())

        assertFalse(
            "the app must never claim it can route cellular call audio",
            result.capabilities.contains(ProtocolConstants.CAPABILITY_CELLULAR_CALL_AUDIO)
        )
        assertEquals(
            GatewayCapabilities.REASON_PRIVILEGED,
            result.unavailableReasons[ProtocolConstants.CAPABILITY_CELLULAR_CALL_AUDIO]
        )
    }

    @Test
    fun `cellular call audio is reported unavailable on a device with no telephony`() {
        val result = GatewayCapabilities.compute(fullyCapable().copy(hasTelephonyFeature = false))
        assertEquals(
            GatewayCapabilities.REASON_PRIVILEGED,
            result.unavailableReasons[ProtocolConstants.CAPABILITY_CELLULAR_CALL_AUDIO]
        )
    }

    @Test
    fun `a device with no telephony hardware advertises nothing`() {
        val result = GatewayCapabilities.compute(fullyCapable().copy(hasTelephonyFeature = false))

        assertTrue(result.capabilities.isEmpty())
        assertEquals(
            GatewayCapabilities.REASON_NO_TELEPHONY,
            result.unavailableReasons[ProtocolConstants.CAPABILITY_GATEWAY]
        )
    }

    @Test
    fun `dialling needs both the permission and a usable SIM`() {
        val noPermission = fullyCapable().copy(callPhoneGranted = false)
        assertFalse(capabilitiesOf(noPermission).contains(ProtocolConstants.CAPABILITY_TELEPHONY_DIAL))
        assertEquals(
            GatewayCapabilities.REASON_PERMISSION,
            reasonFor(noPermission, ProtocolConstants.CAPABILITY_TELEPHONY_DIAL)
        )

        // Permission granted but no SIM: offering a dial button that always fails
        // would be worse than saying it is unavailable.
        val noSim = fullyCapable().copy(simUsable = false)
        assertFalse(capabilitiesOf(noSim).contains(ProtocolConstants.CAPABILITY_TELEPHONY_DIAL))
        assertEquals(
            GatewayCapabilities.REASON_NO_SIM,
            reasonFor(noSim, ProtocolConstants.CAPABILITY_TELEPHONY_DIAL)
        )
    }

    @Test
    fun `call state needs READ_PHONE_STATE`() {
        val environment = fullyCapable().copy(readPhoneStateGranted = false)
        assertFalse(capabilitiesOf(environment).contains(ProtocolConstants.CAPABILITY_TELEPHONY_STATE))
        assertEquals(
            GatewayCapabilities.REASON_PERMISSION,
            reasonFor(environment, ProtocolConstants.CAPABILITY_TELEPHONY_STATE)
        )
    }

    @Test
    fun `answering needs the permission and a recent enough Android`() {
        val noPermission = fullyCapable().copy(answerPhoneCallsGranted = false)
        assertEquals(
            GatewayCapabilities.REASON_PERMISSION,
            reasonFor(noPermission, ProtocolConstants.CAPABILITY_TELEPHONY_ANSWER)
        )

        val oldApi = fullyCapable().copy(apiLevel = 27)
        assertEquals(
            GatewayCapabilities.REASON_API_LEVEL,
            reasonFor(oldApi, ProtocolConstants.CAPABILITY_TELEPHONY_ANSWER)
        )

        assertTrue(
            capabilitiesOf(fullyCapable().copy(apiLevel = GatewayCapabilities.MIN_API_END_CALL))
                .contains(ProtocolConstants.CAPABILITY_TELEPHONY_ANSWER)
        )
    }

    @Test
    fun `reading SMS requires the default handler role, not just the permission`() {
        // Google Play restricts READ_SMS to the default SMS app, so holding the
        // permission alone is not enough in practice.
        val notDefault = fullyCapable().copy(isDefaultSmsHandler = false)
        assertFalse(capabilitiesOf(notDefault).contains(ProtocolConstants.CAPABILITY_SMS_READ))
        assertEquals(
            GatewayCapabilities.REASON_DEFAULT_SMS_HANDLER,
            reasonFor(notDefault, ProtocolConstants.CAPABILITY_SMS_READ)
        )
    }

    @Test
    fun `sending SMS needs the permission and a usable SIM`() {
        assertEquals(
            GatewayCapabilities.REASON_PERMISSION,
            reasonFor(fullyCapable().copy(sendSmsGranted = false), ProtocolConstants.CAPABILITY_SMS_SEND)
        )
        assertEquals(
            GatewayCapabilities.REASON_NO_SIM,
            reasonFor(fullyCapable().copy(simUsable = false), ProtocolConstants.CAPABILITY_SMS_SEND)
        )
    }

    @Test
    fun `a client phone with no SIM still reports the basic gateway capabilities`() {
        // Phone 2's own state: telephony hardware present, no SIM, nothing granted.
        val clientPhone = GatewayEnvironment(
            hasTelephonyFeature = true,
            simUsable = false,
            readPhoneStateGranted = false,
            callPhoneGranted = false,
            answerPhoneCallsGranted = false,
            sendSmsGranted = false,
            readSmsGranted = false,
            isDefaultSmsHandler = false,
            apiLevel = 33
        )
        val capabilities = capabilitiesOf(clientPhone)

        assertTrue(capabilities.contains(ProtocolConstants.CAPABILITY_GATEWAY_STATUS))
        assertFalse(capabilities.contains(ProtocolConstants.CAPABILITY_TELEPHONY_DIAL))
    }

    @Test
    fun `every reason has a human explanation`() {
        val allReasons = listOf(
            GatewayCapabilities.REASON_NO_TELEPHONY,
            GatewayCapabilities.REASON_PERMISSION,
            GatewayCapabilities.REASON_NO_SIM,
            GatewayCapabilities.REASON_DEFAULT_SMS_HANDLER,
            GatewayCapabilities.REASON_API_LEVEL,
            GatewayCapabilities.REASON_PRIVILEGED
        )
        for (reason in allReasons) {
            val explanation = GatewayCapabilities.explain(reason)
            assertNotNull(explanation)
            assertTrue("no explanation for $reason", explanation.isNotBlank())
            assertFalse(
                "explanation for $reason is the fallback text",
                explanation == "Unavailable on this device."
            )
        }
    }
}

package com.jingcjie.wifi_direct_cable.core.security

import com.jingcjie.wifi_direct_cable.core.model.DeviceId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives two [SecureHandshake] instances against each other in-process.
 *
 * The handshake is deliberately transport-free, so the whole exchange can be
 * verified here without sockets, Wi-Fi Direct, or two phones — which matters
 * because the phone-to-phone path cannot be exercised in CI at all.
 */
class SecureHandshakeTest {

    private val gatewayId = DeviceId.random()
    private val clientId = DeviceId.random()
    private val code = PairingCode("482913")

    @Test
    fun `both sides derive matching directional keys`() {
        val (initiator, responder) = pair(code, code)

        val initiatorKeys = initiator.sessionKeys(NOW)
        val responderKeys = responder.sessionKeys(NOW)

        // The initiator's transmit key must be the responder's receive key, and
        // vice versa. Getting this backwards is the classic way to end up with a
        // handshake that "succeeds" and then fails on the first real frame.
        assertArrayEquals(initiatorKeys.transmitKey(), responderKeys.receiveKey())
        assertArrayEquals(initiatorKeys.receiveKey(), responderKeys.transmitKey())

        // The two directions must not share a key.
        assertFalse(initiatorKeys.transmitKey().contentEquals(initiatorKeys.receiveKey()))
    }

    @Test
    fun `per-channel labels give each channel its own key`() {
        val (initiator, responder) = pair(code, code)
        val secret = initiator.transcriptHash() // any stable bytes work as IKM here

        val control = SessionKeys.derive(SecureRole.INITIATOR, secret, secret, SESSION, NOW, "control")
        val bulk = SessionKeys.derive(SecureRole.INITIATOR, secret, secret, SESSION, NOW, "bulk")
        val unlabelled = SessionKeys.derive(SecureRole.INITIATOR, secret, secret, SESSION, NOW)

        assertFalse(control.transmitKey().contentEquals(bulk.transmitKey()))
        assertFalse(control.transmitKey().contentEquals(unlabelled.transmitKey()))

        // ...and both peers still agree per channel.
        val responderControl =
            SessionKeys.derive(SecureRole.RESPONDER, secret, secret, SESSION, NOW, "control")
        assertArrayEquals(control.transmitKey(), responderControl.receiveKey())
        assertTrue(responder.transcriptHash().contentEquals(secret))
    }

    @Test
    fun `both sides agree on the transcript`() {
        val (initiator, responder) = pair(code, code)
        assertArrayEquals(initiator.transcriptHash(), responder.transcriptHash())
    }

    @Test
    fun `each side accepts the other's confirmation`() {
        val (initiator, responder) = pair(code, code)

        assertTrue(responder.verifyPeerConfirmation(initiator.localConfirmation()))
        assertTrue(initiator.verifyPeerConfirmation(responder.localConfirmation()))
    }

    @Test
    fun `a device does not accept its own confirmation`() {
        val (initiator, _) = pair(code, code)
        // Guards against a role mix-up that would let a reflected message pass.
        assertFalse(initiator.verifyPeerConfirmation(initiator.localConfirmation()))
    }

    @Test
    fun `a wrong pairing code fails confirmation`() {
        val (initiator, responder) = pair(code, PairingCode("000000"))

        // Both sides complete ECDH, so the failure surfaces at confirmation —
        // which is exactly where it should, and why callers must abort rather
        // than retry.
        assertFalse(responder.verifyPeerConfirmation(initiator.localConfirmation()))
        assertFalse(initiator.verifyPeerConfirmation(responder.localConfirmation()))
    }

    @Test
    fun `a wrong pairing code yields different session keys`() {
        val (initiator, responder) = pair(code, PairingCode("000000"))
        assertFalse(
            initiator.sessionKeys(NOW).transmitKey()
                .contentEquals(responder.sessionKeys(NOW).receiveKey())
        )
    }

    @Test
    fun `both sides display the same short authentication string`() {
        val (initiator, responder) = pair(code, code)

        val sas = initiator.shortAuthString()
        assertEquals(sas, responder.shortAuthString())
        assertEquals(6, sas.length)
        assertTrue(sas.all { it in '0'..'9' })
    }

    /**
     * The scenario the SAS exists for.
     *
     * The attacker here is assumed to already know the pairing code — which is
     * realistic, because a relayed handshake lets it brute-force a six-digit code
     * offline in milliseconds (see [PairingCode]). Even so, it runs two separate
     * ECDH exchanges, so the two ends compute different transcripts and therefore
     * different SAS values. A user comparing the two screens sees the mismatch.
     */
    @Test
    fun `a man in the middle produces mismatched short authentication strings`() {
        val attackerTowardGateway =
            SecureHandshake(SecureRole.RESPONDER, clientId, SESSION) // impersonating the client
        val attackerTowardClient =
            SecureHandshake(SecureRole.INITIATOR, gatewayId, SESSION) // impersonating the gateway

        val gateway = handshake(SecureRole.INITIATOR, gatewayId)
        val client = handshake(SecureRole.RESPONDER, clientId)

        exchange(gateway, attackerTowardGateway, code.toBytes(), code.toBytes())
        exchange(attackerTowardClient, client, code.toBytes(), code.toBytes())

        // Each leg is internally consistent — the attacker can pass the code check
        // on both sides...
        assertTrue(gateway.verifyPeerConfirmation(attackerTowardGateway.localConfirmation()))
        assertTrue(client.verifyPeerConfirmation(attackerTowardClient.localConfirmation()))

        // ...but it cannot make the two SAS values agree.
        assertNotEquals(gateway.shortAuthString(), client.shortAuthString())
    }

    @Test
    fun `resuming with the stored long term secret produces a working session`() {
        val (initiator, responder) = pair(code, code)
        val storedSecret = initiator.deriveLongTermSecret()
        assertArrayEquals(storedSecret, responder.deriveLongTermSecret())

        // A later reconnection uses the stored secret instead of the typed code,
        // with fresh ephemeral keys, so the session keys differ every time.
        val resumedInitiator = handshake(SecureRole.INITIATOR, gatewayId)
        val resumedResponder = handshake(SecureRole.RESPONDER, clientId)
        exchange(resumedInitiator, resumedResponder, storedSecret, storedSecret)

        assertTrue(resumedResponder.verifyPeerConfirmation(resumedInitiator.localConfirmation()))
        assertArrayEquals(
            resumedInitiator.sessionKeys(NOW).transmitKey(),
            resumedResponder.sessionKeys(NOW).receiveKey()
        )
        assertFalse(
            "ephemeral ECDH must give a fresh session key on every reconnect",
            initiator.sessionKeys(NOW).transmitKey()
                .contentEquals(resumedInitiator.sessionKeys(NOW).transmitKey())
        )
    }

    @Test
    fun `each handshake produces fresh keys`() {
        val first = pair(code, code).first.sessionKeys(NOW).transmitKey()
        val second = pair(code, code).first.sessionKeys(NOW).transmitKey()
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `peer device id is reported from the offer`() {
        val (initiator, responder) = pair(code, code)
        assertEquals(clientId, initiator.peerDeviceId())
        assertEquals(gatewayId, responder.peerDeviceId())
    }

    @Test
    fun `peer device id can be read before the offer is consumed`() {
        val initiator = handshake(SecureRole.INITIATOR, gatewayId)
        val responder = handshake(SecureRole.RESPONDER, clientId)

        // This is what lets the caller pick the right auth secret before
        // committing to key agreement.
        assertEquals(clientId, initiator.peerDeviceIdFrom(responder.buildOffer()))
    }

    @Test(expected = SecureHandshake.HandshakeException::class)
    fun `rejects a reflected public key`() {
        val initiator = handshake(SecureRole.INITIATOR, gatewayId)
        // An attacker with no key material replays our own offer back at us,
        // trying to drive both ends to an identical transcript.
        initiator.acceptOffer(initiator.buildOffer(), code.toBytes())
    }

    @Test(expected = SecureHandshake.HandshakeException::class)
    fun `rejects an offer with a malformed public key`() {
        val initiator = handshake(SecureRole.INITIATOR, gatewayId)
        val offer = handshake(SecureRole.RESPONDER, clientId).buildOffer()
        offer.put(SecureHandshake.FIELD_PUBLIC_KEY, "not-base64-at-all!!")
        initiator.acceptOffer(offer, code.toBytes())
    }

    @Test(expected = SecureHandshake.HandshakeException::class)
    fun `rejects an offer with no device id`() {
        val initiator = handshake(SecureRole.INITIATOR, gatewayId)
        val offer = handshake(SecureRole.RESPONDER, clientId).buildOffer()
        offer.remove(SecureHandshake.FIELD_DEVICE_ID)
        initiator.acceptOffer(offer, code.toBytes())
    }

    @Test(expected = SecureHandshake.HandshakeException::class)
    fun `rejects a nonce of the wrong length`() {
        val initiator = handshake(SecureRole.INITIATOR, gatewayId)
        val offer = handshake(SecureRole.RESPONDER, clientId).buildOffer()
        offer.put(SecureHandshake.FIELD_NONCE, SecureHandshake.encode(ByteArray(8)))
        initiator.acceptOffer(offer, code.toBytes())
    }

    @Test(expected = SecureHandshake.HandshakeException::class)
    fun `session keys are unavailable before the offer is accepted`() {
        handshake(SecureRole.INITIATOR, gatewayId).sessionKeys(NOW)
    }

    // ------------------------------------------------------------- helpers --

    private fun handshake(role: SecureRole, deviceId: DeviceId) =
        SecureHandshake(role = role, localDeviceId = deviceId, sessionId = SESSION)

    /** Completes a handshake between a gateway and a client, each using its own code. */
    private fun pair(
        gatewayCode: PairingCode,
        clientCode: PairingCode
    ): Pair<SecureHandshake, SecureHandshake> {
        val initiator = handshake(SecureRole.INITIATOR, gatewayId)
        val responder = handshake(SecureRole.RESPONDER, clientId)
        exchange(initiator, responder, gatewayCode.toBytes(), clientCode.toBytes())
        return initiator to responder
    }

    private fun exchange(
        initiator: SecureHandshake,
        responder: SecureHandshake,
        initiatorSecret: ByteArray,
        responderSecret: ByteArray
    ) {
        // Offers are built before either side needs a secret, which is exactly
        // how the negotiator sequences it on the wire.
        val initiatorOffer = initiator.buildOffer()
        val responderOffer = responder.buildOffer()
        responder.acceptOffer(initiatorOffer, responderSecret)
        initiator.acceptOffer(responderOffer, initiatorSecret)
    }

    private companion object {
        const val SESSION = "test-session"
        const val NOW = 1_700_000_000_000L
    }
}

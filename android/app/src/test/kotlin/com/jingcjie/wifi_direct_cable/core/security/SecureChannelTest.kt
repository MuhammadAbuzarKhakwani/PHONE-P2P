package com.jingcjie.wifi_direct_cable.core.security

import com.jingcjie.wifi_direct_cable.core.model.DeviceId
import com.jingcjie.wifi_direct_cable.protocol.ProtocolChannel
import com.jingcjie.wifi_direct_cable.protocol.ProtocolConstants
import com.jingcjie.wifi_direct_cable.protocol.ProtocolError
import com.jingcjie.wifi_direct_cable.protocol.ProtocolException
import com.jingcjie.wifi_direct_cable.protocol.ProtocolFrame
import com.jingcjie.wifi_direct_cable.protocol.ProtocolFrameType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SecureChannelTest {

    private val gatewayId = DeviceId.random()
    private val clientId = DeviceId.random()

    private var gatewayNow = NOW
    private var clientNow = NOW

    private val gateway = SecureChannel(gatewayId, clock = { gatewayNow })
    private val client = SecureChannel(clientId, clock = { clientNow })

    private fun activateBoth(
        gatewayExpects: DeviceId = clientId,
        clientExpects: DeviceId = gatewayId
    ) {
        val sharedSecret = CryptoPrimitives.randomBytes(64)
        val transcript = CryptoPrimitives.randomBytes(32)
        gateway.activate(
            SessionKeys.derive(SecureRole.INITIATOR, sharedSecret, transcript, SESSION, NOW),
            gatewayExpects
        )
        client.activate(
            SessionKeys.derive(SecureRole.RESPONDER, sharedSecret, transcript, SESSION, NOW),
            clientExpects
        )
    }

    private fun frame(metadata: String = """{"kind":"hello"}""") = ProtocolFrame(
        type = ProtocolFrameType.CONTROL_MESSAGE,
        channel = ProtocolChannel.CONTROL,
        metadataJson = metadata
    )

    /** Serialises one frame as [from] would put it on the wire. */
    private fun send(from: SecureChannel, frame: ProtocolFrame = frame()): ByteArray =
        ByteArrayOutputStream().use { out ->
            from.writeFrame(frame, out)
            out.toByteArray()
        }

    private fun receive(into: SecureChannel, bytes: ByteArray): ProtocolFrame? =
        into.readFrame(ByteArrayInputStream(bytes))

    // ------------------------------------------------------------- inactive --

    @Test
    fun `passes plaintext before activation so the handshake can run`() {
        assertFalse(gateway.isActive)
        val decoded = receive(client, send(gateway, frame("""{"kind":"pair.request"}""")))!!

        assertFalse(decoded.isEncrypted)
        assertEquals("""{"kind":"pair.request"}""", decoded.metadataJson)
        assertEquals(gatewayId, decoded.senderDeviceId)
    }

    @Test
    fun `stamps identity timestamp and sequence on every frame`() {
        val first = receive(client, send(gateway))!!
        val second = receive(client, send(gateway))!!

        assertEquals(gatewayId, first.senderDeviceId)
        assertEquals(NOW, first.timestampMs)
        assertEquals(0L, first.sequenceNumber)
        assertEquals(1L, second.sequenceNumber)
    }

    // --------------------------------------------------------------- active --

    @Test
    fun `encrypts once activated and decrypts on the peer`() {
        activateBoth()
        assertTrue(gateway.isActive)

        val decoded = receive(client, send(gateway, frame("""{"kind":"call.request"}""")))!!

        assertTrue(decoded.isEncrypted)
        assertEquals("""{"kind":"call.request"}""", decoded.metadataJson)
    }

    @Test
    fun `works in both directions`() {
        activateBoth()

        assertEquals("""{"kind":"a"}""", receive(client, send(gateway, frame("""{"kind":"a"}""")))!!.metadataJson)
        assertEquals("""{"kind":"b"}""", receive(gateway, send(client, frame("""{"kind":"b"}""")))!!.metadataJson)
    }

    @Test
    fun `sensitive metadata does not appear on the wire`() {
        activateBoth()
        val number = "+15551234567"
        val wire = send(gateway, frame("""{"kind":"call.request","number":"$number"}"""))

        assertFalse(String(wire, Charsets.ISO_8859_1).contains(number))
    }

    @Test
    fun `carries a long run of frames`() {
        activateBoth()
        repeat(2000) { index ->
            val decoded = receive(client, send(gateway, frame("""{"n":$index}""")))!!
            assertEquals("""{"n":$index}""", decoded.metadataJson)
        }
        assertEquals(2000L, client.framesReceived())
    }

    // ------------------------------------------------------------- rejection --

    @Test
    fun `rejects a replayed frame`() {
        activateBoth()
        val wire = send(gateway)

        assertTrue(receive(client, wire) != null)
        // Exactly the same bytes again: a recorded frame being re-injected.
        expectError(ProtocolError.REPLAYED_FRAME) { receive(client, wire) }
        assertEquals(1L, client.duplicatesDropped())
    }

    @Test
    fun `accepts frames that arrive out of order`() {
        activateBoth()
        val first = send(gateway)
        val second = send(gateway)
        val third = send(gateway)

        assertTrue(receive(client, third) != null)
        assertTrue(receive(client, first) != null)
        assertTrue(receive(client, second) != null)
        assertEquals(0L, client.duplicatesDropped())
    }

    @Test
    fun `rejects a frame from a device that is not the paired peer`() {
        // The client was paired with someone else entirely.
        activateBoth(clientExpects = DeviceId.random())

        expectError(ProtocolError.UNKNOWN_DEVICE) { receive(client, send(gateway)) }
        assertEquals(1L, client.framesRejected())
    }

    @Test
    fun `rejects a stale frame`() {
        activateBoth()
        gatewayNow = NOW - ProtocolConstants.MAX_FRAME_AGE_MS - 1000L
        val wire = send(gateway)

        expectError(ProtocolError.STALE_FRAME) { receive(client, wire) }
    }

    @Test
    fun `rejects a plaintext frame once keyed`() {
        // Only the client activates, so the gateway still emits plaintext. This is
        // the downgrade case: the receiver must refuse it rather than read it.
        val sharedSecret = CryptoPrimitives.randomBytes(64)
        val transcript = CryptoPrimitives.randomBytes(32)
        client.activate(
            SessionKeys.derive(SecureRole.RESPONDER, sharedSecret, transcript, SESSION, NOW),
            gatewayId
        )

        expectError(ProtocolError.ENCRYPTION_REQUIRED) { receive(client, send(gateway)) }
    }

    @Test
    fun `rejects a frame sealed with an unrelated key`() {
        activateBoth()
        // A third device with its own keys, impersonating the gateway.
        val attacker = SecureChannel(gatewayId, clock = { NOW })
        val otherSecret = CryptoPrimitives.randomBytes(64)
        attacker.activate(
            SessionKeys.derive(SecureRole.INITIATOR, otherSecret, CryptoPrimitives.randomBytes(32), SESSION, NOW),
            clientId
        )

        expectError(ProtocolError.AUTHENTICATION_FAILED) { receive(client, send(attacker)) }
    }

    // ------------------------------------------------------------ lifecycle --

    @Test
    fun `payload survives encryption unchanged`() {
        activateBoth()
        val payload = CryptoPrimitives.randomBytes(4096)
        val decoded = receive(
            client,
            send(gateway, ProtocolFrame(ProtocolFrameType.AUDIO_FRAME, ProtocolChannel.AUDIO, payload = payload))
        )!!
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `deactivate clears the active state`() {
        activateBoth()
        gateway.deactivate()
        assertFalse(gateway.isActive)
    }

    @Test(expected = IllegalStateException::class)
    fun `cannot activate twice`() {
        activateBoth()
        gateway.activate(
            SessionKeys.derive(
                SecureRole.INITIATOR,
                CryptoPrimitives.randomBytes(64),
                CryptoPrimitives.randomBytes(32),
                SESSION,
                NOW
            ),
            clientId
        )
    }

    @Test
    fun `stats snapshot never contains key material`() {
        activateBoth()
        receive(client, send(gateway))

        val snapshot = client.statsSnapshot()
        assertEquals(true, snapshot["secureActive"])
        assertEquals(1L, snapshot["framesReceived"])
        assertEquals(gatewayId.shortLabel(), snapshot["peerDeviceId"])
    }

    private fun expectError(expected: ProtocolError, block: () -> Unit) {
        try {
            block()
            fail("expected ProtocolException($expected)")
        } catch (exception: ProtocolException) {
            assertEquals(expected, exception.error)
        }
    }

    private companion object {
        const val SESSION = "test-session"
        const val NOW = 1_700_000_000_000L
    }
}

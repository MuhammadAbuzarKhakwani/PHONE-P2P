package com.jingcjie.wifi_direct_cable.protocol

import com.jingcjie.wifi_direct_cable.core.model.DeviceId
import com.jingcjie.wifi_direct_cable.core.security.CryptoPrimitives
import com.jingcjie.wifi_direct_cable.core.security.NonceGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.UUID

class SecureProtocolCodecTest {

    private val key = CryptoPrimitives.randomBytes(CryptoPrimitives.AES_KEY_BYTES)
    private val nonces = NonceGenerator()
    private val deviceId = DeviceId.random()

    private fun frame(
        type: ProtocolFrameType = ProtocolFrameType.CONTROL_MESSAGE,
        channel: ProtocolChannel = ProtocolChannel.CONTROL,
        metadata: String = """{"kind":"test"}""",
        payload: ByteArray = "payload-bytes".toByteArray()
    ) = ProtocolFrame(
        type = type,
        channel = channel,
        streamId = 7L,
        sequenceNumber = 42L,
        correlationId = UUID.randomUUID(),
        metadataJson = metadata,
        payload = payload,
        timestampMs = 1_700_000_000_000L,
        senderDeviceId = deviceId
    )

    private fun read(
        bytes: ByteArray,
        readKey: ByteArray? = null,
        requireEncryption: Boolean = false
    ) = SecureProtocolCodec.readFrame(ByteArrayInputStream(bytes), readKey, requireEncryption)

    // ------------------------------------------------------------ plaintext --

    @Test
    fun `plaintext round trip preserves every field`() {
        val original = frame()
        val decoded = read(SecureProtocolCodec.encode(original))!!

        assertEquals(original.type, decoded.type)
        assertEquals(original.channel, decoded.channel)
        assertEquals(original.streamId, decoded.streamId)
        assertEquals(original.sequenceNumber, decoded.sequenceNumber)
        assertEquals(original.correlationId, decoded.correlationId)
        assertEquals(original.metadataJson, decoded.metadataJson)
        assertArrayEquals(original.payload, decoded.payload)
        assertEquals(original.timestampMs, decoded.timestampMs)
        assertEquals(original.senderDeviceId, decoded.senderDeviceId)
        assertFalse(decoded.isEncrypted)
    }

    @Test
    fun `header is the declared v3 size`() {
        val encoded = SecureProtocolCodec.encode(
            frame(metadata = "", payload = ByteArray(0))
        )
        assertEquals(ProtocolConstants.HEADER_SIZE_V3, encoded.size)
    }

    @Test
    fun `empty metadata and payload round trip`() {
        val decoded = read(
            SecureProtocolCodec.encode(frame(metadata = "", payload = ByteArray(0)))
        )!!
        assertEquals("", decoded.metadataJson)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `end of stream returns null`() {
        assertNull(read(ByteArray(0)))
    }

    // ------------------------------------------------------------ encrypted --

    @Test
    fun `encrypted round trip preserves every field`() {
        val original = frame()
        val encoded = SecureProtocolCodec.encode(original, key, nonces.next())
        val decoded = read(encoded, key, requireEncryption = true)!!

        assertTrue(decoded.isEncrypted)
        assertEquals(original.metadataJson, decoded.metadataJson)
        assertArrayEquals(original.payload, decoded.payload)
        assertEquals(original.senderDeviceId, decoded.senderDeviceId)
        assertEquals(original.timestampMs, decoded.timestampMs)
    }

    @Test
    fun `ciphertext does not contain the plaintext`() {
        val secret = "the-account-number-is-12345"
        val encoded = SecureProtocolCodec.encode(
            frame(metadata = """{"secret":"$secret"}""", payload = secret.toByteArray()),
            key,
            nonces.next()
        )
        assertFalse(
            "plaintext leaked onto the wire",
            String(encoded, Charsets.ISO_8859_1).contains(secret)
        )
    }

    @Test
    fun `encrypted body carries the GCM tag overhead`() {
        val metadata = "{}"
        val payload = ByteArray(64)
        val encoded = SecureProtocolCodec.encode(
            frame(metadata = metadata, payload = payload),
            key,
            nonces.next()
        )
        assertEquals(
            ProtocolConstants.HEADER_SIZE_V3 + metadata.length + payload.size +
                CryptoPrimitives.GCM_TAG_BYTES,
            encoded.size
        )
    }

    @Test
    fun `a large payload round trips`() {
        val payload = CryptoPrimitives.randomBytes(256 * 1024)
        val decoded = read(
            SecureProtocolCodec.encode(frame(payload = payload), key, nonces.next()),
            key,
            requireEncryption = true
        )!!
        assertArrayEquals(payload, decoded.payload)
    }

    // ---------------------------------------------------------- tamper cases --

    @Test
    fun `tampering with the ciphertext fails authentication`() {
        val encoded = SecureProtocolCodec.encode(frame(), key, nonces.next())
        // Flip a bit in the body, then repair the CRC so the cheap check passes
        // and the GCM tag is the thing that has to catch it.
        val bodyStart = ProtocolConstants.HEADER_SIZE_V3
        encoded[bodyStart] = (encoded[bodyStart].toInt() xor 0x01).toByte()
        repairCrc(encoded)

        expectError(ProtocolError.AUTHENTICATION_FAILED) { read(encoded, key, true) }
    }

    @Test
    fun `tampering with the header fails authentication`() {
        val encoded = SecureProtocolCodec.encode(frame(), key, nonces.next())
        // Rewrite the frame type in the header. The header is GCM associated
        // data, so this must be detected even though the body is untouched.
        encoded[9] = ProtocolFrameType.CALL_HANGUP.id.toByte()

        expectError(ProtocolError.AUTHENTICATION_FAILED) { read(encoded, key, true) }
    }

    @Test
    fun `tampering with the sender device id fails authentication`() {
        val encoded = SecureProtocolCodec.encode(frame(), key, nonces.next())
        encoded[56] = (encoded[56].toInt() xor 0xff).toByte()

        expectError(ProtocolError.AUTHENTICATION_FAILED) { read(encoded, key, true) }
    }

    @Test
    fun `corruption is caught by the checksum`() {
        val encoded = SecureProtocolCodec.encode(frame(), key, nonces.next())
        val bodyStart = ProtocolConstants.HEADER_SIZE_V3
        encoded[bodyStart] = (encoded[bodyStart].toInt() xor 0x01).toByte()
        // CRC deliberately left stale, so the cheap check fires before any crypto.

        expectError(ProtocolError.CHECKSUM_MISMATCH) { read(encoded, key, true) }
    }

    @Test
    fun `the wrong key fails authentication`() {
        val encoded = SecureProtocolCodec.encode(frame(), key, nonces.next())
        val otherKey = CryptoPrimitives.randomBytes(CryptoPrimitives.AES_KEY_BYTES)

        expectError(ProtocolError.AUTHENTICATION_FAILED) { read(encoded, otherKey, true) }
    }

    // -------------------------------------------------------------- downgrade --

    @Test
    fun `a plaintext frame is refused on a keyed channel`() {
        // The downgrade attack: strip the encrypted flag and send plaintext,
        // hoping the receiver just reads it.
        val encoded = SecureProtocolCodec.encode(frame())

        expectError(ProtocolError.ENCRYPTION_REQUIRED) { read(encoded, key, true) }
    }

    @Test
    fun `an encrypted frame without a key is refused`() {
        val encoded = SecureProtocolCodec.encode(frame(), key, nonces.next())

        expectError(ProtocolError.KEY_UNAVAILABLE) { read(encoded, null, false) }
    }

    // ------------------------------------------------------------ validation --

    @Test
    fun `a v2 frame is refused by the v3 codec`() {
        // Padded past the 96-byte v3 header so the read reaches the version
        // check; a bare 56-byte v2 frame would fail earlier as a partial read,
        // which would test the wrong thing.
        val v2 = ProtocolCodec.encode(
            ProtocolFrame(
                type = ProtocolFrameType.HANDSHAKE_HELLO,
                channel = ProtocolChannel.CONTROL,
                payload = ByteArray(64)
            )
        )
        assertTrue(v2.size > ProtocolConstants.HEADER_SIZE_V3)
        expectError(ProtocolError.UNSUPPORTED_VERSION) { read(v2) }
    }

    @Test
    fun `bad magic is refused`() {
        val encoded = SecureProtocolCodec.encode(frame())
        encoded[0] = 0x00

        expectError(ProtocolError.MALFORMED_MAGIC) { read(encoded) }
    }

    @Test
    fun `a truncated frame is refused`() {
        val encoded = SecureProtocolCodec.encode(frame())
        expectError(ProtocolError.PARTIAL_READ) { read(encoded.copyOf(encoded.size - 4)) }
    }

    @Test
    fun `a truncated header is refused`() {
        val encoded = SecureProtocolCodec.encode(frame())
        expectError(ProtocolError.PARTIAL_READ) { read(encoded.copyOf(20)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encrypting without a nonce is rejected`() {
        SecureProtocolCodec.encode(frame(), key, null)
    }

    // ----------------------------------------------------------- freshness --

    @Test
    fun `freshness accepts a recent timestamp`() {
        val now = 1_700_000_000_000L
        assertTrue(SecureProtocolCodec.isFresh(now, now))
        assertTrue(SecureProtocolCodec.isFresh(now - 60_000L, now))
        // Clocks between two phones are never synchronised, so a peer running
        // slightly ahead must still be accepted.
        assertTrue(SecureProtocolCodec.isFresh(now + 60_000L, now))
    }

    @Test
    fun `freshness rejects an old or absent timestamp`() {
        val now = 1_700_000_000_000L
        assertFalse(SecureProtocolCodec.isFresh(now - ProtocolConstants.MAX_FRAME_AGE_MS - 1, now))
        assertFalse(SecureProtocolCodec.isFresh(0L, now))
    }

    // ------------------------------------------------------------- helpers --

    private fun expectError(expected: ProtocolError, block: () -> Unit) {
        try {
            block()
            fail("expected ProtocolException($expected)")
        } catch (exception: ProtocolException) {
            assertEquals(expected, exception.error)
        }
    }

    /** Recomputes the body CRC in place so a tamper test reaches the GCM check. */
    private fun repairCrc(encoded: ByteArray) {
        val body = encoded.copyOfRange(ProtocolConstants.HEADER_SIZE_V3, encoded.size)
        val crc = java.util.zip.CRC32().apply { update(body) }.value.toInt()
        encoded[84] = (crc ushr 24).toByte()
        encoded[85] = (crc ushr 16).toByte()
        encoded[86] = (crc ushr 8).toByte()
        encoded[87] = crc.toByte()
    }
}

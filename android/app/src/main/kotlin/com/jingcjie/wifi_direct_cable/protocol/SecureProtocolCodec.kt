package com.jingcjie.wifi_direct_cable.protocol

import com.jingcjie.wifi_direct_cable.core.model.DeviceId
import com.jingcjie.wifi_direct_cable.core.security.CryptoPrimitives
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32
import javax.crypto.AEADBadTagException

/**
 * Codec for the v3 authenticated framing.
 *
 * v2 ([ProtocolCodec]) is left completely untouched and still handles the legacy
 * plaintext wire format, so the shipped Windows companion app keeps working. v3
 * is a separate format negotiated at handshake.
 *
 * ## Header (96 bytes, big-endian)
 *
 * ```
 * off  size  field
 *   0     4  magic 0x57444342 "WDCB"
 *   4     2  version = 3
 *   6     2  headerSize = 96
 *   8     2  frameType
 *  10     2  flags            bit0 = FLAG_ENCRYPTED
 *  12     2  channel
 *  14     2  reserved
 *  16     8  streamId
 *  24     8  sequenceNumber
 *  32    16  correlationId    (message ID, UUID)
 *  48     8  timestampMs      (sender wall clock)
 *  56    16  senderDeviceId   (UUID)
 *  72    12  nonce            (AES-GCM IV; zero when not encrypted)
 *  84     4  bodyCrc32        (over the on-wire body)
 *  88     4  metadataLength   (plaintext length)
 *  92     4  payloadLength    (plaintext length)
 * ```
 *
 * ## Body
 *
 * - Not encrypted: `metadata || payload`, exactly `metadataLength + payloadLength`
 *   bytes.
 * - Encrypted: `AES-256-GCM(metadata || payload)`, which is
 *   `metadataLength + payloadLength + 16` bytes because the JCE appends the tag.
 *
 * ## Integrity
 *
 * Two independent mechanisms, doing different jobs:
 *
 * - `bodyCrc32` detects **corruption** and lets a damaged frame be rejected before
 *   any crypto work is attempted. It is not a security control — a CRC is trivially
 *   forgeable.
 * - The **GCM tag** provides the actual authentication, and covers the whole
 *   header as associated data, so an attacker cannot rewrite the frame type,
 *   channel, sequence number, or sender ID without the tag failing.
 *
 * The CRC field itself is excluded from the AAD (it is zeroed in the AAD copy),
 * because it is computed over the ciphertext and so cannot be known until after
 * sealing. Excluding it is safe: every byte it protects is already covered by the
 * GCM tag on an encrypted frame.
 */
object SecureProtocolCodec {

    private const val OFFSET_CRC = 84
    private const val OFFSET_NONCE = 72
    private val ZERO_NONCE = ByteArray(CryptoPrimitives.GCM_NONCE_BYTES)

    /**
     * Encodes [frame] to its wire bytes.
     *
     * @param key AES-256 key, or null to emit a plaintext v3 frame. Plaintext is
     *        only legitimate before the session has keys — that is, during the
     *        handshake and pairing exchange.
     * @param nonce unique nonce for [key]; required when [key] is non-null.
     *        Callers must source it from
     *        [com.jingcjie.wifi_direct_cable.core.security.NonceGenerator] and
     *        never reuse one.
     */
    fun encode(frame: ProtocolFrame, key: ByteArray? = null, nonce: ByteArray? = null): ByteArray {
        val metadataBytes = frame.metadataJson.toByteArray(Charsets.UTF_8)
        validateLengths(metadataBytes.size, frame.payload.size)

        val encrypting = key != null
        require(!encrypting || nonce != null) { "a nonce is required when encrypting" }

        val effectiveNonce = if (encrypting) nonce!! else ZERO_NONCE
        require(effectiveNonce.size == CryptoPrimitives.GCM_NONCE_BYTES) {
            "nonce must be ${CryptoPrimitives.GCM_NONCE_BYTES} bytes, was ${effectiveNonce.size}"
        }

        val flags = if (encrypting) {
            frame.flags or ProtocolConstants.FLAG_ENCRYPTED
        } else {
            frame.flags and ProtocolConstants.FLAG_ENCRYPTED.inv()
        }

        val header = buildHeader(
            frame = frame,
            flags = flags,
            nonce = effectiveNonce,
            metadataLength = metadataBytes.size,
            payloadLength = frame.payload.size,
            bodyCrc32 = 0
        )

        val body = if (encrypting) {
            val plaintext = ByteArray(metadataBytes.size + frame.payload.size)
            System.arraycopy(metadataBytes, 0, plaintext, 0, metadataBytes.size)
            System.arraycopy(frame.payload, 0, plaintext, metadataBytes.size, frame.payload.size)
            try {
                CryptoPrimitives.aeadSeal(
                    key = key!!,
                    nonce = effectiveNonce,
                    associatedData = associatedData(header),
                    plaintext = plaintext
                )
            } finally {
                CryptoPrimitives.wipe(plaintext)
            }
        } else {
            ByteArray(metadataBytes.size + frame.payload.size).also { combined ->
                System.arraycopy(metadataBytes, 0, combined, 0, metadataBytes.size)
                System.arraycopy(frame.payload, 0, combined, metadataBytes.size, frame.payload.size)
            }
        }

        writeInt(header, OFFSET_CRC, crc32(body))

        return ByteArrayOutputStream(header.size + body.size).use { output ->
            output.write(header)
            output.write(body)
            output.toByteArray()
        }
    }

    fun writeFrame(
        frame: ProtocolFrame,
        outputStream: OutputStream,
        key: ByteArray? = null,
        nonce: ByteArray? = null
    ) {
        outputStream.write(encode(frame, key, nonce))
        outputStream.flush()
    }

    /**
     * Reads one v3 frame, or null at a clean end of stream.
     *
     * @param key AES-256 key for this inbound direction, or null if the session is
     *        not yet keyed.
     * @param requireEncryption once the session is keyed this must be true, so
     *        that an attacker cannot strip [ProtocolConstants.FLAG_ENCRYPTED] and
     *        downgrade the channel to plaintext.
     */
    fun readFrame(
        inputStream: InputStream,
        key: ByteArray? = null,
        requireEncryption: Boolean = false
    ): ProtocolFrame? {
        val header = ByteArray(ProtocolConstants.HEADER_SIZE_V3)
        val firstByte = inputStream.read()
        if (firstByte == -1) return null
        header[0] = firstByte.toByte()
        readFully(inputStream, header, 1, header.size - 1, "header")

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)

        val magic = buffer.int
        if (magic != ProtocolConstants.MAGIC) {
            throw ProtocolException(
                ProtocolError.MALFORMED_MAGIC,
                "Malformed protocol magic: 0x${magic.toUInt().toString(16)}"
            )
        }

        val version = buffer.unsignedShort()
        if (version != ProtocolConstants.VERSION_V3) {
            throw ProtocolException(
                ProtocolError.UNSUPPORTED_VERSION,
                "SecureProtocolCodec expects version ${ProtocolConstants.VERSION_V3}, got $version"
            )
        }

        val headerSize = buffer.unsignedShort()
        if (headerSize != ProtocolConstants.HEADER_SIZE_V3) {
            throw ProtocolException(
                ProtocolError.INVALID_HEADER_SIZE,
                "Invalid v3 header size: $headerSize"
            )
        }

        val type = ProtocolFrameType.fromId(buffer.unsignedShort())
        val flags = buffer.unsignedShort()
        val channel = ProtocolChannel.fromId(buffer.unsignedShort())
        buffer.unsignedShort() // reserved
        val streamId = buffer.long
        val sequenceNumber = buffer.long
        val correlationId = UUID(buffer.long, buffer.long)
        val timestampMs = buffer.long
        val senderDeviceId = DeviceId(UUID(buffer.long, buffer.long))

        val nonce = ByteArray(CryptoPrimitives.GCM_NONCE_BYTES)
        buffer.get(nonce)

        val declaredCrc = buffer.int
        val metadataLength = buffer.int
        val payloadLength = buffer.int
        validateLengths(metadataLength, payloadLength)

        val encrypted = flags and ProtocolConstants.FLAG_ENCRYPTED != 0
        if (encrypted && key == null) {
            throw ProtocolException(
                ProtocolError.KEY_UNAVAILABLE,
                "Received an encrypted frame but no session key is available"
            )
        }
        if (!encrypted && requireEncryption) {
            throw ProtocolException(
                ProtocolError.ENCRYPTION_REQUIRED,
                "Received a plaintext ${type.protocolName} frame on a keyed session"
            )
        }

        val bodyLength = metadataLength + payloadLength +
            if (encrypted) CryptoPrimitives.GCM_TAG_BYTES else 0
        val body = ByteArray(bodyLength)
        if (bodyLength > 0) {
            readFully(inputStream, body, 0, bodyLength, "body")
        }

        // Cheap corruption check before spending time on AES-GCM.
        val actualCrc = crc32(body)
        if (actualCrc != declaredCrc) {
            throw ProtocolException(
                ProtocolError.CHECKSUM_MISMATCH,
                "Body CRC mismatch: declared 0x${declaredCrc.toUInt().toString(16)}, " +
                    "actual 0x${actualCrc.toUInt().toString(16)}"
            )
        }

        val plaintext = if (encrypted) {
            try {
                CryptoPrimitives.aeadOpen(
                    key = key!!,
                    nonce = nonce,
                    associatedData = associatedData(header),
                    sealed = body
                )
            } catch (exception: AEADBadTagException) {
                throw ProtocolException(
                    ProtocolError.AUTHENTICATION_FAILED,
                    "GCM authentication failed for ${type.protocolName}; frame was forged or tampered with"
                )
            }
        } else {
            body
        }

        if (plaintext.size != metadataLength + payloadLength) {
            throw ProtocolException(
                ProtocolError.INVALID_LENGTH,
                "Decoded body is ${plaintext.size} bytes, header declared ${metadataLength + payloadLength}"
            )
        }

        val metadataJson = if (metadataLength > 0) {
            String(plaintext, 0, metadataLength, Charsets.UTF_8)
        } else {
            ""
        }
        val payload = if (payloadLength > 0) {
            plaintext.copyOfRange(metadataLength, metadataLength + payloadLength)
        } else {
            ByteArray(0)
        }

        return ProtocolFrame(
            type = type,
            channel = channel,
            flags = flags,
            streamId = streamId,
            sequenceNumber = sequenceNumber,
            correlationId = correlationId,
            metadataJson = metadataJson,
            payload = payload,
            timestampMs = timestampMs,
            senderDeviceId = senderDeviceId
        )
    }

    /**
     * True when [timestampMs] is within [maxAgeMs] of [nowMs].
     *
     * Tolerates a clock that is *ahead* by the same margin, because the two phones
     * are never synchronised and rejecting future-dated frames outright would
     * break a link between devices whose clocks differ by a minute.
     */
    fun isFresh(
        timestampMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = ProtocolConstants.MAX_FRAME_AGE_MS
    ): Boolean {
        if (timestampMs <= 0L) return false
        return kotlin.math.abs(nowMs - timestampMs) <= maxAgeMs
    }

    // ------------------------------------------------------------- internals --

    private fun buildHeader(
        frame: ProtocolFrame,
        flags: Int,
        nonce: ByteArray,
        metadataLength: Int,
        payloadLength: Int,
        bodyCrc32: Int
    ): ByteArray = ByteBuffer.allocate(ProtocolConstants.HEADER_SIZE_V3)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(ProtocolConstants.MAGIC)
        .putShort(ProtocolConstants.VERSION_V3.toShort())
        .putShort(ProtocolConstants.HEADER_SIZE_V3.toShort())
        .putShort(frame.type.id.toShort())
        .putShort(flags.toShort())
        .putShort(frame.channel.id.toShort())
        .putShort(0)
        .putLong(frame.streamId)
        .putLong(frame.sequenceNumber)
        .putLong(frame.correlationId.mostSignificantBits)
        .putLong(frame.correlationId.leastSignificantBits)
        .putLong(frame.timestampMs)
        .putLong(frame.senderDeviceId.value.mostSignificantBits)
        .putLong(frame.senderDeviceId.value.leastSignificantBits)
        .put(nonce)
        .putInt(bodyCrc32)
        .putInt(metadataLength)
        .putInt(payloadLength)
        .array()

    /**
     * The header with the CRC field zeroed, used as GCM associated data.
     * See the class comment for why the CRC is excluded.
     */
    private fun associatedData(header: ByteArray): ByteArray =
        header.copyOf().also { writeInt(it, OFFSET_CRC, 0) }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun crc32(data: ByteArray): Int {
        val crc = CRC32()
        crc.update(data)
        return crc.value.toInt()
    }

    private fun validateLengths(metadataLength: Int, payloadLength: Int) {
        if (metadataLength < 0 || payloadLength < 0) {
            throw ProtocolException(
                ProtocolError.INVALID_LENGTH,
                "Negative metadata or payload length"
            )
        }
        if (metadataLength > ProtocolConstants.MAX_METADATA_BYTES) {
            throw ProtocolException(
                ProtocolError.METADATA_TOO_LARGE,
                "Metadata length $metadataLength exceeds ${ProtocolConstants.MAX_METADATA_BYTES}"
            )
        }
        if (payloadLength > ProtocolConstants.MAX_PAYLOAD_BYTES) {
            throw ProtocolException(
                ProtocolError.PAYLOAD_TOO_LARGE,
                "Payload length $payloadLength exceeds ${ProtocolConstants.MAX_PAYLOAD_BYTES}"
            )
        }
    }

    private fun readFully(
        inputStream: InputStream,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        label: String
    ) {
        var totalRead = 0
        while (totalRead < length) {
            val read = inputStream.read(buffer, offset + totalRead, length - totalRead)
            if (read == -1) {
                throw ProtocolException(
                    ProtocolError.PARTIAL_READ,
                    "Unexpected end of stream while reading $label"
                )
            }
            totalRead += read
        }
    }

    private fun ByteBuffer.unsignedShort(): Int = short.toInt() and 0xffff
}

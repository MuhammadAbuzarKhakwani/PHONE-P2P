package com.jingcjie.wifi_direct_cable.protocol

import com.jingcjie.wifi_direct_cable.core.model.DeviceId
import java.util.UUID

/**
 * One protocol frame.
 *
 * The v3 fields ([timestampMs], [senderDeviceId]) carry defaults so that every
 * existing v2 construction site continues to compile and behave identically.
 * [ProtocolCodec] (v2) ignores them; [SecureProtocolCodec] (v3) encodes them.
 *
 * @param correlationId doubles as the protocol's message ID. Unique per request,
 *        and echoed on the matching response so replies can be correlated.
 * @param timestampMs sender wall-clock time in epoch milliseconds. Used for
 *        freshness checks, not for ordering — ordering is [sequenceNumber]'s job,
 *        because the two devices' clocks are not synchronised.
 * @param senderDeviceId identifies the sending installation. [DeviceId.UNSPECIFIED]
 *        on v2 frames and during the very first handshake message.
 */
data class ProtocolFrame(
    val type: ProtocolFrameType,
    val channel: ProtocolChannel,
    val flags: Int = 0,
    val streamId: Long = 0,
    val sequenceNumber: Long = 0,
    val correlationId: UUID = UUID(0L, 0L),
    val metadataJson: String = "",
    val payload: ByteArray = ByteArray(0),
    val timestampMs: Long = 0L,
    val senderDeviceId: DeviceId = DeviceId.UNSPECIFIED
) {
    /** True if this frame was carried encrypted on the wire. */
    val isEncrypted: Boolean
        get() = flags and ProtocolConstants.FLAG_ENCRYPTED != 0

    /**
     * data class equality would compare [payload] by identity, which silently
     * breaks assertions in tests and any set/map use. Compare contents instead.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProtocolFrame) return false
        return type == other.type &&
            channel == other.channel &&
            flags == other.flags &&
            streamId == other.streamId &&
            sequenceNumber == other.sequenceNumber &&
            correlationId == other.correlationId &&
            metadataJson == other.metadataJson &&
            payload.contentEquals(other.payload) &&
            timestampMs == other.timestampMs &&
            senderDeviceId == other.senderDeviceId
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + channel.hashCode()
        result = 31 * result + flags
        result = 31 * result + streamId.hashCode()
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + correlationId.hashCode()
        result = 31 * result + metadataJson.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + senderDeviceId.hashCode()
        return result
    }
}

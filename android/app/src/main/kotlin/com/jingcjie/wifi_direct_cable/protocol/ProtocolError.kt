package com.jingcjie.wifi_direct_cable.protocol

enum class ProtocolError {
    PARTIAL_READ,
    MALFORMED_MAGIC,
    UNSUPPORTED_VERSION,
    INVALID_HEADER_SIZE,
    INVALID_FRAME_TYPE,
    INVALID_CHANNEL,
    INVALID_LENGTH,
    METADATA_TOO_LARGE,
    PAYLOAD_TOO_LARGE,

    // --- v3 secure framing --------------------------------------------------

    /** Body CRC did not match. Indicates corruption, not necessarily an attack. */
    CHECKSUM_MISMATCH,

    /** GCM tag verification failed. The frame was forged or tampered with. */
    AUTHENTICATION_FAILED,

    /** A plaintext frame arrived on a channel that has already been keyed. */
    ENCRYPTION_REQUIRED,

    /** No key available to open an encrypted frame. */
    KEY_UNAVAILABLE,

    /** Frame timestamp is outside the accepted freshness window. */
    STALE_FRAME,

    /** Sequence number was already seen, or has fallen out of the replay window. */
    REPLAYED_FRAME,

    /** Frame came from a device ID that is not the paired peer. */
    UNKNOWN_DEVICE
}

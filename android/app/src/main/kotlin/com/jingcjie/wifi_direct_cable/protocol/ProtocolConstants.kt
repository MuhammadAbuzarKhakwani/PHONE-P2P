package com.jingcjie.wifi_direct_cable.protocol

object ProtocolConstants {
    const val MAGIC = 0x57444342 // WDCB

    /**
     * Wire version of the legacy plaintext framing. Unchanged, and still what
     * [ProtocolCodec] speaks, so the shipped Windows companion app (WDCableWUI)
     * keeps interoperating.
     */
    const val VERSION = 2
    const val HEADER_SIZE = 56

    /**
     * Wire version of the authenticated framing spoken by [SecureProtocolCodec].
     * Adds a timestamp, a sender device ID, a GCM nonce, and a body checksum, and
     * carries the metadata and payload under AES-256-GCM once keys exist.
     *
     * v3 is negotiated at handshake, never assumed: a peer that only offers v2 is
     * still usable for the existing non-telephony features, but the gateway and
     * telephony channels are refused on an unauthenticated link.
     */
    const val VERSION_V3 = 3
    const val HEADER_SIZE_V3 = 96

    const val MAX_METADATA_BYTES = 64 * 1024
    const val MAX_PAYLOAD_BYTES = 1024 * 1024

    /** Frame flag: metadata and payload are sealed with AES-256-GCM. */
    const val FLAG_ENCRYPTED = 0x0001

    /**
     * Frames older than this are rejected as stale. Generous, because the two
     * devices' wall clocks are not synchronised and may differ by minutes; the
     * real replay defence is [com.jingcjie.wifi_direct_cable.core.security.ReplayWindow],
     * not this bound.
     */
    const val MAX_FRAME_AGE_MS = 5L * 60L * 1000L

    const val APP_ID = "wdcable"

    const val DEFAULT_RENDEZVOUS_PORT = 8987
    const val DEFAULT_CONTROL_PORT = 8988
    const val DEFAULT_BULK_PORT = 8989

    const val CAPABILITY_CHAT = "control.chat"
    const val CAPABILITY_BULK_FILE = "bulk.file"
    const val CAPABILITY_BULK_SPEED = "bulk.speed"
    const val CAPABILITY_DIAGNOSTICS_EXPORT = "diagnostics.export"
    const val CAPABILITY_AUDIO_LINK = "audio.link"
    const val CAPABILITY_AUDIO_CODEC_OPUS = "audio.codec.opus"
    const val CAPABILITY_AUDIO_TRANSPORT_RTP = "audio.transport.rtp"
    const val CAPABILITY_AUDIO_RTCP = "audio.rtcp"
    const val CAPABILITY_AUDIO_CODEC_LIBOPUS = "audio.codec.libopus"
    const val CAPABILITY_AUDIO_QUALITY_SELECT = "audio.quality.select"

    // --- v3 secure channel -------------------------------------------------

    /** Peer can speak the v3 authenticated framing. */
    const val CAPABILITY_SECURE_V3 = "secure.v3"

    /** Peer supports code-based pairing and can establish a long-term identity. */
    const val CAPABILITY_PAIRING = "secure.pairing"

    // --- Remote SIM gateway ------------------------------------------------
    //
    // Advertised by Phone 1 only, and only for the operations that device has
    // actually been granted. A capability that is absent means "this device
    // cannot do this", which is what the UI renders as "Unavailable on this
    // device" rather than inventing a value.

    /** Device has a SIM and can act as the cellular gateway. */
    const val CAPABILITY_GATEWAY = "gateway"

    /** Gateway can report SIM, signal, and network state. */
    const val CAPABILITY_GATEWAY_STATUS = "gateway.status"

    /** Gateway can place outgoing calls (CALL_PHONE granted). */
    const val CAPABILITY_TELEPHONY_DIAL = "telephony.dial"

    /** Gateway can answer and end calls (ANSWER_PHONE_CALLS granted, API 26+/28+). */
    const val CAPABILITY_TELEPHONY_ANSWER = "telephony.answer"

    /** Gateway can report call state (READ_PHONE_STATE granted). */
    const val CAPABILITY_TELEPHONY_STATE = "telephony.state"

    /** Gateway can send SMS (SEND_SMS granted). */
    const val CAPABILITY_SMS_SEND = "sms.send"

    /** Gateway can read SMS. Requires being the default SMS handler in practice. */
    const val CAPABILITY_SMS_READ = "sms.read"

    /**
     * Deliberately absent from every advertised capability set.
     *
     * Routing live cellular call audio requires the signature-level
     * CAPTURE_AUDIO_OUTPUT permission and is not available to a normal
     * third-party app on any current Android version. The constant exists only so
     * that code and tests can refer to the capability the app does NOT have, and
     * so the client can render an explicit "not supported" state instead of
     * silently offering a feature that cannot work.
     */
    const val CAPABILITY_CELLULAR_CALL_AUDIO = "telephony.audio.cellular"
}

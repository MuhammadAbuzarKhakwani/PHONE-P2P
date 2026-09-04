package com.jingcjie.wifi_direct_cable.core.security

/**
 * Which end of the handshake this device is. Kept independent of
 * `session.SessionRole` so the security layer has no dependency on the session
 * layer and can be unit-tested on its own.
 *
 * The mapping is fixed by [SecureRole.forGroupOwner] so both devices agree which
 * directional key is which.
 */
enum class SecureRole {
    /** Sends the first handshake message. Mapped to the Wi-Fi Direct group owner. */
    INITIATOR,

    /** Responds to the handshake. Mapped to the Wi-Fi Direct client. */
    RESPONDER;

    fun opposite(): SecureRole = if (this == INITIATOR) RESPONDER else INITIATOR

    companion object {
        fun forGroupOwner(isGroupOwner: Boolean): SecureRole =
            if (isGroupOwner) INITIATOR else RESPONDER
    }
}

/**
 * The directional AES-256-GCM keys for one session.
 *
 * Two independent keys are derived, one per direction. Sharing a single key
 * across both directions would force both peers to coordinate a single nonce
 * counter to avoid reuse; separate keys make each side's counter independent and
 * remove that failure mode entirely.
 *
 * Keys are ephemeral: they exist only for the lifetime of one session and are
 * never written to disk. The long-term pairing secret in [PairingStore] is a
 * different value and is only ever used as HKDF input material.
 */
class SessionKeys private constructor(
    val role: SecureRole,
    private val transmitKey: ByteArray,
    private val receiveKey: ByteArray,
    val sessionId: String,
    val establishedAtMs: Long
) {

    fun transmitKey(): ByteArray = transmitKey.copyOf()

    fun receiveKey(): ByteArray = receiveKey.copyOf()

    /**
     * Zeroes both key buffers. Call on session teardown so keys do not linger in
     * the heap for the rest of the process lifetime.
     */
    fun destroy() {
        CryptoPrimitives.wipe(transmitKey)
        CryptoPrimitives.wipe(receiveKey)
    }

    /** True once the session has outlived [maxAgeMs] and must be renegotiated. */
    fun isStale(nowMs: Long, maxAgeMs: Long = MAX_SESSION_AGE_MS): Boolean =
        nowMs - establishedAtMs >= maxAgeMs

    companion object {
        /**
         * Sessions are torn down and rekeyed after this long. Bounds the amount
         * of traffic under one key and satisfies the Phase 13 requirement for a
         * "timeout for stale sessions".
         */
        const val MAX_SESSION_AGE_MS = 12L * 60L * 60L * 1000L // 12 hours

        private const val LABEL_INITIATOR_TO_RESPONDER = "wdcable/v3/key/initiator-to-responder"
        private const val LABEL_RESPONDER_TO_INITIATOR = "wdcable/v3/key/responder-to-initiator"

        /**
         * Derives both directional keys from the handshake output.
         *
         * @param sharedSecret raw ECDH output, or the long-term pairing secret on
         *        a resumed session. Never used directly as a key — always run
         *        through HKDF first, because raw ECDH output is not uniformly
         *        distributed.
         * @param transcriptHash binds the keys to the exact handshake that
         *        produced them, so a tampered handshake yields different keys and
         *        the first frame simply fails to authenticate.
         */
        /**
         * @param channelLabel separates the keys used by the CONTROL, BULK and
         *        AUDIO connections. Each channel runs its own nonce counter, so
         *        sharing one key across all three would make nonce uniqueness
         *        depend on their random 4-byte prefixes never colliding. Giving
         *        each channel its own key removes that risk entirely rather than
         *        making it merely unlikely.
         *
         *        Defaults to empty, which reproduces the single-key derivation
         *        exactly, so existing callers and tests are unaffected.
         */
        fun derive(
            role: SecureRole,
            sharedSecret: ByteArray,
            transcriptHash: ByteArray,
            sessionId: String,
            nowMs: Long,
            channelLabel: String = ""
        ): SessionKeys {
            val suffix = if (channelLabel.isEmpty()) "" else "/$channelLabel"
            val initiatorToResponder = CryptoPrimitives.hkdf(
                inputKeyMaterial = sharedSecret,
                salt = transcriptHash,
                info = (LABEL_INITIATOR_TO_RESPONDER + suffix).toByteArray(Charsets.UTF_8),
                outputLength = CryptoPrimitives.AES_KEY_BYTES
            )
            val responderToInitiator = CryptoPrimitives.hkdf(
                inputKeyMaterial = sharedSecret,
                salt = transcriptHash,
                info = (LABEL_RESPONDER_TO_INITIATOR + suffix).toByteArray(Charsets.UTF_8),
                outputLength = CryptoPrimitives.AES_KEY_BYTES
            )

            return when (role) {
                SecureRole.INITIATOR -> SessionKeys(
                    role = role,
                    transmitKey = initiatorToResponder,
                    receiveKey = responderToInitiator,
                    sessionId = sessionId,
                    establishedAtMs = nowMs
                )

                SecureRole.RESPONDER -> SessionKeys(
                    role = role,
                    transmitKey = responderToInitiator,
                    receiveKey = initiatorToResponder,
                    sessionId = sessionId,
                    establishedAtMs = nowMs
                )
            }
        }
    }
}

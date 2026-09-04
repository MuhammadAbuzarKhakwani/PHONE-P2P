package com.jingcjie.wifi_direct_cable.core.security

import com.jingcjie.wifi_direct_cable.core.model.DeviceId
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement

/**
 * The authenticated key exchange run over the control channel before any
 * application traffic is accepted.
 *
 * Deliberately a pure state machine with no socket, no `Context`, and no Android
 * dependency beyond `org.json` (already a test dependency of this module). It can
 * therefore be driven end-to-end in a plain JVM unit test by wiring two instances
 * to each other, which is how the handshake is actually verified — see
 * `SecureHandshakeTest`.
 *
 * ## Shape
 *
 * ```
 * INITIATOR (Wi-Fi Direct group owner)      RESPONDER (Wi-Fi Direct client)
 *   PAIR_REQUEST  { deviceId, pub, nonce } ---->
 *                                          <---- PAIR_RESPONSE { deviceId, pub, nonce }
 *   PAIR_CONFIRM  { mac }                  ---->
 *                                          <---- PAIR_RESULT  { mac, ok }
 * ```
 *
 * Both sides then hold identical [SessionKeys] and a matching [shortAuthString].
 *
 * ## Cryptography
 *
 * - **P-256 ECDH** for key agreement. Chosen over X25519 because `XDH` support is
 *   uneven across Android 13 devices and OEM providers, while `secp256r1` ECDH has
 *   been universally available for years. The security margin is more than
 *   adequate here and device compatibility matters more.
 * - The raw ECDH output is **never** used as a key directly — it is not uniformly
 *   distributed. Everything goes through HKDF-SHA256.
 * - The **transcript hash** binds every field both sides exchanged. Tampering with
 *   any of them yields different keys, so the first encrypted frame simply fails
 *   to authenticate.
 * - The **auth secret** is the typed [PairingCode] on first pairing, or the stored
 *   long-term secret on every later connection.
 *
 * See [PairingCode] for the honest analysis of what the short code does and does
 * not defend against, and why [shortAuthString] exists.
 */
class SecureHandshake(
    val role: SecureRole,
    val localDeviceId: DeviceId,
    val sessionId: String,
    keyPair: KeyPair = generateKeyPair()
) {

    private val localKeyPair: KeyPair = keyPair
    private val localNonce: ByteArray = CryptoPrimitives.randomBytes(NONCE_BYTES)

    private var peerDeviceId: DeviceId? = null

    private var sharedSecret: ByteArray? = null
    private var transcript: ByteArray? = null

    val localPublicKeyBytes: ByteArray get() = localKeyPair.public.encoded

    /** The offer this device sends: `PAIR_REQUEST` for the initiator, `PAIR_RESPONSE` for the responder. */
    fun buildOffer(): JSONObject = JSONObject()
        .put(FIELD_DEVICE_ID, localDeviceId.toString())
        .put(FIELD_PUBLIC_KEY, encode(localPublicKeyBytes))
        .put(FIELD_NONCE, encode(localNonce))

    /**
     * Reads just the device ID out of a peer offer, without consuming it.
     *
     * Needed because the caller has to know *who* the peer is before it can
     * decide which auth secret applies — a stored long-term secret for a known
     * peer, or a freshly typed pairing code for a new one.
     */
    fun peerDeviceIdFrom(offer: JSONObject): DeviceId =
        DeviceId.parseOrNull(offer.optString(FIELD_DEVICE_ID, null))
            ?: throw HandshakeException("peer offer has no valid deviceId")

    /**
     * Consumes the peer's offer and completes key agreement.
     *
     * @param authSecret the typed [PairingCode] bytes on a first pairing, or the
     *        stored long-term secret when reconnecting to a known peer. Supplied
     *        here rather than at construction because the peer's identity — and
     *        therefore which secret applies — is only known once its offer
     *        arrives.
     * @throws HandshakeException if the offer is malformed, or if the peer echoed
     *         our own public key back at us — a reflection attack that would
     *         otherwise let an attacker with no key material drive both sides to
     *         the same transcript.
     */
    fun acceptOffer(offer: JSONObject, authSecret: ByteArray) {
        check(sharedSecret == null) { "handshake offer already accepted" }

        val deviceId = DeviceId.parseOrNull(offer.optString(FIELD_DEVICE_ID, null))
            ?: throw HandshakeException("peer offer has no valid deviceId")
        val publicKeyBytes = decodeOrNull(offer.optString(FIELD_PUBLIC_KEY, null))
            ?: throw HandshakeException("peer offer has no valid public key")
        val nonce = decodeOrNull(offer.optString(FIELD_NONCE, null))
            ?: throw HandshakeException("peer offer has no valid nonce")

        if (nonce.size != NONCE_BYTES) {
            throw HandshakeException("peer nonce must be $NONCE_BYTES bytes, was ${nonce.size}")
        }
        if (publicKeyBytes.contentEquals(localPublicKeyBytes)) {
            throw HandshakeException("peer echoed our own public key; refusing reflected handshake")
        }
        if (deviceId == localDeviceId) {
            throw HandshakeException("peer claims our own device id; refusing reflected handshake")
        }

        val peerPublicKey = try {
            KeyFactory.getInstance(KEY_ALGORITHM)
                .generatePublic(X509EncodedKeySpec(publicKeyBytes))
        } catch (exception: Exception) {
            // Covers a malformed encoding and a point that is not on the curve;
            // the provider validates the latter when building the key.
            throw HandshakeException("peer public key is not a valid P-256 key", exception)
        }

        val agreed = try {
            KeyAgreement.getInstance(AGREEMENT_ALGORITHM).run {
                init(localKeyPair.private)
                doPhase(peerPublicKey, true)
                generateSecret()
            }
        } catch (exception: Exception) {
            throw HandshakeException("ECDH key agreement failed", exception)
        }

        peerDeviceId = deviceId

        // The keying material is the ECDH output concatenated with the auth
        // secret, so an attacker needs BOTH the private key and the code or
        // long-term secret. The ephemeral ECDH half also gives forward secrecy:
        // compromising the stored long-term secret later does not decrypt
        // recorded sessions.
        sharedSecret = ByteArrayOutputStream(agreed.size + authSecret.size).use { out ->
            out.write(agreed)
            out.write(authSecret)
            out.toByteArray()
        }
        CryptoPrimitives.wipe(agreed)

        transcript = computeTranscript(deviceId, publicKeyBytes, nonce)
    }

    fun peerDeviceId(): DeviceId =
        peerDeviceId ?: throw HandshakeException("peer offer has not been accepted yet")

    fun transcriptHash(): ByteArray =
        (transcript ?: throw HandshakeException("handshake is not complete")).copyOf()

    /** The confirmation MAC this device sends. */
    fun localConfirmation(): ByteArray = confirmationFor(role)

    /**
     * Verifies the peer's confirmation MAC in constant time.
     *
     * A mismatch means the typed pairing code was wrong, or the long-term secret
     * has diverged, or someone is interfering. Callers must abort the session —
     * never retry silently, since retries turn a one-guess-per-attempt code into
     * an online oracle.
     */
    fun verifyPeerConfirmation(mac: ByteArray): Boolean =
        CryptoPrimitives.constantTimeEquals(confirmationFor(role.opposite()), mac)

    /**
     * A 6-digit value derived from the completed transcript, shown on both phones.
     *
     * If the two displayed values match, there is no man-in-the-middle: an
     * attacker relaying the handshake necessarily has a different transcript with
     * each side, so it cannot make both values agree. This is the check that
     * actually defeats an active MITM — the typed pairing code alone does not.
     */
    fun shortAuthString(): String {
        val bytes = derive(INFO_SAS, 4)
        val value = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).int
        return ((value.toLong() and 0xffffffffL) % 1_000_000L).toString().padStart(6, '0')
    }

    /**
     * The secret persisted after a successful first pairing and used as the auth
     * secret on every later connection, so the user types the code exactly once.
     */
    fun deriveLongTermSecret(): ByteArray = derive(INFO_LONG_TERM, CryptoPrimitives.AES_KEY_BYTES)

    /**
     * @param channelLabel gives the CONTROL, BULK and AUDIO connections
     *        independent keys. Each holds its own nonce counter, so they must not
     *        share a key. See [SessionKeys.derive].
     */
    fun sessionKeys(
        nowMs: Long = System.currentTimeMillis(),
        channelLabel: String = ""
    ): SessionKeys = SessionKeys.derive(
        role = role,
        sharedSecret = requireSharedSecret(),
        transcriptHash = transcriptHash(),
        sessionId = sessionId,
        nowMs = nowMs,
        channelLabel = channelLabel
    )

    /** Wipes the ephemeral secrets. Call once [sessionKeys] has been taken. */
    fun destroy() {
        sharedSecret?.let(CryptoPrimitives::wipe)
        CryptoPrimitives.wipe(localNonce)
    }

    // ------------------------------------------------------------- internals --

    private fun confirmationFor(who: SecureRole): ByteArray {
        val confirmKey = derive(INFO_CONFIRM, CryptoPrimitives.AES_KEY_BYTES)
        return try {
            CryptoPrimitives.hmacSha256(confirmKey, who.name.toByteArray(Charsets.UTF_8))
        } finally {
            CryptoPrimitives.wipe(confirmKey)
        }
    }

    private fun derive(info: String, length: Int): ByteArray = CryptoPrimitives.hkdf(
        inputKeyMaterial = requireSharedSecret(),
        salt = transcriptHash(),
        info = info.toByteArray(Charsets.UTF_8),
        outputLength = length
    )

    private fun requireSharedSecret(): ByteArray =
        sharedSecret ?: throw HandshakeException("handshake is not complete")

    /**
     * Hashes every exchanged field in a role-fixed order, so both devices compute
     * the same value regardless of which of them is local.
     *
     * Every variable-length field is length-prefixed. Without that, a shorter key
     * and a longer nonce could be shifted between fields to produce a colliding
     * transcript.
     */
    private fun computeTranscript(
        peerId: DeviceId,
        peerPublicKey: ByteArray,
        peerNonceBytes: ByteArray
    ): ByteArray {
        val initiatorId: DeviceId
        val responderId: DeviceId
        val initiatorPublicKey: ByteArray
        val responderPublicKey: ByteArray
        val initiatorNonce: ByteArray
        val responderNonce: ByteArray

        if (role == SecureRole.INITIATOR) {
            initiatorId = localDeviceId
            responderId = peerId
            initiatorPublicKey = localPublicKeyBytes
            responderPublicKey = peerPublicKey
            initiatorNonce = localNonce
            responderNonce = peerNonceBytes
        } else {
            initiatorId = peerId
            responderId = localDeviceId
            initiatorPublicKey = peerPublicKey
            responderPublicKey = localPublicKeyBytes
            initiatorNonce = peerNonceBytes
            responderNonce = localNonce
        }

        val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
        digest.update(TRANSCRIPT_LABEL.toByteArray(Charsets.UTF_8))
        digest.update(initiatorId.toBytes())
        digest.update(responderId.toBytes())
        digest.updateLengthPrefixed(initiatorPublicKey)
        digest.updateLengthPrefixed(responderPublicKey)
        digest.updateLengthPrefixed(initiatorNonce)
        digest.updateLengthPrefixed(responderNonce)
        return digest.digest()
    }

    private fun MessageDigest.updateLengthPrefixed(data: ByteArray) {
        update(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array())
        update(data)
    }

    class HandshakeException(message: String, cause: Throwable? = null) : Exception(message, cause)

    companion object {
        const val FIELD_DEVICE_ID = "deviceId"
        const val FIELD_PUBLIC_KEY = "publicKey"
        const val FIELD_NONCE = "nonce"
        const val FIELD_MAC = "mac"
        const val FIELD_OK = "ok"
        const val FIELD_REASON = "reason"

        private const val NONCE_BYTES = 32
        private const val KEY_ALGORITHM = "EC"
        private const val CURVE = "secp256r1"
        private const val AGREEMENT_ALGORITHM = "ECDH"
        private const val DIGEST_ALGORITHM = "SHA-256"

        private const val TRANSCRIPT_LABEL = "wdcable/v3/transcript"
        private const val INFO_CONFIRM = "wdcable/v3/confirm"
        private const val INFO_SAS = "wdcable/v3/sas"
        private const val INFO_LONG_TERM = "wdcable/v3/longterm"

        fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance(KEY_ALGORITHM).run {
            initialize(ECGenParameterSpec(CURVE))
            generateKeyPair()
        }

        fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        fun decodeOrNull(text: String?): ByteArray? {
            if (text.isNullOrBlank()) return null
            return try {
                Base64.getDecoder().decode(text)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

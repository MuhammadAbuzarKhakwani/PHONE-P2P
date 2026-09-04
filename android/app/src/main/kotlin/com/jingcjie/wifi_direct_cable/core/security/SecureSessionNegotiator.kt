package com.jingcjie.wifi_direct_cable.core.security

import com.jingcjie.wifi_direct_cable.core.model.DeviceId
import com.jingcjie.wifi_direct_cable.diagnostics.DiagnosticsLogger
import com.jingcjie.wifi_direct_cable.protocol.ProtocolChannel
import com.jingcjie.wifi_direct_cable.protocol.ProtocolFrame
import com.jingcjie.wifi_direct_cable.protocol.ProtocolFrameType
import com.jingcjie.wifi_direct_cable.session.SessionTransport
import org.json.JSONObject
import java.io.IOException

/**
 * Drives the four-message pairing exchange over an established control transport,
 * then hands back the material needed to key every channel.
 *
 * ## Message ordering, and why it is this way
 *
 * ```
 * INITIATOR (group owner)                     RESPONDER (client)
 *   pair.request  { offer }        ---->
 *                                  <----      pair.response { offer }
 *   -- both now know the peer's identity, and resolve their auth secret --
 *   pair.confirm  { mac }          ---->
 *                                  <----      pair.result   { mac, ok }
 *   -- both switch to v3 authenticated framing --
 * ```
 *
 * Offers are exchanged **before** either side resolves its auth secret. That
 * ordering is deliberate: resolving the secret may block for a long time on the
 * client while a person reads a code off one phone and types it into the other.
 * If the responder had to resolve before replying, the initiator would sit in a
 * blocking read for that whole period. Exchanging offers first keeps the slow,
 * human part confined to a single window that both sides expect, for which
 * [pairingReadTimeoutMs] is raised.
 *
 * Both peers switch to the secure framing at exactly the same point in the byte
 * stream — immediately after `pair.result` crosses — so they never disagree about
 * how to parse the next frame.
 *
 * Transport-agnostic and Android-free, so the whole negotiation is exercised in
 * `SecureSessionNegotiatorTest` against an in-memory transport pair.
 */
class SecureSessionNegotiator(
    private val repository: PairingRepository,
    private val prompt: PairingPrompt,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pairingReadTimeoutMs: Int = DEFAULT_PAIRING_TIMEOUT_MS,
    private val normalReadTimeoutMs: Int = DEFAULT_NORMAL_TIMEOUT_MS
) {

    /**
     * A completed negotiation. Holds the live handshake so per-channel keys can
     * be derived; call [close] once every channel has been keyed.
     */
    class Result internal constructor(
        val peerDeviceId: DeviceId,
        val peerName: String,
        val shortAuthString: String,
        val isNewPairing: Boolean,
        val sasVerified: Boolean,
        private val handshake: SecureHandshake
    ) {
        val role: SecureRole get() = handshake.role

        fun keysFor(channel: ProtocolChannel, nowMs: Long = System.currentTimeMillis()): SessionKeys =
            handshake.sessionKeys(nowMs, channel.protocolName)

        /** Wipes the ephemeral handshake secrets. */
        fun close() = handshake.destroy()
    }

    class NegotiationException(
        val failureReason: String,
        message: String,
        cause: Throwable? = null
    ) : IOException(message, cause)

    /**
     * Runs the exchange to completion.
     *
     * @throws NegotiationException on any failure. The caller must tear the
     *         session down; a failed confirmation must never be retried, because
     *         repeated attempts would turn a six-digit code into an online oracle.
     */
    fun negotiate(
        transport: SessionTransport,
        role: SecureRole,
        sessionId: String,
        localName: String = repository.localDisplayName()
    ): Result {
        val localDeviceId = repository.localDeviceId()
        val handshake = SecureHandshake(role, localDeviceId, sessionId)

        DiagnosticsLogger.log(
            "security",
            "Starting secure negotiation",
            mapOf(
                "sessionId" to sessionId,
                "role" to role.name,
                "localDeviceId" to localDeviceId.shortLabel()
            )
        )

        try {
            // --- 1. exchange offers -----------------------------------------
            val peerOffer: JSONObject
            if (role == SecureRole.INITIATOR) {
                write(transport, ProtocolFrameType.PAIR_REQUEST, handshake.buildOffer(), localName)
                peerOffer = readPayload(transport, ProtocolFrameType.PAIR_RESPONSE)
            } else {
                peerOffer = readPayload(transport, ProtocolFrameType.PAIR_REQUEST)
                write(transport, ProtocolFrameType.PAIR_RESPONSE, handshake.buildOffer(), localName)
            }

            val peerDeviceId = handshake.peerDeviceIdFrom(peerOffer)
            val peerName = peerOffer.optString(FIELD_NAME, "").ifBlank { peerDeviceId.shortLabel() }

            // --- 2. resolve the auth secret (may block on the user) ----------
            transport.setReadTimeout(pairingReadTimeoutMs)
            val existing = repository.findPairing(peerDeviceId)
            val isNewPairing = existing == null
            val authSecret = existing?.longTermSecret ?: promptForCode(role, peerDeviceId, peerName)

            handshake.acceptOffer(peerOffer, authSecret)

            // --- 3. exchange confirmations ----------------------------------
            if (role == SecureRole.INITIATOR) {
                write(transport, ProtocolFrameType.PAIR_CONFIRM, confirmBody(handshake), localName)
                val result = readPayload(transport, ProtocolFrameType.PAIR_RESULT)
                if (!result.optBoolean(SecureHandshake.FIELD_OK, false)) {
                    throw NegotiationException(
                        "pairing_rejected",
                        "Peer rejected pairing: ${result.optString(SecureHandshake.FIELD_REASON, "unspecified")}"
                    )
                }
                verifyPeer(handshake, result, usedStoredSecret = !isNewPairing)
            } else {
                val confirm = readPayload(transport, ProtocolFrameType.PAIR_CONFIRM)
                try {
                    verifyPeer(handshake, confirm, usedStoredSecret = !isNewPairing)
                } catch (exception: NegotiationException) {
                    // Tell the peer why before dropping, so its UI can say
                    // "wrong code" instead of "connection lost".
                    runCatching {
                        write(
                            transport,
                            ProtocolFrameType.PAIR_RESULT,
                            JSONObject()
                                .put(SecureHandshake.FIELD_OK, false)
                                .put(SecureHandshake.FIELD_REASON, exception.failureReason),
                            localName
                        )
                    }
                    throw exception
                }
                write(
                    transport,
                    ProtocolFrameType.PAIR_RESULT,
                    confirmBody(handshake).put(SecureHandshake.FIELD_OK, true),
                    localName
                )
            }

            transport.setReadTimeout(normalReadTimeoutMs)

            // --- 4. short authentication string -----------------------------
            val sas = handshake.shortAuthString()
            // Only asked on a first pairing; a known peer keeps whatever the user
            // decided the first time.
            val sasVerified = existing?.sasVerified
                ?: prompt.confirmShortAuthString(peerDeviceId, sas)

            // --- 5. persist ---------------------------------------------------
            val now = clock()
            if (isNewPairing) {
                val longTerm = handshake.deriveLongTermSecret()
                repository.savePairing(
                    PairingRecord(
                        peerDeviceId = peerDeviceId,
                        longTermSecret = longTerm,
                        peerName = peerName,
                        pairedAtMs = now,
                        lastSeenAtMs = now,
                        sasVerified = sasVerified
                    )
                )
                CryptoPrimitives.wipe(longTerm)
            } else {
                repository.touchPairing(peerDeviceId, now)
            }

            DiagnosticsLogger.log(
                "security",
                "Secure negotiation complete",
                mapOf(
                    "sessionId" to sessionId,
                    "peerDeviceId" to peerDeviceId.shortLabel(),
                    "newPairing" to isNewPairing,
                    "sasVerified" to sasVerified
                )
            )

            return Result(peerDeviceId, peerName, sas, isNewPairing, sasVerified, handshake)
        } catch (exception: NegotiationException) {
            handshake.destroy()
            logFailure(sessionId, exception.failureReason, exception)
            throw exception
        } catch (exception: SecureHandshake.HandshakeException) {
            handshake.destroy()
            logFailure(sessionId, "handshake_invalid", exception)
            throw NegotiationException("handshake_invalid", exception.message ?: "handshake failed", exception)
        } catch (exception: Exception) {
            handshake.destroy()
            logFailure(sessionId, "pairing_io_error", exception)
            throw NegotiationException("pairing_io_error", "Secure negotiation failed", exception)
        }
    }

    private fun promptForCode(
        role: SecureRole,
        peerDeviceId: DeviceId,
        peerName: String
    ): ByteArray {
        // The gateway (initiator) shows the code; the client (responder) types it.
        val code = if (role == SecureRole.INITIATOR) {
            prompt.displayPairingCode(peerDeviceId, peerName)
        } else {
            prompt.requestPairingCode(peerDeviceId, peerName)
                ?: throw NegotiationException("pairing_cancelled", "The user cancelled pairing")
        }
        return code.toBytes()
    }

    private fun confirmBody(handshake: SecureHandshake): JSONObject = JSONObject()
        .put(SecureHandshake.FIELD_MAC, SecureHandshake.encode(handshake.localConfirmation()))

    /**
     * @param usedStoredSecret whether this side authenticated with a stored
     *        long-term secret rather than a freshly typed code. It changes what a
     *        failure *means*: with a typed code the likely cause is a typo, but
     *        between two supposedly-paired devices it means their stored secrets
     *        have diverged, and telling the user "wrong pairing code" when they
     *        never typed one is actively misleading.
     *
     *        The stored pairing is deliberately **not** deleted here. An attacker
     *        inside the Wi-Fi Direct group could otherwise force a legitimate
     *        pairing to be dropped simply by failing confirmation on purpose. The
     *        user is told to re-pair and decides.
     */
    private fun verifyPeer(
        handshake: SecureHandshake,
        body: JSONObject,
        usedStoredSecret: Boolean
    ) {
        val mac = SecureHandshake.decodeOrNull(body.optString(SecureHandshake.FIELD_MAC, null))
            ?: throw NegotiationException("pairing_malformed", "Peer confirmation carried no MAC")
        if (!handshake.verifyPeerConfirmation(mac)) {
            throw if (usedStoredSecret) {
                NegotiationException(
                    REASON_DESYNCHRONISED,
                    "Confirmation failed against a stored pairing. The two devices' " +
                        "stored secrets have diverged; pair again."
                )
            } else {
                NegotiationException(
                    REASON_CODE_MISMATCH,
                    "Peer confirmation did not verify: wrong pairing code or interference"
                )
            }
        }
    }

    private fun write(
        transport: SessionTransport,
        type: ProtocolFrameType,
        body: JSONObject,
        localName: String
    ) {
        transport.writeFrame(
            ProtocolFrame(
                type = type,
                channel = ProtocolChannel.CONTROL,
                metadataJson = body.put(FIELD_NAME, localName).toString()
            )
        )
    }

    private fun readPayload(
        transport: SessionTransport,
        expected: ProtocolFrameType
    ): JSONObject {
        val frame = transport.readFrame()
            ?: throw NegotiationException(
                "pairing_closed",
                "Peer closed the connection while waiting for ${expected.protocolName}"
            )
        if (frame.type != expected) {
            throw NegotiationException(
                "pairing_unexpected_frame",
                "Expected ${expected.protocolName}, received ${frame.type.protocolName}"
            )
        }
        return try {
            JSONObject(frame.metadataJson)
        } catch (exception: org.json.JSONException) {
            throw NegotiationException(
                "pairing_malformed",
                "Malformed ${expected.protocolName} metadata",
                exception
            )
        }
    }

    private fun logFailure(sessionId: String, reason: String, exception: Exception) {
        DiagnosticsLogger.log(
            "security",
            "Secure negotiation failed",
            mapOf(
                "sessionId" to sessionId,
                "failureReason" to reason,
                "errorType" to exception.javaClass.simpleName
            )
        )
    }

    companion object {
        const val FIELD_NAME = "name"

        /**
         * How long the UI may block waiting for a person to read a code off one
         * phone and type it into the other. Used by `MethodChannelPairingPrompt`,
         * which must not invent its own value.
         */
        const val PROMPT_TIMEOUT_MS = 120_000L

        /**
         * Socket read timeout during pairing.
         *
         * **Invariant: this must be comfortably larger than [PROMPT_TIMEOUT_MS].**
         *
         * The responder does not read `pair.confirm` until *after* its code prompt
         * returns, so the initiator — which is already blocked reading
         * `pair.result` — has to outwait the entire prompt. When the two values
         * were equal, a user taking almost the full prompt time produced a race
         * the wrong way round: the responder wrote `pair.result` and **persisted
         * the pairing**, while the initiator timed out a moment later and
         * persisted nothing. The two devices then disagreed about whether they
         * were paired, and every later reconnect failed with a misleading "wrong
         * code" until app data was cleared.
         *
         * `SecureSessionNegotiatorTest` pins the ordering of these constants.
         */
        const val DEFAULT_PAIRING_TIMEOUT_MS = 180_000

        const val DEFAULT_NORMAL_TIMEOUT_MS = 10_000

        /** Confirmation failed while both sides believed they were already paired. */
        const val REASON_DESYNCHRONISED = "pairing_desynchronised"

        const val REASON_CODE_MISMATCH = "pairing_code_mismatch"
    }
}

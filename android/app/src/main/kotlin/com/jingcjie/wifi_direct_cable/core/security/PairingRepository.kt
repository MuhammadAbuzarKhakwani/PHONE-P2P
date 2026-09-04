package com.jingcjie.wifi_direct_cable.core.security

import com.jingcjie.wifi_direct_cable.core.model.DeviceId

/**
 * Storage the pairing flow depends on.
 *
 * Extracted from [PairingStore] purely so [SecureSessionNegotiator] can be driven
 * in a plain JVM unit test. The real implementation needs a `Context`, the
 * Android Keystore, and `SharedPreferences`, none of which exist under a JUnit
 * run, and all of which would otherwise make the negotiation logic — the part
 * most worth testing — untestable.
 */
interface PairingRepository {

    fun localDeviceId(): DeviceId

    fun localDisplayName(): String

    fun findPairing(peerDeviceId: DeviceId): PairingRecord?

    fun savePairing(record: PairingRecord)

    fun touchPairing(peerDeviceId: DeviceId, nowMs: Long = System.currentTimeMillis())
}

/**
 * Supplies the pairing code and reports the short authentication string.
 *
 * Both calls may block while a person reads a screen or types, so the negotiator
 * raises the transport read timeout for the duration of pairing. Implementations
 * bridge to the UI over the method channel.
 */
interface PairingPrompt {

    /**
     * Called on Phone 1 (the gateway), which displays the code.
     *
     * @return the code now shown to the user, which they will type into Phone 2.
     */
    fun displayPairingCode(peerDeviceId: DeviceId, peerName: String): PairingCode

    /**
     * Called on Phone 2 (the client), which asks the user to type the code.
     *
     * @return the typed code, or null if the user cancelled — in which case the
     *         session is abandoned rather than retried, since repeated guesses
     *         against a six-digit code must not be allowed.
     */
    fun requestPairingCode(peerDeviceId: DeviceId, peerName: String): PairingCode?

    /**
     * Both phones show this six-digit value after key agreement so the user can
     * confirm they match. See [PairingCode] for why this is the check that
     * actually defeats a man-in-the-middle.
     *
     * @return true if the user confirmed the values matched. Returning false does
     *         not fail the session — the pairing is stored with
     *         [PairingRecord.sasVerified] false and the UI must present it as
     *         unverified.
     */
    fun confirmShortAuthString(peerDeviceId: DeviceId, sas: String): Boolean

    /**
     * Called once the attempt has finished, successfully or not, so any pairing
     * dialog can be dismissed.
     *
     * Without this a failed pairing would leave the gateway showing its code
     * dialog forever, since nothing else on that side is waiting for a reply.
     * Deliberately carries no peer id: on an early failure there may not be one
     * yet, and the UI only needs to know to close what it has open.
     */
    fun onPairingFinished(paired: Boolean) {}

    /** A no-op prompt used where pairing cannot be driven interactively. */
    companion object {
        val REFUSING: PairingPrompt = object : PairingPrompt {
            override fun displayPairingCode(peerDeviceId: DeviceId, peerName: String): PairingCode =
                PairingCode.random()

            override fun requestPairingCode(peerDeviceId: DeviceId, peerName: String): PairingCode? =
                null

            override fun confirmShortAuthString(peerDeviceId: DeviceId, sas: String): Boolean = false
        }
    }
}

package com.jingcjie.wifi_direct_cable.core.security

import com.jingcjie.wifi_direct_cable.core.model.DeviceId

/**
 * A completed pairing with one peer device.
 *
 * Deliberately minimal, per the brief's "store only the minimum required
 * information". This holds no phone number, no SIM data, no carrier identity, no
 * call history, and no account — only what is needed to recognise the peer again
 * and re-key with it.
 *
 * [longTermSecret] never leaves the device: it is used solely as HKDF input
 * material inside [SecureHandshake], and is stored encrypted under an Android
 * Keystore key by [PairingStore].
 */
data class PairingRecord(
    val peerDeviceId: DeviceId,
    val longTermSecret: ByteArray,
    /** Peer's self-reported display name. Untrusted, for UI only. */
    val peerName: String,
    val pairedAtMs: Long,
    val lastSeenAtMs: Long,
    /**
     * Whether the user confirmed the short authentication string matched on both
     * phones. False means the pairing is only as strong as the typed code — see
     * [PairingCode] — and the UI should say so rather than showing a plain
     * "paired" state.
     */
    val sasVerified: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingRecord) return false
        return peerDeviceId == other.peerDeviceId &&
            longTermSecret.contentEquals(other.longTermSecret) &&
            peerName == other.peerName &&
            pairedAtMs == other.pairedAtMs &&
            lastSeenAtMs == other.lastSeenAtMs &&
            sasVerified == other.sasVerified
    }

    override fun hashCode(): Int {
        var result = peerDeviceId.hashCode()
        result = 31 * result + longTermSecret.contentHashCode()
        result = 31 * result + peerName.hashCode()
        result = 31 * result + pairedAtMs.hashCode()
        result = 31 * result + lastSeenAtMs.hashCode()
        result = 31 * result + sasVerified.hashCode()
        return result
    }

    /** Never log the secret. */
    override fun toString(): String =
        "PairingRecord(peer=${peerDeviceId.shortLabel()}, name=$peerName, " +
            "sasVerified=$sasVerified, pairedAtMs=$pairedAtMs)"
}

package com.jingcjie.wifi_direct_cable.core.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * A stable 128-bit identifier for one installation of the app.
 *
 * This is an app-generated random UUID. It is deliberately NOT derived from any
 * hardware identifier — no IMEI, no MAC, no ANDROID_ID, no SIM serial. Those are
 * either restricted on modern Android, or personal data, or both, and the brief
 * forbids transmitting SIM and carrier identity across the link. A random UUID
 * gives the protocol the "sender device ID" it needs while carrying no
 * information about the user, the SIM, or the hardware.
 *
 * It is reset if app data is cleared, which correctly invalidates existing
 * pairings.
 */
@JvmInline
value class DeviceId(val value: UUID) {

    /** 16-byte big-endian encoding, as it appears on the wire. */
    fun toBytes(): ByteArray = ByteBuffer.allocate(BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .putLong(value.mostSignificantBits)
        .putLong(value.leastSignificantBits)
        .array()

    /** Canonical text form, used for storage keys and diagnostics. */
    override fun toString(): String = value.toString()

    /** Short form for UI and logs, e.g. `a1b2c3d4`. */
    fun shortLabel(): String = value.toString().substringBefore('-')

    companion object {
        const val BYTES = 16

        val UNSPECIFIED = DeviceId(UUID(0L, 0L))

        fun random(): DeviceId = DeviceId(UUID.randomUUID())

        fun fromBytes(bytes: ByteArray, offset: Int = 0): DeviceId {
            require(bytes.size - offset >= BYTES) {
                "need $BYTES bytes for a DeviceId, had ${bytes.size - offset}"
            }
            val buffer = ByteBuffer.wrap(bytes, offset, BYTES).order(ByteOrder.BIG_ENDIAN)
            return DeviceId(UUID(buffer.long, buffer.long))
        }

        /** Parses the canonical text form, or returns null if malformed. */
        fun parseOrNull(text: String?): DeviceId? {
            if (text.isNullOrBlank()) return null
            return try {
                DeviceId(UUID.fromString(text))
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

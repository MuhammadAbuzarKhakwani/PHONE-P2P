package com.jingcjie.wifi_direct_cable.core.security

/**
 * The short numeric code Phone 1 displays and Phone 2 types during first pairing.
 *
 * ## What this code does and does not protect against
 *
 * This is stated plainly because the honest answer is not "it makes pairing
 * secure", and the project brief requires limitations to be surfaced rather than
 * hidden.
 *
 * The code is combined with an ephemeral ECDH exchange (see [SecureHandshake]).
 * That gives:
 *
 * - **Confidentiality against a passive eavesdropper: yes.** Someone sniffing the
 *   Wi-Fi Direct link learns nothing; the session keys come from ECDH.
 * - **Protection against pairing with the wrong nearby device: yes.** A device
 *   that cannot produce the code fails the confirmation step.
 * - **Protection against an active man-in-the-middle: NO, not by itself.**
 *
 * The MITM caveat is a property of the construction, not a bug. An attacker who
 * relays the whole handshake performs its own ECDH with each side, so it learns
 * both shared secrets. It can then take the confirmation MAC it observed and try
 * all [SPACE] candidate codes offline — a million HMACs, which is milliseconds —
 * recover the code, and complete the handshake with both phones.
 *
 * Defeating that requires either a PAKE (SPAKE2 and friends, which bind the code
 * into the group operation so it cannot be brute-forced offline) or an
 * out-of-band comparison the attacker cannot influence.
 *
 * This implementation takes the second route: [SecureHandshake] also derives a
 * **short authentication string** from the completed transcript, which both
 * phones display. Because a MITM necessarily produces a *different* transcript
 * with each side, its two SAS values cannot both match, so a user who compares
 * them detects the attack. That check is what actually closes the MITM hole; the
 * typed code handles device selection and the passive case.
 *
 * See `docs/ANDROID_LIMITATIONS.md` for the user-facing wording.
 */
@JvmInline
value class PairingCode(val digits: String) {

    init {
        require(isValid(digits)) { "pairing code must be exactly $LENGTH digits" }
    }

    /** The bytes mixed into the handshake confirmation. */
    fun toBytes(): ByteArray = digits.toByteArray(Charsets.UTF_8)

    /** `123 456` — grouped for legibility when displayed. */
    fun formatted(): String = "${digits.substring(0, 3)} ${digits.substring(3)}"

    override fun toString(): String = digits

    companion object {
        const val LENGTH = 6

        /** Number of possible codes. 10^6 — see the class comment. */
        const val SPACE = 1_000_000

        /**
         * Generates a uniformly random code from [CryptoPrimitives.randomBytes].
         *
         * Rejection sampling, not `% SPACE`, so every code is equally likely; the
         * modulo shortcut would bias the low end of the range.
         */
        fun random(): PairingCode {
            val limit = Int.MAX_VALUE / SPACE * SPACE
            while (true) {
                val bytes = CryptoPrimitives.randomBytes(4)
                val candidate = ((bytes[0].toInt() and 0x7f) shl 24) or
                    ((bytes[1].toInt() and 0xff) shl 16) or
                    ((bytes[2].toInt() and 0xff) shl 8) or
                    (bytes[3].toInt() and 0xff)
                if (candidate < limit) {
                    return PairingCode((candidate % SPACE).toString().padStart(LENGTH, '0'))
                }
            }
        }

        fun isValid(digits: String?): Boolean =
            digits != null && digits.length == LENGTH && digits.all { it in '0'..'9' }

        /** Parses user input, tolerating spaces and dashes. Null if not a valid code. */
        fun parseOrNull(input: String?): PairingCode? {
            val cleaned = input?.filter(Char::isDigit) ?: return null
            return if (isValid(cleaned)) PairingCode(cleaned) else null
        }
    }
}

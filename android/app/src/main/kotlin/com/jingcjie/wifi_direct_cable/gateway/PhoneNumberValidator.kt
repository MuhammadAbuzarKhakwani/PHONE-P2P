package com.jingcjie.wifi_direct_cable.gateway

/**
 * Validates a number received from the remote client before the gateway dials it.
 *
 * ## Why this is strict
 *
 * The gateway dials numbers that arrive over the network from another device. If
 * it passed those straight to `TelecomManager` or an `ACTION_CALL` intent, a
 * malicious or buggy client could make Phone 1's SIM execute **MMI and USSD
 * codes** rather than place a call. Those are not hypothetical:
 *
 * - `*#06#` — reveals the IMEI
 * - `**21*<number>#` — unconditional call forwarding to an attacker's number
 * - `*#21#`, `##002#` — query or clear forwarding
 * - `*<code>#` sequences — carrier account operations, balance transfers on some
 *   networks
 *
 * Call forwarding is the dangerous one: silently redirecting the victim's
 * incoming calls is a real attack, and it is a single dial away.
 *
 * So this is an **allowlist**, not a blocklist: an optional leading `+`, then
 * digits only. Everything else is rejected — `*`, `#`, `,` and `;` (DTMF pause
 * and wait, which can append a suffix after connection), whitespace-separated
 * second numbers, and `tel:` scheme trickery.
 *
 * Formatting characters a human might type are stripped first, so a client that
 * sends `+1 (555) 123-4567` still works.
 */
object PhoneNumberValidator {

    /** Characters that are cosmetic and safe to remove before validating. */
    private val SEPARATORS = charArrayOf(' ', '-', '(', ')', '.', ' ')

    const val MIN_DIGITS = 2
    const val MAX_DIGITS = 15 // E.164 caps the national + country digits at 15.

    sealed interface Result {
        /** [dialable] is what should be handed to the telephony API. */
        data class Valid(val dialable: String) : Result

        data class Invalid(val reason: String) : Result
    }

    fun validate(raw: String?): Result {
        if (raw.isNullOrBlank()) {
            return Result.Invalid("empty_number")
        }

        // A client must send a bare number, never a URI. Rejecting the scheme
        // outright avoids arguing about how it would be parsed.
        if (raw.contains(':')) {
            return Result.Invalid("scheme_not_allowed")
        }

        val cleaned = buildString {
            for (character in raw) {
                if (character !in SEPARATORS) append(character)
            }
        }

        if (cleaned.isEmpty()) {
            return Result.Invalid("empty_number")
        }

        val hasPlus = cleaned.startsWith('+')
        val digits = if (hasPlus) cleaned.substring(1) else cleaned

        if (digits.isEmpty()) {
            return Result.Invalid("empty_number")
        }

        // The allowlist. Anything that is not a digit here — including '*' and
        // '#' — means this is an MMI/USSD sequence or otherwise not a number.
        for (character in digits) {
            if (character !in '0'..'9') {
                return Result.Invalid(
                    when (character) {
                        '*', '#' -> "mmi_code_not_allowed"
                        ',', ';' -> "dtmf_suffix_not_allowed"
                        '+' -> "misplaced_plus"
                        else -> "illegal_character"
                    }
                )
            }
        }

        if (digits.length < MIN_DIGITS) {
            return Result.Invalid("too_short")
        }
        if (digits.length > MAX_DIGITS) {
            return Result.Invalid("too_long")
        }

        return Result.Valid(if (hasPlus) "+$digits" else digits)
    }

    fun isValid(raw: String?): Boolean = validate(raw) is Result.Valid

    /**
     * Masks a number for logs and diagnostics. Never log a full number: it is
     * personal data, and diagnostics are exportable.
     */
    fun mask(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val digits = raw.filter { it in '0'..'9' }
        if (digits.length <= 4) return "*".repeat(digits.length)
        return "*".repeat(digits.length - 4) + digits.takeLast(4)
    }
}

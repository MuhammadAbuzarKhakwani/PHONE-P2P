package com.jingcjie.wifi_direct_cable.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumberValidatorTest {

    private fun valid(raw: String): String {
        val result = PhoneNumberValidator.validate(raw)
        assertTrue("expected $raw to be valid, got $result", result is PhoneNumberValidator.Result.Valid)
        return (result as PhoneNumberValidator.Result.Valid).dialable
    }

    private fun invalidReason(raw: String?): String {
        val result = PhoneNumberValidator.validate(raw)
        assertTrue("expected $raw to be rejected, got $result", result is PhoneNumberValidator.Result.Invalid)
        return (result as PhoneNumberValidator.Result.Invalid).reason
    }

    // ------------------------------------------------------------- accepted --

    @Test
    fun `accepts a plain national number`() {
        assertEquals("5551234567", valid("5551234567"))
    }

    @Test
    fun `accepts an international number`() {
        assertEquals("+15551234567", valid("+15551234567"))
    }

    @Test
    fun `strips human formatting`() {
        assertEquals("+15551234567", valid("+1 (555) 123-4567"))
        assertEquals("+442071234567", valid("+44 20 7123 4567"))
        assertEquals("5551234567", valid("555.123.4567"))
    }

    @Test
    fun `accepts the length boundaries`() {
        assertEquals("12", valid("12"))
        assertEquals("+123456789012345", valid("+123456789012345")) // 15 digits
    }

    // ---------------------------------------------------- MMI and USSD codes --
    //
    // The reason this validator exists. Each of these, if passed through to the
    // dialer, makes the gateway's own SIM execute a carrier operation rather than
    // place a call. Call forwarding is the dangerous one: it silently redirects
    // the victim's incoming calls.

    @Test
    fun `rejects the IMEI query code`() {
        assertEquals("mmi_code_not_allowed", invalidReason("*#06#"))
    }

    @Test
    fun `rejects call forwarding codes`() {
        assertEquals("mmi_code_not_allowed", invalidReason("**21*5551234567#"))
        assertEquals("mmi_code_not_allowed", invalidReason("*21*5551234567#"))
        assertEquals("mmi_code_not_allowed", invalidReason("##002#"))
        assertEquals("mmi_code_not_allowed", invalidReason("*#21#"))
    }

    @Test
    fun `rejects a hash appended to an otherwise valid number`() {
        // The sneaky case: looks like a normal number until the trailing MMI.
        assertEquals("mmi_code_not_allowed", invalidReason("5551234567#"))
    }

    @Test
    fun `rejects DTMF pause and wait suffixes`() {
        assertEquals("dtmf_suffix_not_allowed", invalidReason("5551234567,123"))
        assertEquals("dtmf_suffix_not_allowed", invalidReason("5551234567;123"))
    }

    @Test
    fun `rejects a tel scheme`() {
        assertEquals("scheme_not_allowed", invalidReason("tel:5551234567"))
        assertEquals("scheme_not_allowed", invalidReason("sip:someone@example.com"))
    }

    // ------------------------------------------------------------- malformed --

    @Test
    fun `rejects empty input`() {
        assertEquals("empty_number", invalidReason(null))
        assertEquals("empty_number", invalidReason(""))
        assertEquals("empty_number", invalidReason("   "))
        assertEquals("empty_number", invalidReason("+"))
    }

    @Test
    fun `rejects letters`() {
        assertEquals("illegal_character", invalidReason("555CALLNOW"))
    }

    @Test
    fun `rejects a misplaced plus`() {
        assertEquals("misplaced_plus", invalidReason("555+1234"))
    }

    @Test
    fun `rejects numbers outside the length bounds`() {
        assertEquals("too_short", invalidReason("7"))
        assertEquals("too_long", invalidReason("1234567890123456"))
    }

    @Test
    fun `isValid agrees with validate`() {
        assertTrue(PhoneNumberValidator.isValid("+15551234567"))
        assertFalse(PhoneNumberValidator.isValid("*#06#"))
    }

    // ------------------------------------------------------------- masking --

    @Test
    fun `mask keeps only the last four digits`() {
        // Diagnostics are exportable, so a full number must never reach a log.
        assertEquals("*******4567", PhoneNumberValidator.mask("+15551234567"))
        assertEquals("***", PhoneNumberValidator.mask("123"))
        assertEquals("", PhoneNumberValidator.mask(null))
    }

    @Test
    fun `mask does not leak the leading digits`() {
        assertFalse(PhoneNumberValidator.mask("+15551234567").contains("555"))
    }
}

package com.jingcjie.wifi_direct_cable.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {

    @Test
    fun `generated codes are always six digits`() {
        repeat(5_000) {
            val code = PairingCode.random()
            assertEquals(PairingCode.LENGTH, code.digits.length)
            assertTrue(code.digits.all { digit -> digit in '0'..'9' })
        }
    }

    @Test
    fun `generated codes include leading-zero values`() {
        // A code built with plain Int.toString() would render 42 as "42" rather
        // than "000042" and the two phones would disagree on the bytes fed into
        // the handshake. Generating enough codes makes a missing pad show up.
        val codes = (1..20_000).map { PairingCode.random().digits }
        assertTrue(
            "expected at least one code starting with 0",
            codes.any { it.startsWith("0") }
        )
        assertTrue(codes.all { it.length == PairingCode.LENGTH })
    }

    @Test
    fun `generation covers a wide spread of values`() {
        // Guards against a generator collapsing onto a small range.
        val distinct = (1..2_000).map { PairingCode.random().digits }.toSet()
        assertTrue("expected mostly distinct codes, got ${distinct.size}", distinct.size > 1_900)
    }

    @Test
    fun `formatted output groups the digits`() {
        assertEquals("123 456", PairingCode("123456").formatted())
    }

    @Test
    fun `parses input with spaces and dashes`() {
        assertEquals(PairingCode("123456"), PairingCode.parseOrNull("123 456"))
        assertEquals(PairingCode("123456"), PairingCode.parseOrNull("123-456"))
        assertEquals(PairingCode("123456"), PairingCode.parseOrNull(" 1 2 3 4 5 6 "))
    }

    @Test
    fun `rejects malformed input`() {
        assertNull(PairingCode.parseOrNull(null))
        assertNull(PairingCode.parseOrNull(""))
        assertNull(PairingCode.parseOrNull("12345"))
        assertNull(PairingCode.parseOrNull("1234567"))
        assertNull(PairingCode.parseOrNull("abcdef"))
    }

    @Test
    fun `accepts a valid code`() {
        assertNotNull(PairingCode.parseOrNull("000000"))
        assertTrue(PairingCode.isValid("482913"))
        assertFalse(PairingCode.isValid("48291"))
        assertFalse(PairingCode.isValid(null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects a wrong-length code`() {
        PairingCode("12345")
    }

    @Test
    fun `code bytes are the digits as ascii`() {
        assertEquals("482913", String(PairingCode("482913").toBytes(), Charsets.UTF_8))
    }
}

package com.jingcjie.wifi_direct_cable.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NonceGeneratorTest {

    @Test
    fun `nonces are the size GCM expects`() {
        assertEquals(CryptoPrimitives.GCM_NONCE_BYTES, NonceGenerator().next().size)
    }

    @Test
    fun `nonces never repeat within a generator`() {
        val generator = NonceGenerator()
        val seen = HashSet<String>()
        repeat(100_000) {
            val nonce = generator.next().joinToString("") { byte -> "%02x".format(byte) }
            // Nonce reuse under a fixed GCM key is catastrophic, so this is the
            // single most important property in the security layer.
            assertEquals("nonce $nonce was issued twice", true, seen.add(nonce))
        }
    }

    @Test
    fun `the counter advances by one per nonce`() {
        val prefix = byteArrayOf(1, 2, 3, 4)
        val generator = NonceGenerator(prefix)

        assertArrayEquals(NonceGenerator.compose(prefix, 0), generator.next())
        assertArrayEquals(NonceGenerator.compose(prefix, 1), generator.next())
        assertArrayEquals(NonceGenerator.compose(prefix, 2), generator.next())
        assertEquals(3L, generator.issued())
    }

    @Test
    fun `independent generators draw different prefixes`() {
        // Not a guarantee, but a 1-in-4-billion collision would be a red flag
        // worth investigating rather than a flake to ignore.
        assertNotEquals(
            NonceGenerator().prefix().toList(),
            NonceGenerator().prefix().toList()
        )
    }

    @Test
    fun `the prefix occupies the leading bytes`() {
        val prefix = byteArrayOf(0x0a, 0x0b, 0x0c, 0x0d)
        val nonce = NonceGenerator(prefix).next()
        assertArrayEquals(prefix, nonce.copyOfRange(0, NonceGenerator.PREFIX_BYTES))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a prefix of the wrong length`() {
        NonceGenerator(byteArrayOf(1, 2, 3))
    }
}

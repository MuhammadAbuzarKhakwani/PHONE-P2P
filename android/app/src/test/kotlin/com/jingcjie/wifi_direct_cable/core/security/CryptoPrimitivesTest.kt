package com.jingcjie.wifi_direct_cable.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class CryptoPrimitivesTest {

    // ------------------------------------------------------------------ HKDF --
    //
    // RFC 5869 Appendix A test vectors. These matter more than any round-trip
    // test: a hand-written HKDF that is subtly wrong (mis-ordered HMAC inputs, a
    // counter starting at 0, a missing previous-block feed) still round-trips
    // happily with itself, and would only be caught when a second implementation
    // has to agree with it. Pinning the RFC vectors catches that here.

    @Test
    fun `hkdf matches RFC 5869 test case 1`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")

        val prk = CryptoPrimitives.hkdfExtract(salt, ikm)
        assertEquals(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            prk.toHex()
        )

        val okm = CryptoPrimitives.hkdf(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            okm.toHex()
        )
    }

    @Test
    fun `hkdf matches RFC 5869 test case 3 with empty salt and info`() {
        val ikm = ByteArray(22) { 0x0b }

        val prk = CryptoPrimitives.hkdfExtract(ByteArray(0), ikm)
        assertEquals(
            "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04",
            prk.toHex()
        )

        val okm = CryptoPrimitives.hkdf(ikm, ByteArray(0), ByteArray(0), 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8",
            okm.toHex()
        )
    }

    @Test
    fun `hkdf produces independent keys for different info labels`() {
        val ikm = CryptoPrimitives.randomBytes(32)
        val salt = CryptoPrimitives.randomBytes(32)

        val a = CryptoPrimitives.hkdf(ikm, salt, "direction-a".toByteArray(), 32)
        val b = CryptoPrimitives.hkdf(ikm, salt, "direction-b".toByteArray(), 32)

        assertFalse("different labels must not collide", a.contentEquals(b))
    }

    @Test
    fun `hkdf expands across multiple hash blocks`() {
        // 100 bytes needs four SHA-256 blocks, exercising the counter loop.
        val okm = CryptoPrimitives.hkdf(ByteArray(32) { 1 }, ByteArray(0), ByteArray(0), 100)
        assertEquals(100, okm.size)
    }

    // ------------------------------------------------------------------ AEAD --

    @Test
    fun `aead round trip returns the original plaintext`() {
        val key = CryptoPrimitives.randomBytes(CryptoPrimitives.AES_KEY_BYTES)
        val nonce = CryptoPrimitives.randomBytes(CryptoPrimitives.GCM_NONCE_BYTES)
        val aad = "header".toByteArray()
        val plaintext = "the quick brown fox".toByteArray()

        val sealed = CryptoPrimitives.aeadSeal(key, nonce, aad, plaintext)
        assertEquals(plaintext.size + CryptoPrimitives.GCM_TAG_BYTES, sealed.size)

        assertArrayEquals(plaintext, CryptoPrimitives.aeadOpen(key, nonce, aad, sealed))
    }

    @Test(expected = AEADBadTagException::class)
    fun `aead rejects a tampered ciphertext`() {
        val key = CryptoPrimitives.randomBytes(CryptoPrimitives.AES_KEY_BYTES)
        val nonce = CryptoPrimitives.randomBytes(CryptoPrimitives.GCM_NONCE_BYTES)
        val sealed = CryptoPrimitives.aeadSeal(key, nonce, ByteArray(0), "payload".toByteArray())

        sealed[0] = (sealed[0].toInt() xor 0x01).toByte()

        CryptoPrimitives.aeadOpen(key, nonce, ByteArray(0), sealed)
    }

    @Test(expected = AEADBadTagException::class)
    fun `aead rejects modified associated data`() {
        val key = CryptoPrimitives.randomBytes(CryptoPrimitives.AES_KEY_BYTES)
        val nonce = CryptoPrimitives.randomBytes(CryptoPrimitives.GCM_NONCE_BYTES)
        val sealed = CryptoPrimitives.aeadSeal(key, nonce, "real".toByteArray(), "x".toByteArray())

        CryptoPrimitives.aeadOpen(key, nonce, "forged".toByteArray(), sealed)
    }

    @Test(expected = AEADBadTagException::class)
    fun `aead rejects the wrong key`() {
        val nonce = CryptoPrimitives.randomBytes(CryptoPrimitives.GCM_NONCE_BYTES)
        val sealed = CryptoPrimitives.aeadSeal(
            CryptoPrimitives.randomBytes(CryptoPrimitives.AES_KEY_BYTES),
            nonce,
            ByteArray(0),
            "x".toByteArray()
        )

        CryptoPrimitives.aeadOpen(
            CryptoPrimitives.randomBytes(CryptoPrimitives.AES_KEY_BYTES),
            nonce,
            ByteArray(0),
            sealed
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `aead rejects a short key`() {
        CryptoPrimitives.aeadSeal(ByteArray(16), ByteArray(12), ByteArray(0), ByteArray(0))
    }

    // ------------------------------------------------------------------ misc --

    @Test
    fun `constant time equals behaves like content equality`() {
        val a = byteArrayOf(1, 2, 3)
        assertTrue(CryptoPrimitives.constantTimeEquals(a, byteArrayOf(1, 2, 3)))
        assertFalse(CryptoPrimitives.constantTimeEquals(a, byteArrayOf(1, 2, 4)))
        assertFalse(CryptoPrimitives.constantTimeEquals(a, byteArrayOf(1, 2)))
    }

    @Test
    fun `random bytes are not repeated`() {
        assertNotEquals(
            CryptoPrimitives.randomBytes(32).toHex(),
            CryptoPrimitives.randomBytes(32).toHex()
        )
    }

    @Test
    fun `wipe zeroes the buffer`() {
        val secret = CryptoPrimitives.randomBytes(32)
        CryptoPrimitives.wipe(secret)
        assertArrayEquals(ByteArray(32), secret)
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) {
        value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

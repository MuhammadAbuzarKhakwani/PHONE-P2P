package com.jingcjie.wifi_direct_cable.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic primitives for the WDCable secure channel.
 *
 * Deliberately built on the Java/Android platform providers only. No third-party
 * crypto dependency is introduced, so this compiles against a stock Android SDK
 * and there is no supply-chain surface beyond the platform itself.
 *
 * Algorithms:
 *  - AEAD: AES-256-GCM with a 96-bit nonce and a 128-bit tag.
 *  - KDF:  HKDF-SHA256 (RFC 5869), implemented here because Android exposes no
 *          public HKDF before `javax.crypto.KDF` (Java 24, not on Android).
 *  - MAC:  HMAC-SHA256.
 *
 * Nothing in this file persists keys. Storage is [PairingStore]'s job.
 */
object CryptoPrimitives {

    const val AES_KEY_BYTES = 32
    const val GCM_NONCE_BYTES = 12
    const val GCM_TAG_BYTES = 16
    private const val GCM_TAG_BITS = GCM_TAG_BYTES * 8

    private const val HMAC_SHA256 = "HmacSHA256"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val AES = "AES"

    private const val SHA256_OUTPUT_BYTES = 32

    private val secureRandom = SecureRandom()

    fun randomBytes(length: Int): ByteArray {
        require(length >= 0) { "length must not be negative" }
        return ByteArray(length).also(secureRandom::nextBytes)
    }

    // ---------------------------------------------------------------- HKDF --

    /**
     * HKDF-Extract (RFC 5869 §2.2). Returns a pseudo-random key of 32 bytes.
     */
    fun hkdfExtract(salt: ByteArray, inputKeyMaterial: ByteArray): ByteArray {
        // RFC 5869: if salt is not provided, it is set to a string of HashLen zeros.
        val effectiveSalt = if (salt.isEmpty()) ByteArray(SHA256_OUTPUT_BYTES) else salt
        return hmacSha256(effectiveSalt, inputKeyMaterial)
    }

    /**
     * HKDF-Expand (RFC 5869 §2.3).
     */
    fun hkdfExpand(pseudoRandomKey: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        require(outputLength >= 0) { "outputLength must not be negative" }
        require(outputLength <= 255 * SHA256_OUTPUT_BYTES) {
            "HKDF-Expand cannot produce more than ${255 * SHA256_OUTPUT_BYTES} bytes"
        }

        val output = ByteArray(outputLength)
        var previousBlock = ByteArray(0)
        var written = 0
        var counter = 1

        while (written < outputLength) {
            val mac = Mac.getInstance(HMAC_SHA256)
            mac.init(SecretKeySpec(pseudoRandomKey, HMAC_SHA256))
            mac.update(previousBlock)
            mac.update(info)
            mac.update(counter.toByte())
            previousBlock = mac.doFinal()

            val chunk = minOf(previousBlock.size, outputLength - written)
            System.arraycopy(previousBlock, 0, output, written, chunk)
            written += chunk
            counter++
        }

        return output
    }

    /**
     * Full HKDF (extract-then-expand).
     */
    fun hkdf(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int
    ): ByteArray = hkdfExpand(hkdfExtract(salt, inputKeyMaterial), info, outputLength)

    // ----------------------------------------------------------------- MAC --

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(key, HMAC_SHA256))
        return mac.doFinal(data)
    }

    /**
     * Constant-time equality. [MessageDigest.isEqual] is constant-time on
     * Android and on any modern JDK; using it avoids hand-rolling the loop.
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    // ---------------------------------------------------------------- AEAD --

    /**
     * AES-256-GCM seal. Returns ciphertext with the 16-byte tag appended, which
     * is what the JCE provider produces natively.
     *
     * @param nonce MUST be unique for the lifetime of [key]. See [NonceGenerator].
     */
    fun aeadSeal(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        plaintext: ByteArray
    ): ByteArray {
        requireKey(key)
        requireNonce(nonce)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, AES),
            GCMParameterSpec(GCM_TAG_BITS, nonce)
        )
        cipher.updateAAD(associatedData)
        return cipher.doFinal(plaintext)
    }

    /**
     * AES-256-GCM open. [sealed] is ciphertext with the tag appended.
     *
     * @throws javax.crypto.AEADBadTagException if authentication fails. Callers
     *         must treat that as a hostile or corrupt frame and drop the session.
     */
    fun aeadOpen(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        sealed: ByteArray
    ): ByteArray {
        requireKey(key)
        requireNonce(nonce)
        require(sealed.size >= GCM_TAG_BYTES) { "sealed payload is shorter than the GCM tag" }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, AES),
            GCMParameterSpec(GCM_TAG_BITS, nonce)
        )
        cipher.updateAAD(associatedData)
        return cipher.doFinal(sealed)
    }

    /**
     * Overwrites a key buffer in place. Best-effort: the JVM may still hold
     * copies, but it shortens the window in which a key sits in a live heap.
     */
    fun wipe(secret: ByteArray) = secret.fill(0)

    private fun requireKey(key: ByteArray) =
        require(key.size == AES_KEY_BYTES) { "AES-256 key must be $AES_KEY_BYTES bytes, was ${key.size}" }

    private fun requireNonce(nonce: ByteArray) =
        require(nonce.size == GCM_NONCE_BYTES) { "GCM nonce must be $GCM_NONCE_BYTES bytes, was ${nonce.size}" }
}

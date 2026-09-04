package com.jingcjie.wifi_direct_cable.core.security

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * Produces unique 96-bit GCM nonces for one direction of one session.
 *
 * Nonce reuse under a fixed AES-GCM key is catastrophic — it leaks the XOR of
 * two plaintexts and, worse, allows forgery by recovering the GHASH authentication
 * key. So this deliberately does NOT use random nonces: at 96 bits, birthday
 * collisions become a real concern in a long-lived audio stream sending 50
 * packets/second.
 *
 * Layout (12 bytes):
 *
 * ```
 *  0        4                                   12
 *  +--------+-----------------------------------+
 *  | prefix |            counter (BE)           |
 *  +--------+-----------------------------------+
 * ```
 *
 * The 4-byte prefix is random per generator instance and the 8-byte counter is
 * strictly increasing, so a nonce can only repeat if the same key is used by two
 * generators that also drew the same prefix. Each session derives fresh keys
 * (see [SessionKeys]), and each direction gets its own key, so that cannot happen
 * within a session.
 *
 * The counter is hard-capped: exhausting it throws rather than wrapping to a
 * value already used.
 */
class NonceGenerator(prefix: ByteArray = CryptoPrimitives.randomBytes(PREFIX_BYTES)) {

    init {
        require(prefix.size == PREFIX_BYTES) {
            "nonce prefix must be $PREFIX_BYTES bytes, was ${prefix.size}"
        }
    }

    private val prefix = prefix.copyOf()
    private val counter = AtomicLong(0)

    /** The random prefix, exposed so a peer can be told how to reconstruct nonces. */
    fun prefix(): ByteArray = prefix.copyOf()

    /** Number of nonces issued so far. */
    fun issued(): Long = counter.get()

    /**
     * Returns the next unique nonce.
     *
     * @throws IllegalStateException if the counter space is exhausted. In practice
     *         unreachable (2^63 frames), but failing loudly beats silent reuse.
     */
    fun next(): ByteArray {
        val value = counter.getAndIncrement()
        if (value == Long.MAX_VALUE) {
            throw IllegalStateException("GCM nonce counter exhausted; the session must be rekeyed")
        }
        return compose(prefix, value)
    }

    companion object {
        const val PREFIX_BYTES = 4
        const val COUNTER_BYTES = 8

        /** Rebuilds the nonce for [counterValue], for verification and tests. */
        fun compose(prefix: ByteArray, counterValue: Long): ByteArray {
            require(prefix.size == PREFIX_BYTES) {
                "nonce prefix must be $PREFIX_BYTES bytes, was ${prefix.size}"
            }
            return ByteBuffer.allocate(CryptoPrimitives.GCM_NONCE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .put(prefix)
                .putLong(counterValue)
                .array()
        }
    }
}

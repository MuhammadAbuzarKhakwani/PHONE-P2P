package com.jingcjie.wifi_direct_cable.core.security

/**
 * Sliding-window duplicate and replay detector over frame sequence numbers.
 *
 * This is the RFC 6479 / IPsec-style anti-replay bitmap. It serves two of the
 * brief's requirements at once:
 *  - Phase 12 "duplicate packet detection"
 *  - Phase 13 "replay protection"
 *
 * A pure "sequence must increase" rule is not usable here because the transport
 * is not strictly ordered in every case, and because dropping legitimately
 * reordered frames would be worse than the problem being solved. The window
 * accepts anything inside [highest - WINDOW_SIZE, highest] that has not already
 * been seen, and rejects everything older or already delivered.
 *
 * Not thread-safe by itself; callers hold one instance per inbound channel and
 * call it from that channel's single reader thread. [SecureChannel] does exactly
 * that.
 */
class ReplayWindow(val windowSize: Int = DEFAULT_WINDOW_SIZE) {

    init {
        require(windowSize > 0 && windowSize % BITS_PER_WORD == 0) {
            "windowSize must be a positive multiple of $BITS_PER_WORD, was $windowSize"
        }
    }

    private val bitmap = LongArray(windowSize / BITS_PER_WORD)

    /** Highest sequence number accepted so far; -1 before the first frame. */
    var highest: Long = -1L
        private set

    private var acceptedCount: Long = 0
    private var duplicateCount: Long = 0
    private var tooOldCount: Long = 0

    fun acceptedCount(): Long = acceptedCount
    fun duplicateCount(): Long = duplicateCount
    fun tooOldCount(): Long = tooOldCount

    /**
     * Records [sequenceNumber] and reports whether it is fresh.
     *
     * @return true if the frame is new and should be processed; false if it is a
     *         duplicate or has fallen out of the window (i.e. drop it).
     */
    fun accept(sequenceNumber: Long): Boolean {
        if (sequenceNumber < 0) {
            tooOldCount++
            return false
        }

        if (sequenceNumber > highest) {
            slideTo(sequenceNumber)
            setBit(sequenceNumber)
            highest = sequenceNumber
            acceptedCount++
            return true
        }

        // sequenceNumber <= highest: inside the window, or too old.
        if (highest - sequenceNumber >= windowSize) {
            tooOldCount++
            return false
        }

        if (testBit(sequenceNumber)) {
            duplicateCount++
            return false
        }

        setBit(sequenceNumber)
        acceptedCount++
        return true
    }

    /** Reports whether [sequenceNumber] would be accepted, without recording it. */
    fun wouldAccept(sequenceNumber: Long): Boolean {
        if (sequenceNumber < 0) return false
        if (sequenceNumber > highest) return true
        if (highest - sequenceNumber >= windowSize) return false
        return !testBit(sequenceNumber)
    }

    fun reset() {
        bitmap.fill(0L)
        highest = -1L
        acceptedCount = 0
        duplicateCount = 0
        tooOldCount = 0
    }

    /**
     * Clears the bits for slots between the old head and [newHighest], which are
     * about to be reused by the circular bitmap and would otherwise still carry
     * stale "seen" marks from an earlier lap.
     */
    private fun slideTo(newHighest: Long) {
        val advance = newHighest - highest
        if (advance >= windowSize) {
            bitmap.fill(0L)
            return
        }
        var slot = highest + 1
        while (slot <= newHighest) {
            clearBit(slot)
            slot++
        }
    }

    private fun index(sequenceNumber: Long): Int =
        ((sequenceNumber % windowSize).toInt() + windowSize) % windowSize

    private fun testBit(sequenceNumber: Long): Boolean {
        val bit = index(sequenceNumber)
        return bitmap[bit / BITS_PER_WORD] and (1L shl (bit % BITS_PER_WORD)) != 0L
    }

    private fun setBit(sequenceNumber: Long) {
        val bit = index(sequenceNumber)
        bitmap[bit / BITS_PER_WORD] = bitmap[bit / BITS_PER_WORD] or (1L shl (bit % BITS_PER_WORD))
    }

    private fun clearBit(sequenceNumber: Long) {
        val bit = index(sequenceNumber)
        bitmap[bit / BITS_PER_WORD] = bitmap[bit / BITS_PER_WORD] and (1L shl (bit % BITS_PER_WORD)).inv()
    }

    companion object {
        const val BITS_PER_WORD = 64
        const val DEFAULT_WINDOW_SIZE = 1024
    }
}

package com.jingcjie.wifi_direct_cable.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayWindowTest {

    @Test
    fun `accepts a strictly increasing sequence`() {
        val window = ReplayWindow()
        for (sequence in 0L until 5000L) {
            assertTrue("sequence $sequence should be accepted", window.accept(sequence))
        }
        assertEquals(5000L, window.acceptedCount())
        assertEquals(0L, window.duplicateCount())
    }

    @Test
    fun `rejects an immediate duplicate`() {
        val window = ReplayWindow()
        assertTrue(window.accept(0))
        assertFalse(window.accept(0))
        assertEquals(1L, window.duplicateCount())
    }

    @Test
    fun `accepts frames reordered inside the window`() {
        val window = ReplayWindow()
        assertTrue(window.accept(10))
        // Everything below the head but inside the window is still fresh.
        assertTrue(window.accept(7))
        assertTrue(window.accept(9))
        assertTrue(window.accept(8))
        // ...but only once each.
        assertFalse(window.accept(9))
        assertEquals(1L, window.duplicateCount())
    }

    @Test
    fun `rejects a frame that has fallen out of the window`() {
        val window = ReplayWindow(windowSize = 128)
        assertTrue(window.accept(0))
        assertTrue(window.accept(500))
        // 0 is now 500 behind the head, far outside a 128-wide window.
        assertFalse(window.accept(0))
        assertEquals(1L, window.tooOldCount())
    }

    @Test
    fun `accepts the oldest sequence still inside the window`() {
        val window = ReplayWindow(windowSize = 128)
        assertTrue(window.accept(200))
        // highest - seq == 127, which is < windowSize, so still acceptable.
        assertTrue(window.accept(73))
        // highest - seq == 128, exactly out of range.
        assertFalse(window.accept(72))
    }

    @Test
    fun `a large jump forward clears stale bits rather than reporting duplicates`() {
        val window = ReplayWindow(windowSize = 128)
        assertTrue(window.accept(5))
        // Jumping more than a full window must wipe the bitmap. If it did not,
        // sequence 133 would alias onto slot 5 and be misreported as a duplicate.
        assertTrue(window.accept(1000))
        assertTrue(window.accept(1000 - 127))
        assertEquals(0L, window.duplicateCount())
    }

    @Test
    fun `a small jump forward clears only the slots it passes over`() {
        val window = ReplayWindow(windowSize = 128)
        assertTrue(window.accept(10))
        assertTrue(window.accept(20))
        // Slot 20 % 128 == 20 was just used; advancing past it and lapping the
        // bitmap must not leave the bit set.
        assertTrue(window.accept(148)) // 148 % 128 == 20
        assertEquals(0L, window.duplicateCount())
    }

    @Test
    fun `rejects a negative sequence number`() {
        assertFalse(ReplayWindow().accept(-1))
    }

    @Test
    fun `wouldAccept does not mutate state`() {
        val window = ReplayWindow()
        assertTrue(window.wouldAccept(42))
        assertTrue(window.wouldAccept(42))
        assertTrue(window.accept(42))
        assertFalse(window.wouldAccept(42))
    }

    @Test
    fun `reset clears all history`() {
        val window = ReplayWindow()
        window.accept(100)
        window.reset()
        assertEquals(-1L, window.highest)
        assertTrue(window.accept(100))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a window size that is not a multiple of the word size`() {
        ReplayWindow(windowSize = 100)
    }
}

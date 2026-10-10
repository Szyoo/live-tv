package xyz.szyyw.livetv.logic

import org.junit.Assert.*
import org.junit.Test

class ChannelNumberInputTest {
    @Test fun accumulatesAndShowsSpaced() {
        val i = ChannelNumberInput()
        assertFalse(i.active)
        assertEquals("1", i.digit(1, 0))
        assertEquals("1 2", i.digit(2, 100))
        assertTrue(i.active)
    }
    @Test fun commitsAfterTimeoutOnly() {
        val i = ChannelNumberInput(1500)
        i.digit(1, 0); i.digit(2, 1000)
        assertNull(i.poll(2400))      // 距最后一次输入 1400ms
        assertEquals(12, i.poll(2500)) // 1500ms
        assertFalse(i.active)
        assertNull(i.poll(9999))
    }
    @Test fun confirmCommitsImmediately() {
        val i = ChannelNumberInput()
        i.digit(7, 0)
        assertEquals(7, i.confirm())
        assertNull(i.confirm())
    }
    @Test fun leadingZeros() {
        val i = ChannelNumberInput()
        i.digit(0, 0); i.digit(1, 1); i.digit(2, 2)
        assertEquals(12, i.confirm())
    }
    @Test fun zeroAloneIsZeroAndOverflowRestarts() {
        val i = ChannelNumberInput(maxDigits = 3)
        i.digit(0, 0)
        assertEquals(0, i.confirm())
        i.digit(1, 0); i.digit(2, 0); i.digit(3, 0)
        assertEquals("4", i.digit(4, 0))
        assertEquals(4, i.confirm())
    }
    @Test fun cancelClears() {
        val i = ChannelNumberInput()
        i.digit(5, 0); i.cancel()
        assertFalse(i.active)
        assertNull(i.confirm())
    }
}

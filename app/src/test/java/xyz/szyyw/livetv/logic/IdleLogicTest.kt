package xyz.szyyw.livetv.logic

import org.junit.Assert.*
import org.junit.Test

class IdleLogicTest {
    private val h = IdleLogic.HOUR_MS

    @Test fun thresholds() {
        assertEquals(5 * h, IdleLogic.thresholdMs(IdleLogic.DEFAULT_HOURS))
        assertEquals(0L, IdleLogic.thresholdMs(0))
        assertEquals(30_000L, IdleLogic.thresholdMs(5, 30_000))
        assertEquals(30_000L, IdleLogic.thresholdMs(0, 30_000))
    }
    @Test fun remaining() {
        assertEquals(2 * h - 1000, IdleLogic.remainingMs(1000, 0, 2 * h))
        assertEquals(0L, IdleLogic.remainingMs(2 * h, 0, 2 * h))
        assertEquals(0L, IdleLogic.remainingMs(3 * h, 0, 2 * h))
        assertEquals(Long.MAX_VALUE, IdleLogic.remainingMs(9 * h, 0, 0))
    }
    @Test fun keyPressResets() {
        val th = 2 * h
        assertTrue(IdleLogic.isIdle(th, 0, th))
        assertFalse(IdleLogic.isIdle(th, 1, th))          // 刚好差 1ms：没到点
        assertFalse(IdleLogic.isIdle(th + 499, 500, th))  // 按键重置了起点
        assertTrue(IdleLogic.isIdle(th + 500, 500, th))
    }
    @Test fun offNeverIdle() { assertFalse(IdleLogic.isIdle(100 * h, 0, 0)) }
    @Test fun clockGoingBackwardsIsTreatedAsFreshKey() {
        assertEquals(h, IdleLogic.remainingMs(10, 1000, h))
        assertFalse(IdleLogic.isIdle(10, 1000, h))
    }
    @Test fun labels() {
        assertEquals("关", IdleLogic.hoursLabel(0))
        assertEquals("5 小时", IdleLogic.hoursLabel(5))
        assertEquals("5 小时", IdleLogic.durationLabel(5 * h))
        assertEquals("2 分钟", IdleLogic.durationLabel(120_000))
        assertEquals("30 秒", IdleLogic.durationLabel(30_000))
        assertEquals(listOf(0, 2, 3, 5, 8), IdleLogic.HOUR_OPTIONS)
        assertEquals(8, Settings.cycle(IdleLogic.HOUR_OPTIONS, 5, +1))
        assertEquals(0, Settings.cycle(IdleLogic.HOUR_OPTIONS, 8, +1))
    }
}

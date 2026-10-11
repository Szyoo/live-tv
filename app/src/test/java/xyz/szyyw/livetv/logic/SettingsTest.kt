package xyz.szyyw.livetv.logic

import org.junit.Assert.*
import org.junit.Test

class SettingsTest {
    @Test fun cycleWrapsBothWays() {
        val o = Settings.STALL_OPTIONS
        assertEquals(20, Settings.cycle(o, 12, +1))
        assertEquals(8, Settings.cycle(o, 30, +1))
        assertEquals(30, Settings.cycle(o, 8, -1))
        assertEquals(12, Settings.cycle(o, 999, +1)) // 不在列表里：从第一项起算
    }
    @Test fun enumCycle() {
        assertEquals(ResizeOption.ZOOM, Settings.cycle(ResizeOption.FIT, +1))
        assertEquals(ResizeOption.FIT, Settings.cycle(ResizeOption.FILL, +1))
        assertEquals(ResizeOption.FILL, Settings.cycle(ResizeOption.FIT, -1))
    }
    @Test fun parseFallsBackToDefault() {
        assertEquals(ExitMode.SINGLE_BACK, Settings.parse("SINGLE_BACK", ExitMode.DOUBLE_BACK))
        assertEquals(ExitMode.DOUBLE_BACK, Settings.parse("nope", ExitMode.DOUBLE_BACK))
        assertEquals(ExitMode.DOUBLE_BACK, Settings.parse(null, ExitMode.DOUBLE_BACK))
    }
    @Test fun defaultsMatchOldBehaviour() {
        val v = VerticalMode.UP_PREV; val h = HorizontalMode.LINE
        assertEquals(KeyAction(channelDelta = -1), Settings.mapDpad(Dir.UP, v, h))
        assertEquals(KeyAction(channelDelta = +1), Settings.mapDpad(Dir.DOWN, v, h))
        assertEquals(KeyAction(lineDelta = -1), Settings.mapDpad(Dir.LEFT, v, h))
        assertEquals(KeyAction(lineDelta = +1), Settings.mapDpad(Dir.RIGHT, v, h))
    }
    @Test fun swappedVerticalAndChannelHorizontal() {
        assertEquals(KeyAction(channelDelta = +1), Settings.mapDpad(Dir.UP, VerticalMode.UP_NEXT, HorizontalMode.LINE))
        assertEquals(KeyAction(channelDelta = -1), Settings.mapDpad(Dir.DOWN, VerticalMode.UP_NEXT, HorizontalMode.LINE))
        assertEquals(KeyAction(channelDelta = -1), Settings.mapDpad(Dir.LEFT, VerticalMode.UP_PREV, HorizontalMode.CHANNEL))
        assertEquals(KeyAction(channelDelta = +1), Settings.mapDpad(Dir.RIGHT, VerticalMode.UP_PREV, HorizontalMode.CHANNEL))
    }
    @Test fun labels() {
        assertEquals("一直显示", Settings.infoLabel(0))
        assertEquals("10 秒", Settings.infoLabel(10))
    }
}

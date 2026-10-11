package xyz.szyyw.livetv.logic

import org.junit.Assert.*
import org.junit.Test
import xyz.szyyw.livetv.data.PlaylistLoader
import java.util.TimeZone

class PlaylistSourceTest {
    private val stamp = FileStamp(1_700_000_000_000L, 100)

    @Test fun customUrlWins() {
        assertEquals(SourceKind.CUSTOM, PlaylistSource.choose("http://example.com/a.m3u", stamp))
        assertEquals(SourceKind.CUSTOM, PlaylistSource.choose("http://example.com/a.m3u", null))
    }

    @Test fun pushedWhenNoCustomUrl() {
        assertEquals(SourceKind.PUSHED, PlaylistSource.choose("", stamp))
        assertEquals(SourceKind.PUSHED, PlaylistSource.choose("   ", stamp))
    }

    @Test fun noneWhenNothing() {
        assertEquals(SourceKind.NONE, PlaylistSource.choose("", null))
        assertEquals(SourceKind.NONE, PlaylistSource.choose("", FileStamp(1, 0))) // 空文件视为没有
    }

    @Test fun reloadOnlyWhenPushedAndChanged() {
        assertFalse(PlaylistSource.needsReload(true, stamp, stamp))
        assertTrue(PlaylistSource.needsReload(true, stamp, stamp.copy(modified = stamp.modified + 1000)))
        assertTrue(PlaylistSource.needsReload(true, stamp, stamp.copy(length = 5)))
        assertTrue(PlaylistSource.needsReload(true, null, stamp))
        assertFalse(PlaylistSource.needsReload(false, stamp, stamp.copy(length = 5))) // 当前不是推送清单
        assertFalse(PlaylistSource.needsReload(true, stamp, null))                    // 文件被删：保持现有清单
        assertFalse(PlaylistSource.needsReload(true, stamp, FileStamp(2, 0)))         // 被截成空：不重载
    }

    @Test fun labels() {
        val tz = TimeZone.getTimeZone("UTC")
        assertEquals("自定义地址", PlaylistSource.label(SourceKind.CUSTOM, 3, stamp, tz))
        assertEquals("无", PlaylistSource.label(SourceKind.NONE, null, null, tz))
        // 1_700_000_000 = 2023-11-14 22:13:20 UTC
        assertEquals("推送的清单（18 个频道，更新于 2023-11-14 22:13）",
            PlaylistSource.label(SourceKind.PUSHED, 18, stamp, tz))
        assertEquals("推送的清单（更新于 2023-11-14 22:13）", PlaylistSource.label(SourceKind.PUSHED, null, stamp, tz))
    }

    @Test fun cacheFileNameDependsOnUrl() {
        val a = PlaylistLoader.cacheFileName("http://a/x.m3u")
        assertEquals(a, PlaylistLoader.cacheFileName("http://a/x.m3u"))
        assertNotEquals(a, PlaylistLoader.cacheFileName("http://b/x.m3u"))
        assertTrue(a.matches(Regex("playlist-cache-[0-9a-f]{16}\\.txt")))
    }
}

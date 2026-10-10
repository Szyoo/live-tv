package xyz.szyyw.livetv

import org.junit.Assert.*
import org.junit.Test
import xyz.szyyw.livetv.data.PlaylistLoader
import xyz.szyyw.livetv.playlist.PlaylistParser
import java.io.File

class BuiltinChannelsTest {
    private val pl = PlaylistParser.parse(File("src/main/assets/channels.m3u").readText())

    @Test fun channelNamesInOrder() {
        val expected = listOf("CCTV-1", "CCTV-2", "CCTV-3", "CCTV-4", "CCTV-5", "CCTV-5+", "CCTV-6") +
            (7..17).map { "CCTV-$it" }
        assertEquals(18, pl.channels.size)
        assertEquals(expected, pl.channels.map { it.name })
    }

    @Test fun eachChannelHasEnoughHttpStreams() {
        for (c in pl.channels) {
            assertTrue("${c.name} 线路不足: ${c.streams.size}", c.streams.size >= 3)
            for (s in c.streams) assertTrue("${c.name}: ${s.url}", s.url.startsWith("http://") || s.url.startsWith("https://"))
        }
    }

    @Test fun cacheFileNameDependsOnUrl() {
        val a = PlaylistLoader.cacheFileName("http://a/x.m3u")
        assertEquals(a, PlaylistLoader.cacheFileName("http://a/x.m3u"))
        assertNotEquals(a, PlaylistLoader.cacheFileName("http://b/x.m3u"))
        assertTrue(a.matches(Regex("playlist-cache-[0-9a-f]{16}\\.txt")))
    }
}

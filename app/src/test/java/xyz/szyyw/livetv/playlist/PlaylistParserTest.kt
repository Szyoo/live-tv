package xyz.szyyw.livetv.playlist

import org.junit.Assert.*
import org.junit.Test

class PlaylistParserTest {
    @Test fun m3uAttributesAndDisplayName() {
        val p = PlaylistParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV1" tvg-logo="http://x/logo.png" group-title="央视",CCTV-1 综合
            http://a/1.m3u8
            """.trimIndent()
        )
        assertEquals(1, p.channels.size)
        val c = p.channels[0]
        assertEquals(1, c.number)
        assertEquals("CCTV-1 综合", c.name)
        assertEquals("央视", c.group)
        assertEquals("http://x/logo.png", c.logo)
        assertEquals("http://a/1.m3u8", c.streams[0].url)
    }

    @Test fun m3uFallsBackToTvgNameWhenNoDisplayName() {
        val p = PlaylistParser.parse("#EXTM3U\n#EXTINF:-1 tvg-name=\"CCTV9\" group-title=\"G\",\nhttp://a/9.m3u8")
        assertEquals("CCTV9", p.channels[0].name)
    }

    @Test fun m3uVlcOptHeadersApplyToThatStreamOnly() {
        val p = PlaylistParser.parse(
            """
            #EXTM3U
            #EXTINF:-1,A
            #EXTVLCOPT:http-user-agent=MyUA/1.0
            #EXTVLCOPT:http-referrer=http://ref.example/
            http://a/1
            #EXTINF:-1,A
            http://a/2
            """.trimIndent()
        )
        val s = p.channels[0].streams
        assertEquals(2, s.size)
        assertEquals("MyUA/1.0", s[0].headers["User-Agent"])
        assertEquals("http://ref.example/", s[0].headers["Referer"])
        assertTrue(s[1].headers.isEmpty())
    }

    @Test fun quotesAndCommasInAttributes() {
        val p = PlaylistParser.parse(
            "#EXTM3U\n#EXTINF:-1 tvg-name=\"A, B\" group-title='新闻,综合' tvg-logo=\"\",Name X\nhttp://a/1"
        )
        val c = p.channels[0]
        assertEquals("Name X", c.name)
        assertEquals("新闻,综合", c.group)
        assertNull(c.logo)
    }

    @Test fun sameNameMergedKeepingFirstOrderAndGlobalNumbering() {
        val p = PlaylistParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="央视",CCTV-1
            http://a/1
            #EXTINF:-1 group-title="央视",CCTV-2
            http://a/2
            #EXTINF:-1 group-title="其他",CCTV-1
            http://b/1
            #EXTINF:-1 group-title="其他",News
            http://b/3
            """.trimIndent()
        )
        assertEquals(listOf("CCTV-1", "CCTV-2", "News"), p.channels.map { it.name })
        assertEquals(listOf(1, 2, 3), p.channels.map { it.number })
        assertEquals(listOf("http://a/1", "http://b/1"), p.channels[0].streams.map { it.url })
        assertEquals("央视", p.channels[0].group)
    }

    @Test fun duplicateIdenticalUrlsDeduped() {
        val p = PlaylistParser.parse("#EXTM3U\n#EXTINF:-1,A\nhttp://a/1\n#EXTINF:-1,A\nhttp://a/1")
        assertEquals(1, p.channels[0].streams.size)
    }

    @Test fun bomCrlfBlankLinesAndNoGroup() {
        val raw = "﻿#EXTM3U\r\n\r\n#EXTINF:-1,无组台\r\n\r\nhttp://a/1\r\n\r\n"
        val p = PlaylistParser.parse(raw)
        assertEquals(1, p.channels.size)
        assertEquals("无组台", p.channels[0].name)
        assertEquals(PlaylistParser.UNGROUPED, p.channels[0].group)
        assertEquals("http://a/1", p.channels[0].streams[0].url)
    }

    @Test fun invalidLinesSkippedWithoutCrash() {
        val p = PlaylistParser.parse(
            """
            #EXTM3U
            garbage line
            http://orphan/without/extinf
            #EXTINF:-1,OK
            not a url
            http://a/ok
            #EXTINF
            #EXTINF:-1,
            http://a/noname
            """.trimIndent()
        )
        assertEquals(listOf("OK"), p.channels.map { it.name })
        assertEquals("http://a/ok", p.channels[0].streams[0].url)
    }

    @Test fun emptyAndNonsenseInput() {
        assertTrue(PlaylistParser.parse("").isEmpty)
        assertTrue(PlaylistParser.parse("\n\n").isEmpty)
        assertTrue(PlaylistParser.parse("<html>404</html>").isEmpty)
    }

    @Test fun txtGroupsAndMultiLineSameLine() {
        val p = PlaylistParser.parse(
            """
            央视,#genre#
            CCTV-1,http://a/1#http://a/2
            CCTV-2,http://a/3
            卫视,#genre#
            湖南卫视,http://b/1
            CCTV-1,http://c/1
            """.trimIndent()
        )
        assertEquals(listOf("CCTV-1", "CCTV-2", "湖南卫视"), p.channels.map { it.name })
        assertEquals(listOf("央视", "央视", "卫视"), p.channels.map { it.group })
        assertEquals(listOf("http://a/1", "http://a/2", "http://c/1"), p.channels[0].streams.map { it.url })
        assertEquals(listOf(1, 2, 3), p.channels.map { it.number })
    }

    @Test fun txtBomCrlfNoGenreAndBadLines() {
        val p = PlaylistParser.parse("﻿A,http://a/1\r\n\r\nno comma line\r\nB,notaurl\r\n,http://x\r\nC,http://c/1\r\n")
        assertEquals(listOf("A", "C"), p.channels.map { it.name })
        assertEquals(PlaylistParser.UNGROUPED, p.channels[0].group)
    }

    @Test fun txtUrlWithQueryComma() {
        val p = PlaylistParser.parse("G,#genre#\nA,http://a/x?a=1,2")
        assertEquals("http://a/x?a=1,2", p.channels[0].streams[0].url)
    }

    @Test fun pipeHeadersOnUrl() {
        val p = PlaylistParser.parse("A,http://a/1|User-Agent=UA9&Referer=http://r/")
        val s = p.channels[0].streams[0]
        assertEquals("http://a/1", s.url)
        assertEquals("UA9", s.headers["User-Agent"])
        assertEquals("http://r/", s.headers["Referer"])
    }
}

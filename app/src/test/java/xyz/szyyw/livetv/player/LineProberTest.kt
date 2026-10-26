package xyz.szyyw.livetv.player

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import xyz.szyyw.livetv.playlist.Stream
import java.util.concurrent.TimeUnit

class LineProberTest {
    private lateinit var server: MockWebServer
    private val cfg = ProbeConfig(connectMs = 1000, readMs = 1000, callMs = 4000, directMs = 800, totalMs = 3000)
    private lateinit var prober: LineProber

    private fun ts(bytes: Int): ByteArray = ByteArray(bytes).also { for (i in it.indices step 188) it[i] = 0x47 }
    private fun body(b: ByteArray) = Buffer().write(b)

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/hls/master.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nv/media.m3u8\n")
                "/hls/v/media.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nseg0.ts\n#EXTINF:4,\nseg1.ts\n")
                "/hls/v/seg0.ts" -> MockResponse().setBody(body(ts(188 * 300)))
                "/hls/plain.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXTINF:4,\nv/seg0.ts\n")
                "/redir" -> MockResponse().setResponseCode(302).addHeader("Location", "/hls/master.m3u8")
                "/direct" -> MockResponse().setBody(body(ts(188 * 400)))
                "/slow" -> MockResponse().setBody(body(ts(188 * 400))).throttleBody(2048, 100, TimeUnit.MILLISECONDS)
                "/hlsslow" -> MockResponse().setBody("#EXTM3U\n#EXTINF:4,\nv/segslow.ts\n")
                "/v/segslow.ts" -> MockResponse().setBody(body(ts(188 * 300))).throttleBody(4096, 100, TimeUnit.MILLISECONDS)
                "/html" -> MockResponse().addHeader("Content-Type", "text/html").setBody("<html><body>hello</body></html>")
                "/htmlseg.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXTINF:4,\nhtmlpage\n")
                "/htmlpage" -> MockResponse().setBody("<!DOCTYPE html><html>" + "x".repeat(40_000))
                "/small.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXTINF:4,\ntiny.ts\n")
                "/tiny.ts" -> MockResponse().setBody(body(ts(188 * 5)))
                "/junk" -> MockResponse().setBody(body(ByteArray(50_000) { (it * 7 % 251).toByte() }))
                "/emptyplaylist.m3u8" -> MockResponse().setBody("#EXTM3U\n")
                "/encrypted.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:4,\nenc.ts\n")
                "/enc.ts" -> MockResponse().setBody(body(ByteArray(50_000) { (it * 7 % 251).toByte() }))
                "/hang" -> MockResponse().setBody("x").setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        prober = LineProber(OkHttpClient(), cfg)
    }

    @After fun tearDown() { server.shutdown() }

    private fun probe(path: String) = prober.probe(Stream(server.url(path).toString()))

    @Test fun hlsMasterMediaSegmentSucceeds() {
        val p = probe("/hls/master.m3u8")
        assertTrue(p.toString(), p.ok)
        assertTrue(p.millis in 0..3000)
    }

    @Test fun plainMediaPlaylistSucceeds() = assertTrue(probe("/hls/plain.m3u8").ok)

    @Test fun redirectToPlaylistIsFollowed() = assertTrue(probe("/redir").ok)

    @Test fun directTsStreamSucceeds() = assertTrue(probe("/direct").ok)

    @Test fun slowDirectStreamTimesOut() {
        val p = probe("/slow")   // 约 20KB/s，32KB 要 1.6 秒，超过 800ms 时限
        assertFalse(p.ok); assertEquals(ProbeFail.TIMEOUT, p.fail)
    }

    @Test fun slowSegmentIsSlowerButMeasured() {
        val fast = probe("/hls/plain.m3u8")
        val slow = probe("/hlsslow")
        assertTrue(slow.toString(), slow.ok)
        assertTrue("slow ${slow.millis} fast ${fast.millis}", slow.millis > fast.millis + 200)
    }

    @Test fun notFoundFails() {
        val p = probe("/nothing")
        assertFalse(p.ok); assertEquals(ProbeFail.HTTP, p.fail); assertEquals("404", p.detail)
    }

    @Test fun htmlPageFails() {
        assertEquals(ProbeFail.HTML, probe("/html").fail)
        assertEquals(ProbeFail.HTML, probe("/htmlseg.m3u8").fail)
    }

    @Test fun tooSmallSegmentFails() {
        val p = probe("/small.m3u8")
        assertFalse(p.ok); assertEquals(ProbeFail.TOO_SMALL, p.fail)
    }

    @Test fun nonMediaBytesFail() = assertEquals(ProbeFail.NOT_MEDIA, probe("/junk").fail)

    @Test fun emptyPlaylistFails() = assertEquals(ProbeFail.BAD_PLAYLIST, probe("/emptyplaylist.m3u8").fail)

    @Test fun encryptedSegmentsOnlyNeedEnoughBytes() = assertTrue(probe("/encrypted.m3u8").ok)

    @Test fun noResponseTimesOut() = assertEquals(ProbeFail.TIMEOUT, probe("/hang").fail)

    @Test fun refusedConnectionIsNetworkFailure() {
        val url = server.url("/direct").toString()
        server.shutdown()
        val p = prober.probe(Stream(url))
        assertFalse(p.ok); assertEquals(ProbeFail.NETWORK, p.fail)
    }

    @Test fun sniffers() {
        assertTrue(LineProber.looksLikeMedia(ts(188 * 200)))
        assertTrue(LineProber.looksLikeMedia(ByteArray(20) { 0 }.also { "ftyp".toByteArray().copyInto(it, 4) }))
        assertFalse(LineProber.looksLikeMedia(ByteArray(2000) { 1 }))
        assertTrue(LineProber.looksLikeHtml("  <!DOCTYPE HTML><html>".toByteArray()))
        assertFalse(LineProber.looksLikeHtml(ts(2000)))
        assertTrue(LineProber.isPlaylistHead("﻿ #EXTM3U".toByteArray()))
        assertFalse(LineProber.isPlaylistHead("<html>".toByteArray()))
    }
}

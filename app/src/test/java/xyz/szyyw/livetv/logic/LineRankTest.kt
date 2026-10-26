package xyz.szyyw.livetv.logic

import org.junit.Assert.*
import org.junit.Test
import xyz.szyyw.livetv.playlist.Stream

class LineRankTest {
    private val now = 1_000_000L
    private fun s(n: Int) = Stream("http://example.com/l$n")
    private val all = (1..6).map { s(it) }
    private fun ok(ms: Long, at: Long = now) = ProbeEntry(true, ms, at)
    private fun bad(at: Long = now) = ProbeEntry(false, 100, at)
    private fun urls(l: List<Stream>) = l.map { it.url.substringAfterLast('/') }

    @Test fun orderIsGoodByMillisThenUntestedThenFailed() {
        val r = mapOf(
            s(1).url to bad(),
            s(2).url to ok(1200),
            s(4).url to ok(800),
            s(6).url to bad(),
        )
        // 3、5 未测，保持原序
        assertEquals(listOf("l4", "l2", "l3", "l5", "l1", "l6"), urls(LineRank.order(all, r, now)))
    }

    @Test fun equalMillisKeepListOrder() {
        val r = mapOf(s(3).url to ok(500), s(2).url to ok(500))
        assertEquals(listOf("l2", "l3", "l1", "l4", "l5", "l6"), urls(LineRank.order(all, r, now)))
    }

    @Test fun expiredResultsCountAsUntested() {
        val ttl = 30 * 60_000L
        val r = mapOf(
            s(1).url to ok(100, at = now - ttl - 1),     // 过期的成功
            s(2).url to bad(at = now - ttl - 1),         // 过期的失败
            s(3).url to ok(900, at = now - ttl),         // 刚好有效
        )
        assertEquals(listOf("l3", "l1", "l2", "l4", "l5", "l6"), urls(LineRank.order(all, r, now, ttl)))
        assertFalse(LineRank.isBad(r[s(2).url], now, ttl))
        assertTrue(LineRank.isGood(r[s(3).url], now, ttl))
    }

    @Test fun futureTimestampIsStale() {
        assertFalse(LineRank.isFresh(ok(1, at = now + 5000), now, LineRank.TTL_MS))
    }

    @Test fun keyedByUrlSoNewPlaylistMatches() {
        val r = mapOf(s(5).url to ok(300))
        val reshuffled = listOf(s(9), s(5), s(1))
        assertEquals(listOf("l5", "l9", "l1"), urls(LineRank.order(reshuffled, r, now)))
    }

    @Test fun duplicateUrlsCollapse() {
        assertEquals(listOf("l1", "l2"), urls(LineRank.order(listOf(s(1), s(2), s(1)), emptyMap(), now)))
    }

    @Test fun pickStartUsesRememberedUnlessFailed() {
        val ordered = listOf(s(4), s(2), s(1))
        assertEquals(1, LineRank.pickStart(ordered, s(2).url) { false })
        assertEquals(0, LineRank.pickStart(ordered, s(2).url) { it == s(2).url })
        assertEquals(0, LineRank.pickStart(ordered, null) { false })
        assertEquals(0, LineRank.pickStart(ordered, "http://example.com/gone") { false })
    }

    @Test fun failoverSkipsFailedThenFallsBackToPlainNext() {
        val ordered = listOf(s(1), s(2), s(3), s(4))
        val failed = setOf(s(2).url, s(3).url)
        assertEquals(3, LineRank.nextCandidate(ordered, 0) { it in failed })
        assertEquals(0, LineRank.nextCandidate(ordered, 3) { it in failed })
        val all = ordered.map { it.url }.toSet()
        assertEquals(2, LineRank.nextCandidate(ordered, 1) { it in all })
        assertEquals(2, LineRank.failoverLimit(ordered) { it in failed })
        assertEquals(4, LineRank.failoverLimit(ordered) { it in all })
        assertEquals(0, LineRank.nextCandidate(listOf(s(1)), 0) { false })
    }

    @Test fun shouldRunRespectsInterval() {
        val iv = LineRank.RERUN_INTERVAL_MS
        assertTrue(LineRank.shouldRun(0, now, iv, false))
        assertFalse(LineRank.shouldRun(now - iv, now, iv, false))
        assertTrue(LineRank.shouldRun(now - iv - 1, now, iv, false))
        assertTrue(LineRank.shouldRun(now - 1, now, iv, true))
        assertFalse(LineRank.shouldRun(now - 1000, now, 5_000, false))
    }

    @Test fun labels() {
        assertEquals("412 ms", LineRank.msLabel(412))
        assertEquals("10000 ms", LineRank.msLabel(10_000))
        assertEquals(">10 s", LineRank.msLabel(10_001))
        assertEquals("412 ms", LineRank.statusLabel(ok(412), now))
        assertEquals("失败", LineRank.statusLabel(bad(), now))
        assertEquals("未测", LineRank.statusLabel(null, now))
        assertEquals("未测", LineRank.statusLabel(ok(1, at = now - LineRank.TTL_MS - 1), now))
    }

    @Test fun hostOf() {
        assertEquals("example.com", LineRank.hostOf("http://example.com:8080/a/b.m3u8?x=1"))
        assertEquals("127.0.0.1", LineRank.hostOf("http://user:pw@127.0.0.1/x"))
        assertEquals("[::1]", LineRank.hostOf("http://[::1]:80/x"))
    }

    @Test fun hostLimiterCapsPerHostAndTotal() {
        val l = HostLimiter(maxTotal = 3, maxPerHost = 2)
        assertTrue(l.tryAcquire("a")); assertTrue(l.tryAcquire("a"))
        assertFalse(l.tryAcquire("a"))
        assertTrue(l.tryAcquire("b"))
        assertFalse(l.tryAcquire("c"))          // 总数已满
        l.release("a")
        assertTrue(l.tryAcquire("c"))
        assertEquals(3, l.running())
        assertEquals(1, l.runningOn("a"))
    }
}

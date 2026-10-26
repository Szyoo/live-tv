package xyz.szyyw.livetv.player

import org.junit.Assert.*
import org.junit.Test
import xyz.szyyw.livetv.logic.LineRank
import xyz.szyyw.livetv.playlist.Channel
import xyz.szyyw.livetv.playlist.Stream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LineRankerTest {
    private fun ch(name: String, n: Int, host: (Int) -> String = { "h$it" }) =
        Channel(1, name, "g", null, (1..n).map { Stream("http://${host(it)}.example.com/$name/$it") })

    private fun waitIdle(r: LineRanker) {
        val end = System.currentTimeMillis() + 10_000
        while (r.isRunning() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertFalse("ranker did not finish", r.isRunning())
    }

    @Test fun onlyChannelsWithEnoughLinesAreProbed() {
        val seen = ConcurrentHashMap.newKeySet<String>()
        val r = LineRanker({ seen.add(it.url); Probe(true, 100) }, null)
        val added = r.start(listOf(ch("big", 12), ch("small", 3)), null)
        waitIdle(r)
        assertEquals(12, added)
        assertEquals(12, seen.size)
        assertTrue(seen.none { it.contains("/small/") })
        assertTrue(r.isRanked(ch("big", 12)) && !r.isRanked(ch("small", 3)))
        r.shutdown()
    }

    @Test fun viewOrdersByResultAndFailedLast() {
        val ms = mapOf(1 to 900L, 2 to 100L, 3 to 500L)
        val r = LineRanker({ s ->
            val i = s.url.substringAfterLast('/').toInt()
            if (i == 5) Probe(false, 50, ProbeFail.HTTP, "404") else Probe(true, ms[i] ?: (1000L + i))
        }, null)
        val c = ch("big", 10)
        r.start(listOf(c), null)
        waitIdle(r)
        val order = r.view(c).streams.map { it.url.substringAfterLast('/').toInt() }
        assertEquals(listOf(2, 3, 1), order.take(3))
        assertEquals(5, order.last())
        assertTrue(r.isFailed(c.streams[4].url))
        assertEquals(9, r.status(c).ok)
        assertFalse(r.status(c).running)
        assertEquals(10, r.status(c).total)
        r.shutdown()
    }

    @Test fun concurrencyLimitsTotalAndPerHost() {
        val cur = AtomicInteger(); val max = AtomicInteger()
        val perHost = ConcurrentHashMap<String, AtomicInteger>(); val maxHost = AtomicInteger()
        val r = LineRanker({ s ->
            val h = s.url.substringAfter("//").substringBefore('/')
            val c = cur.incrementAndGet(); max.updateAndGet { maxOf(it, c) }
            val hc = perHost.getOrPut(h) { AtomicInteger() }.incrementAndGet(); maxHost.updateAndGet { maxOf(it, hc) }
            Thread.sleep(30)
            perHost[h]!!.decrementAndGet(); cur.decrementAndGet()
            Probe(true, 10)
        }, null)
        // 40 条线路只落在 5 个主机上
        r.start(listOf(ch("big", 40) { "h${it % 5}" }), null)
        waitIdle(r)
        assertTrue("max total ${max.get()}", max.get() <= 8)
        assertTrue("max per host ${maxHost.get()}", maxHost.get() <= 2)
        assertTrue(max.get() >= 2)
        r.shutdown()
    }

    @Test fun currentChannelIsProbedFirst() {
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        val gate = CountDownLatch(1)
        val r = LineRanker({ s ->
            order.add(s.url.substringAfter("//").substringAfter('/').substringBefore('/'))
            gate.await(2, TimeUnit.SECONDS)
            Probe(true, 10)
        }, null, maxTotal = 1)
        r.start(listOf(ch("a", 10), ch("b", 10)), "b")
        gate.countDown()
        waitIdle(r)
        assertEquals("b", order.first())
        assertEquals(List(10) { "b" }, order.take(10))
        r.shutdown()
    }

    @Test fun freshResultsAreSkippedUntilExpiryOrForce() {
        var now = 1_000_000L
        val n = AtomicInteger()
        val r = LineRanker({ n.incrementAndGet(); Probe(true, 10) }, null, clock = { now }, ttlMs = 1000)
        val c = ch("big", 10)
        assertEquals(10, r.start(listOf(c), null)); waitIdle(r)
        assertEquals(0, r.start(listOf(c), null))
        now += 1001
        assertEquals(10, r.start(listOf(c), null)); waitIdle(r)
        assertEquals(10, r.start(listOf(c), null, force = true)); waitIdle(r)
        assertEquals(30, n.get())
        r.shutdown()
    }

    @Test fun failedLinesAreRetestedAfterExpiry() {
        var now = 5_000L
        val r = LineRanker({ Probe(false, 5, ProbeFail.HTTP) }, null, clock = { now }, ttlMs = 1000)
        val c = ch("big", 10)
        r.start(listOf(c), null); waitIdle(r)
        assertTrue(r.isFailed(c.streams[0].url))
        now += 2000
        assertFalse(r.isFailed(c.streams[0].url))
        assertEquals(10, r.start(listOf(c), null)); waitIdle(r)
        r.shutdown()
    }

    @Test fun resultsPersistAndMatchByUrl() {
        val f = File.createTempFile("probes", ".json").apply { deleteOnExit() }
        val r1 = LineRanker({ Probe(true, 321) }, f)
        val c = ch("big", 10)
        r1.start(listOf(c), null); waitIdle(r1); r1.shutdown()
        // 新进程、新清单（顺序打乱、多出一条未测）仍能对应上
        val r2 = LineRanker({ Probe(true, 1) }, f)
        val c2 = c.copy(streams = c.streams.reversed() + Stream("http://new.example.com/x"))
        assertEquals(321L, r2.entry(c.streams[0].url)?.millis)
        assertNull(r2.entry("http://new.example.com/x"))
        assertEquals(1, r2.start(listOf(c2), null)); waitIdle(r2)
        r2.shutdown()
    }

    @Test fun expiredSavedResultsAreDropped() {
        val f = File.createTempFile("probes", ".json").apply { deleteOnExit() }
        var now = 1_000L
        val r1 = LineRanker({ Probe(true, 7) }, f, clock = { now })
        val c = ch("big", 10)
        r1.start(listOf(c), null); waitIdle(r1); r1.shutdown()
        now += LineRank.TTL_MS + 1
        val r2 = LineRanker({ Probe(true, 7) }, f, clock = { now })
        assertNull(r2.entry(c.streams[0].url))
        r2.shutdown()
    }

    @Test fun cancelMarksInterruptedSoNextRunIsAllowed() {
        val gate = CountDownLatch(1)
        val r = LineRanker({ gate.await(2, TimeUnit.SECONDS); Probe(true, 1) }, null, maxTotal = 1)
        r.start(listOf(ch("big", 10)), null)
        r.cancelPending()
        assertTrue(r.interrupted)
        assertTrue(LineRank.shouldRun(r.lastRunAt, System.currentTimeMillis(), LineRank.RERUN_INTERVAL_MS, r.interrupted))
        gate.countDown(); waitIdle(r); r.shutdown()
    }

    @Test fun switchRouteKeepsResultsPerRouteAndDropsStaleInFlight() {
        val direct = File.createTempFile("probes-d", ".json").apply { deleteOnExit() }
        val viaProxy = File.createTempFile("probes-p", ".json").apply { deleteOnExit() }
        val gate = CountDownLatch(1)
        var slow = true
        val r = LineRanker({ s ->
            if (slow && s.url.endsWith("/1")) gate.await(3, TimeUnit.SECONDS)
            Probe(true, if (slow) 111 else 222)
        }, direct, maxTotal = 4)
        val c = ch("big", 10)
        r.start(listOf(c), null)
        Thread.sleep(100)
        // 第 1 条还卡在旧通道上；此时换通道
        slow = false
        r.switchRoute(viaProxy)
        assertNull(r.entry(c.streams[0].url))               // 旧通道的结果不带过来
        gate.countDown()
        Thread.sleep(150)
        assertNull(r.entry(c.streams[0].url))               // 卡住那条后来才返回，也要作废
        r.start(listOf(c), null); waitIdle(r)
        assertEquals(222L, r.entry(c.streams[0].url)?.millis)
        r.switchRoute(direct)                                // 切回直连：直连当时保存的结果仍在
        assertNull(r.entry(c.streams[0].url))                // 直连时只有少数几条测完，第 1 条没有
        r.shutdown()
    }
}

package xyz.szyyw.livetv.player

import android.util.Log
import org.json.JSONObject
import xyz.szyyw.livetv.logic.HostLimiter
import xyz.szyyw.livetv.logic.LineRank
import xyz.szyyw.livetv.logic.ProbeEntry
import xyz.szyyw.livetv.playlist.Channel
import xyz.szyyw.livetv.playlist.Stream
import java.io.File
import java.util.LinkedList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** 某个频道的测速进展：running 时 done/total 为本轮进度；ok 为当前可用（测速成功且未过期）的线路数。 */
data class RankStatus(val running: Boolean, val done: Int, val total: Int, val ok: Int)

/**
 * 线路自动测速排序：对线路数 >= [LineRank.MIN_LINES] 的频道在后台批量测速，
 * 保存每个 URL 的结果（内存 + filesDir 下的 json，按 URL 为键、[ttlMs] 内有效），
 * 并按「成功按耗时 → 未测保持原序 → 失败最后」给出线路顺序。线程安全。
 *
 * 并发：总数 [maxTotal]、同主机 [perHost]；工作线程为最低优先级；当前频道的任务先调度。
 * [onChanged] 在工作线程调用，调用方自行切回主线程。
 */
class LineRanker(
    private val probe: (Stream) -> Probe,
    store: File?,
    private val clock: () -> Long = System::currentTimeMillis,
    @Volatile var ttlMs: Long = LineRank.TTL_MS,
    maxTotal: Int = 8,
    perHost: Int = 2,
    private val onChanged: () -> Unit = {},
) {
    private class Task(val channel: String, val stream: Stream, val host: String, val gen: Int)

    private var store: File? = store
    private var gen = 0   // 通道切换一次加一；旧通道上还在测的任务结果作废

    private val lock = Any()
    private val results = HashMap<String, ProbeEntry>()
    private val pending = LinkedList<Task>()
    private val queued = HashSet<String>()           // 在队列里或正在测的 URL
    private val limiter = HostLimiter(maxTotal, perHost)
    private val remaining = HashMap<String, Int>()   // 频道 -> 未完成的任务数
    private val totals = HashMap<String, Int>()
    private val dones = HashMap<String, Int>()
    private var priorityChannel: String? = null
    private var inFlight = 0
    private var runStartedAt = 0L
    private var okCount = 0
    private var ranked: List<Channel> = emptyList()
    private var failCount = 0
    private var lastSaveAt = 0L
    private val poolId = AtomicInteger()
    private val pool: ExecutorService = Executors.newFixedThreadPool(maxTotal) { r ->
        Thread(r, "line-probe-${poolId.incrementAndGet()}").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    /** 上一轮测速的开始时刻（毫秒，0 = 没测过）；被取消打断过则 [interrupted] 为 true。 */
    @Volatile var lastRunAt = 0L
        private set
    @Volatile var interrupted = false
        private set

    init { load() }

    // ---------- 排序与查询 ----------

    /** 该频道按排序后的线路给出的视图。 */
    fun view(ch: Channel): Channel {
        val ordered = synchronized(lock) { LineRank.order(ch.streams, results, clock(), ttlMs) }
        return if (ordered.isEmpty()) ch else ch.copy(streams = ordered)
    }

    fun isFailed(url: String): Boolean = synchronized(lock) { LineRank.isBad(results[url], clock(), ttlMs) }

    fun entry(url: String): ProbeEntry? = synchronized(lock) { results[url]?.takeIf { LineRank.isFresh(it, clock(), ttlMs) } }

    /** 该频道是否在测速范围内（线路数够多）。 */
    fun isRanked(ch: Channel): Boolean = ch.streams.size >= LineRank.MIN_LINES

    fun status(ch: Channel): RankStatus = synchronized(lock) {
        val now = clock()
        val ok = ch.streams.map { it.url }.distinct().count { LineRank.isGood(results[it], now, ttlMs) }
        RankStatus((remaining[ch.name] ?: 0) > 0, dones[ch.name] ?: 0, totals[ch.name] ?: 0, ok)
    }

    fun isRunning(): Boolean = synchronized(lock) { pending.isNotEmpty() || inFlight > 0 }

    // ---------- 调度 ----------

    /** 之后优先测这个频道。 */
    fun prioritize(channelName: String?) {
        synchronized(lock) { priorityChannel = channelName }
        dispatch()
    }

    /**
     * 开始一轮测速：只测线路数够多的频道里「没有有效结果」的线路（[force] 则全部重测）。
     * @return 新排进队列的线路数
     */
    fun start(channels: List<Channel>, currentChannel: String?, force: Boolean = false): Int {
        var added = 0
        val now = clock()
        synchronized(lock) {
            priorityChannel = currentChannel
            ranked = channels.filter { it.streams.size >= LineRank.MIN_LINES }
            if (force) { // 丢掉排着队的旧任务，按新的一轮重排
                for (t in pending) { queued.remove(t.stream.url); dec(t.channel) }
                pending.clear()
            }
            val perChannel = HashMap<String, Int>()
            for (ch in channels) {
                if (ch.streams.size < LineRank.MIN_LINES) continue
                for (s in ch.streams) {
                    if (s.url in queued) continue
                    if (!force && LineRank.isFresh(results[s.url], now, ttlMs)) continue
                    queued.add(s.url)
                    pending.add(Task(ch.name, s, LineRank.hostOf(s.url), gen))
                    perChannel.merge(ch.name, 1, Int::plus)
                    added++
                }
            }
            for ((name, n) in perChannel) {
                if ((remaining[name] ?: 0) > 0) {
                    totals[name] = (totals[name] ?: 0) + n
                } else {
                    totals[name] = n; dones[name] = 0
                }
                remaining[name] = (remaining[name] ?: 0) + n
            }
            if (added > 0) {
                if (inFlight == 0 && pending.size == added) { runStartedAt = SystemClockMs.now(); okCount = 0; failCount = 0 }
                lastRunAt = now
                interrupted = false
            }
        }
        if (added > 0) Log.i(TAG, "rank: start lines=$added force=$force current=${currentChannel ?: "-"}")
        dispatch()
        return added
    }

    /** 放弃排队中的任务（退到后台时调用）；正在测的会自然结束。 */
    fun cancelPending() {
        synchronized(lock) {
            if (pending.isEmpty()) return
            for (t in pending) { queued.remove(t.stream.url); dec(t.channel) }
            Log.i(TAG, "rank: cancelled ${pending.size} queued")
            pending.clear()
            interrupted = true
        }
    }

    /**
     * 测速通道变了（直连 <-> 代理）：保存旧通道的结果，换成新通道自己的结果文件，
     * 丢掉排队中的任务；旧通道上还在测的任务结果作废。之后调用 [start] 重测。
     */
    fun switchRoute(newStore: File?) {
        synchronized(lock) {
            saveLocked()
            pending.clear()
            queued.clear()
            remaining.clear(); totals.clear(); dones.clear()
            results.clear()
            store = newStore
            gen++
            lastRunAt = 0L
            interrupted = false
            load()
        }
        onChanged()
    }

    fun shutdown() {
        cancelPending()
        pool.shutdownNow()
        synchronized(lock) { saveLocked() }
    }

    private fun dec(channel: String) {
        val r = (remaining[channel] ?: 0) - 1
        if (r <= 0) remaining.remove(channel) else remaining[channel] = r
    }

    private fun dispatch() {
        val toRun = ArrayList<Task>()
        synchronized(lock) {
            while (true) {
                val t = pickLocked() ?: break
                inFlight++
                toRun += t
            }
        }
        for (t in toRun) {
            try { pool.execute { runTask(t) } } catch (_: Exception) { synchronized(lock) { finishLocked(t, null) } }
        }
    }

    /** 取下一个可以开测的任务：先当前频道，再其它；同主机已满的跳过。 */
    private fun pickLocked(): Task? {
        for (pass in 0..1) {
            val it = pending.iterator()
            while (it.hasNext()) {
                val t = it.next()
                if (pass == 0 && t.channel != priorityChannel) continue
                if (!limiter.tryAcquire(t.host)) continue
                it.remove()
                return t
            }
        }
        return null
    }

    private fun runTask(t: Task) {
        val p = try { probe(t.stream) } catch (e: Throwable) { Probe(false, 0, ProbeFail.NETWORK, e.javaClass.simpleName) }
        val e = ProbeEntry(p.ok, p.millis, clock())
        Log.d(TAG, "probe ${t.host} ${if (p.ok) "ok ${p.millis}ms" else "fail ${p.fail} ${p.detail} ${p.millis}ms"}")
        synchronized(lock) { finishLocked(t, e) }
        onChanged()
        dispatch()
    }

    private fun finishLocked(t: Task, e: ProbeEntry?) {
        limiter.release(t.host)
        inFlight--
        if (t.gen != gen) return // 通道已切换：旧通道的结果不要
        queued.remove(t.stream.url)
        if (e != null) {
            results[t.stream.url] = e
            if (e.ok) okCount++ else failCount++
        }
        dec(t.channel)
        dones[t.channel] = (dones[t.channel] ?: 0) + 1
        val now = SystemClockMs.now()
        val idle = pending.isEmpty() && inFlight == 0
        if (idle || now - lastSaveAt > 3000) { saveLocked(); lastSaveAt = now }
        if (idle) {
            Log.i(TAG, "rank: done ok=$okCount fail=$failCount in ${now - runStartedAt}ms")
            for (ch in ranked) {
                val es = ch.streams.map { it.url }.distinct().mapNotNull { results[it] }.filter { LineRank.isFresh(it, now, ttlMs) }
                val top = es.filter { it.ok }.map { it.millis }.sorted().take(5)
                Log.i(TAG, "rank: ${ch.name} ok=${es.count { it.ok }} fail=${es.count { !it.ok }} top5(ms)=$top")
            }
        }
    }

    // ---------- 持久化 ----------

    private fun load() {
        val f = store ?: return
        try {
            if (!f.isFile) return
            val o = JSONObject(f.readText())
            val now = clock()
            for (k in o.keys()) {
                val v = o.getJSONObject(k)
                val e = ProbeEntry(v.getBoolean("ok"), v.getLong("ms"), v.getLong("at"))
                if (LineRank.isFresh(e, now, ttlMs)) results[k] = e
            }
            Log.i(TAG, "rank: loaded ${results.size} saved results")
        } catch (e: Exception) {
            Log.w(TAG, "rank: ignore unreadable saved results", e)
        }
    }

    private fun saveLocked() {
        val f = store ?: return
        try {
            val now = clock()
            val o = JSONObject()
            for ((k, e) in results) {
                if (!LineRank.isFresh(e, now, ttlMs)) continue
                o.put(k, JSONObject().put("ok", e.ok).put("ms", e.millis).put("at", e.at))
            }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) {
            Log.w(TAG, "rank: save failed", e)
        }
    }

    private object SystemClockMs { fun now() = System.currentTimeMillis() }

    companion object {
        const val TAG = "LiveTv"
    }
}

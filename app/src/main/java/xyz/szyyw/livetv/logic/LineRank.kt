package xyz.szyyw.livetv.logic

import xyz.szyyw.livetv.playlist.Stream

/** 一条线路的一次测速结果：是否可用、拿到首包数据的耗时、测速时刻（毫秒时间戳）。 */
data class ProbeEntry(val ok: Boolean, val millis: Long, val at: Long)

/** 线路排序与选择的纯逻辑（无 Android 依赖，便于单测）。 */
object LineRank {
    const val TTL_MS = 30 * 60_000L
    const val RERUN_INTERVAL_MS = 10 * 60_000L

    /** 线路数达到这个值的频道才会被测速。 */
    const val MIN_LINES = 10

    fun isFresh(e: ProbeEntry?, now: Long, ttl: Long): Boolean = e != null && now - e.at in 0..ttl

    /** 测速成功且未过期。 */
    fun isGood(e: ProbeEntry?, now: Long, ttl: Long): Boolean = e != null && e.ok && isFresh(e, now, ttl)

    /** 判失败且未过期（过期的结果视为未测）。 */
    fun isBad(e: ProbeEntry?, now: Long, ttl: Long): Boolean = e != null && !e.ok && isFresh(e, now, ttl)

    /**
     * 排序：① 成功的按耗时升序 ② 未测的保持清单原序 ③ 失败的放最后（保持原序）。
     * 同耗时保持原序。重复 URL 只保留第一条。
     */
    fun order(
        streams: List<Stream>,
        results: Map<String, ProbeEntry>,
        now: Long,
        ttl: Long = TTL_MS,
    ): List<Stream> {
        val seen = HashSet<String>()
        val good = ArrayList<Stream>()
        val untested = ArrayList<Stream>()
        val bad = ArrayList<Stream>()
        for (s in streams) {
            if (!seen.add(s.url)) continue
            val e = results[s.url]
            when {
                isGood(e, now, ttl) -> good += s
                isBad(e, now, ttl) -> bad += s
                else -> untested += s
            }
        }
        val sortedGood = good.sortedBy { results.getValue(it.url).millis } // sortedBy 稳定
        return sortedGood + untested + bad
    }

    /**
     * 起播时选哪条：记忆的 URL 仍在列表里、且没被判失败 → 用它；否则第 1 条。
     * @param failed 该 URL 当前是否被判失败
     */
    fun pickStart(ordered: List<Stream>, remembered: String?, failed: (String) -> Boolean): Int {
        if (remembered != null) {
            val i = ordered.indexOfFirst { it.url == remembered }
            if (i >= 0 && !failed(remembered)) return i
        }
        return 0
    }

    /**
     * 自动故障转移的下一条：从 [from] 往后找第一条未判失败的；全部判失败时退化为顺序下一条。
     * 只有 1 条时返回 [from]。
     */
    fun nextCandidate(ordered: List<Stream>, from: Int, failed: (String) -> Boolean): Int {
        val n = ordered.size
        if (n <= 1) return from
        for (step in 1 until n) {
            val i = (from + step) % n
            if (!failed(ordered[i].url)) return i
        }
        return (from + 1) % n
    }

    /** 故障转移最多试几条：未判失败的条数；全部判失败则按全部线路试一圈。 */
    fun failoverLimit(ordered: List<Stream>, failed: (String) -> Boolean): Int {
        val good = ordered.count { !failed(it.url) }
        return if (good == 0) ordered.size else good
    }

    /** 是否该开始新一轮测速：从没测过、上次被打断、或距上次开始已超过间隔。 */
    fun shouldRun(lastRunAt: Long, now: Long, interval: Long, interrupted: Boolean): Boolean =
        interrupted || lastRunAt == 0L || now - lastRunAt > interval || now < lastRunAt

    /** 线路主机名（日志与列表里只显示它，不显示完整地址）。 */
    fun hostOf(url: String): String {
        val rest = url.substringAfter("://", url).substringBefore('/').substringBefore('?').substringBefore('#')
        val noUser = rest.substringAfterLast('@')
        return if (noUser.startsWith("[")) noUser.substringBefore(']') + "]" else noUser.substringBefore(':')
    }

    /** 耗时的显示：412 ms；超过 10000 ms 显示 >10 s。 */
    fun msLabel(millis: Long): String = if (millis > 10_000) ">10 s" else "$millis ms"

    /** 线路列表里的测速状态：412 ms / 失败 / 未测。 */
    fun statusLabel(e: ProbeEntry?, now: Long, ttl: Long = TTL_MS): String = when {
        isGood(e, now, ttl) -> msLabel(e!!.millis)
        isBad(e, now, ttl) -> "失败"
        else -> "未测"
    }
}

/** 同时测速数量限制：总数与同主机数。线程安全。 */
class HostLimiter(private val maxTotal: Int, private val maxPerHost: Int) {
    private val perHost = HashMap<String, Int>()
    private var total = 0

    @Synchronized fun tryAcquire(host: String): Boolean {
        if (total >= maxTotal || (perHost[host] ?: 0) >= maxPerHost) return false
        total++
        perHost[host] = (perHost[host] ?: 0) + 1
        return true
    }

    @Synchronized fun release(host: String) {
        val c = perHost[host] ?: return
        total--
        if (c <= 1) perHost.remove(host) else perHost[host] = c - 1
    }

    @Synchronized fun running(): Int = total
    @Synchronized fun runningOn(host: String): Int = perHost[host] ?: 0
}

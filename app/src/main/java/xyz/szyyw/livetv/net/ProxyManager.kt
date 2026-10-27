package xyz.szyyw.livetv.net

import android.util.Log
import xyz.szyyw.livetv.logic.FileStamp
import xyz.szyyw.livetv.logic.ProxyAddress
import xyz.szyyw.livetv.logic.ProxyLogic
import xyz.szyyw.livetv.logic.ProxyMode
import xyz.szyyw.livetv.logic.ProxyState
import xyz.szyyw.livetv.logic.Route
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 拉流代理的运行时状态：读取推送的 proxy.txt（修改时间变了才重读）、记录代理是否可达，
 * 并据此给出当前通道。[selector] 交给「视频流 / 测速」用的 OkHttp 客户端，每次连接时现查通道，
 * 因此通道切换不需要重建客户端。更新检查、更新包与自定义清单下载用的客户端不接这个 selector。
 * 线程安全；check/test 为阻塞调用。
 */
class ProxyManager(private val file: File?, private val mode: () -> ProxyMode) {
    @Volatile var address: ProxyAddress? = null
        private set
    private var stamp: FileStamp? = null
    @Volatile private var reachable = true
    /** 最近一次检查失败是因为认证（而不是连不上）。 */
    @Volatile var authFailed = false
        private set
    @Volatile var lastCheckAt = 0L
        private set

    val configured: Boolean get() = address != null
    /** 配置了代理且没有被设为「关闭」。 */
    val active: Boolean get() = configured && mode() != ProxyMode.OFF
    fun route(): Route = ProxyLogic.route(mode(), configured, reachable)
    fun state(): ProxyState = ProxyLogic.state(mode(), configured, reachable, authFailed)

    /** 重读 proxy.txt（文件修改时间或大小变了才读）。返回配置是否变化。 */
    @Synchronized
    fun reloadIfChanged(): Boolean {
        val f = file
        val now = if (f != null && f.isFile) FileStamp(f.lastModified(), f.length()) else null
        if (now == stamp) return false
        stamp = now
        val before = address
        address = if (now == null) null else runCatching { ProxyLogic.parse(f!!.readText()) }.getOrNull()
        reachable = true
        authFailed = false
        lastCheckAt = 0L
        Log.i(TAG, "proxy file: ${if (address == null) (if (now == null) "absent" else "invalid") else "configured"}")
        return before != address
    }

    /** 对代理做一次握手检查并记录结果。返回通道是否因此改变。 */
    fun check(): Boolean {
        val a = address ?: return false
        val before = route()
        val r = Socks5.handshake(a)
        lastCheckAt = System.currentTimeMillis()
        reachable = r.ok
        authFailed = r.authFailed
        Log.i(TAG, "proxy check: ${if (r.ok) "ok ${r.millis}ms" else "fail ${r.reason}"} route=${route()}")
        return route() != before
    }

    /** 用外部得到的连通结果更新可达状态（如「测试代理」）。返回通道是否因此改变。 */
    fun applyResult(ok: Boolean, authFailed: Boolean = false): Boolean {
        val before = route()
        reachable = ok
        this.authFailed = authFailed
        lastCheckAt = System.currentTimeMillis()
        return route() != before
    }

    fun test(): ProxyResult? = address?.let { Socks5.fullTest(it) }

    /** 当通道为代理（或被拦住）时让连接走 SOCKS5；目标主机名留给代理解析。 */
    val selector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val a = address
            return if (a != null && route() != Route.DIRECT) {
                listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(a.host, a.port)))
            } else listOf(Proxy.NO_PROXY)
        }
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {}
    }

    private companion object { const val TAG = "LiveTv" }
}

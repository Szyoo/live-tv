package xyz.szyyw.livetv.net

import xyz.szyyw.livetv.logic.ProxyAddress
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** 一次代理检查的结果；reason 只含原因，不含代理地址。 */
data class ProxyResult(val ok: Boolean, val millis: Long, val reason: String = "")

/** SOCKS5 探测（阻塞调用，须在后台线程）。不使用认证。 */
object Socks5 {
    const val HANDSHAKE_TIMEOUT_MS = 1_500
    const val TEST_HOST = "example.com"
    const val TEST_PORT = 80
    const val TEST_TIMEOUT_MS = 8_000

    /** 只做 TCP 连接 + SOCKS5 握手（问候），超时 [timeoutMs]。 */
    fun handshake(addr: ProxyAddress, timeoutMs: Int = HANDSHAKE_TIMEOUT_MS): ProxyResult {
        val t0 = System.nanoTime()
        return try {
            Socket().use { s -> greet(s, addr, timeoutMs) }
            ProxyResult(true, since(t0))
        } catch (e: Failure) {
            ProxyResult(false, since(t0), e.message ?: "未知错误")
        } catch (e: Exception) {
            ProxyResult(false, since(t0), describe(e))
        }
    }

    /** 完整测试：握手后经代理连接 example.com:80 并发一个 HTTP HEAD，读到状态行算成功。 */
    fun fullTest(addr: ProxyAddress, timeoutMs: Int = TEST_TIMEOUT_MS): ProxyResult {
        val t0 = System.nanoTime()
        return try {
            Socket().use { s ->
                greet(s, addr, timeoutMs)
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                val host = TEST_HOST.toByteArray()
                val req = byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf((TEST_PORT shr 8).toByte(), TEST_PORT.toByte())
                out.write(req); out.flush()
                val head = readFully(inp, 4)
                if (head[1].toInt() != 0) throw Failure("代理已连上，但不肯转发到测试地址（应答码 ${head[1].toInt() and 0xFF}）")
                val skip = when (head[3].toInt()) { 1 -> 6; 4 -> 18; 3 -> (readFully(inp, 1)[0].toInt() and 0xFF) + 2; else -> throw Failure("代理应答格式不对") }
                readFully(inp, skip)
                out.write("HEAD / HTTP/1.1\r\nHost: $TEST_HOST\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
                val line = readFully(inp, 5)
                if (String(line, Charsets.ISO_8859_1) != "HTTP/") throw Failure("代理已连上，但测试地址没有正常响应")
            }
            ProxyResult(true, since(t0))
        } catch (e: Failure) {
            ProxyResult(false, since(t0), e.message ?: "未知错误")
        } catch (e: Exception) {
            ProxyResult(false, since(t0), describe(e))
        }
    }

    private class Failure(msg: String) : IOException(msg)

    private fun greet(s: Socket, addr: ProxyAddress, timeoutMs: Int) {
        s.soTimeout = timeoutMs
        s.connect(InetSocketAddress(addr.host, addr.port), timeoutMs)
        val out = s.getOutputStream()
        out.write(byteArrayOf(5, 1, 0)); out.flush()
        val r = readFully(s.getInputStream(), 2)
        if (r[0].toInt() != 5) throw Failure("对方不是 SOCKS5 代理")
        if (r[1].toInt() != 0) throw Failure("代理要求认证，暂不支持")
    }

    private fun readFully(inp: java.io.InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = inp.read(b, got, n - got)
            if (r < 0) throw Failure("连接被对方关闭")
            got += r
        }
        return b
    }

    private fun since(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    private fun describe(e: Exception): String = when (e) {
        is SocketTimeoutException -> "连接超时"
        is java.net.ConnectException -> "连接被拒绝或网络不通"
        is java.net.UnknownHostException -> "无法解析代理主机名"
        else -> e.javaClass.simpleName
    }
}

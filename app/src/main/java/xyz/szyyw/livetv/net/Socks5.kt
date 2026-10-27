package xyz.szyyw.livetv.net

import xyz.szyyw.livetv.logic.ProxyAddress
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * 一次代理检查的结果；reason 只含原因，不含代理地址与用户名密码。
 * authFailed：失败原因是认证（代理要求用户名密码但没提供、或用户名密码被拒），与「连不上」区分。
 */
data class ProxyResult(val ok: Boolean, val millis: Long, val reason: String = "", val authFailed: Boolean = false)

/** SOCKS5 握手失败；auth 表示失败原因是认证。消息里不含地址和凭据。 */
class Socks5Failure(msg: String, val auth: Boolean = false) : IOException(msg)

/** SOCKS5 探测（阻塞调用，须在后台线程）。支持 RFC 1929 用户名/密码认证。 */
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
        } catch (e: Socks5Failure) {
            ProxyResult(false, since(t0), e.message ?: "未知错误", e.auth)
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
                request(s, TEST_HOST, TEST_PORT)
                out.write("HEAD / HTTP/1.1\r\nHost: $TEST_HOST\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
                val line = readFully(inp, 5)
                if (String(line, Charsets.ISO_8859_1) != "HTTP/") throw Socks5Failure("代理已连上，但测试地址没有正常响应")
            }
            ProxyResult(true, since(t0))
        } catch (e: Socks5Failure) {
            ProxyResult(false, since(t0), e.message ?: "未知错误", e.auth)
        } catch (e: Exception) {
            ProxyResult(false, since(t0), describe(e))
        }
    }

    /** 连上代理并完成方法协商（及用户名/密码认证）。失败抛 [Socks5Failure]。 */
    fun greet(s: Socket, addr: ProxyAddress, timeoutMs: Int) {
        s.soTimeout = timeoutMs
        s.connect(InetSocketAddress(addr.host, addr.port), timeoutMs)
        negotiate(s, addr)
    }

    /** 在已连上代理的 socket 上做方法协商与认证。 */
    fun negotiate(s: Socket, addr: ProxyAddress) {
        val out = s.getOutputStream()
        val inp = s.getInputStream()
        val cred = if (addr.hasCredentials) credentialBytes(addr) else null
        out.write(if (cred != null) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0)); out.flush()
        val r = readFully(inp, 2)
        if (r[0].toInt() != 5) throw Socks5Failure("对方不是 SOCKS5 代理")
        when (r[1].toInt() and 0xFF) {
            0x00 -> return
            0x02 -> {
                if (cred == null) throw Socks5Failure("代理要求用户名密码，但没有提供", auth = true)
                val (u, p) = cred
                out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p); out.flush()
                val a = readFully(inp, 2)
                if (a[0].toInt() != 1) throw Socks5Failure("代理的认证应答格式不对")
                if (a[1].toInt() != 0) throw Socks5Failure("用户名密码错误", auth = true)
            }
            0xFF -> throw Socks5Failure(
                if (cred == null) "代理要求用户名密码，但没有提供" else "代理不接受用户名密码", auth = true)
            else -> throw Socks5Failure("代理选择了不支持的认证方式")
        }
    }

    /** 发 CONNECT 请求目标（域名走 DOMAINNAME 交给代理解析，IPv4/IPv6 字面量走对应类型），读完应答。 */
    fun request(s: Socket, host: String, port: Int) {
        val out = s.getOutputStream()
        val inp = s.getInputStream()
        val ip = ipLiteral(host)
        val head = byteArrayOf(5, 1, 0)
        val dest = when {
            ip != null -> byteArrayOf(if (ip.size == 4) 1 else 4) + ip
            else -> {
                val h = host.toByteArray()
                if (h.size > 255) throw Socks5Failure("目标主机名过长")
                byteArrayOf(3, h.size.toByte()) + h
            }
        }
        out.write(head + dest + byteArrayOf((port shr 8).toByte(), port.toByte())); out.flush()
        val rep = readFully(inp, 4)
        if (rep[1].toInt() != 0) throw Socks5Failure("代理已连上，但不肯转发到目标地址（应答码 ${rep[1].toInt() and 0xFF}）")
        val skip = when (rep[3].toInt()) {
            1 -> 6; 4 -> 18
            3 -> (readFully(inp, 1)[0].toInt() and 0xFF) + 2
            else -> throw Socks5Failure("代理应答格式不对")
        }
        readFully(inp, skip)
    }

    private fun credentialBytes(addr: ProxyAddress): Pair<ByteArray, ByteArray> {
        val u = (addr.username ?: "").toByteArray()
        val p = (addr.password ?: "").toByteArray()
        if (u.isEmpty() || u.size > 255 || p.size > 255) throw Socks5Failure("用户名或密码长度不合法（各不超过 255 字节）")
        return u to p
    }

    /** 只识别 IPv4 点分十进制与含冒号的 IPv6 字面量，不做任何 DNS 解析。 */
    private fun ipLiteral(host: String): ByteArray? {
        val h = host.removePrefix("[").removeSuffix("]")
        if (Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(h)) {
            val parts = h.split('.').map { it.toInt() }
            if (parts.all { it in 0..255 }) return ByteArray(4) { parts[it].toByte() }
            return null
        }
        if (h.contains(':') && Regex("""[0-9A-Fa-f:.]+""").matches(h)) {
            return try { java.net.InetAddress.getByName(h).address } catch (e: Exception) { null }
        }
        return null
    }

    private fun readFully(inp: java.io.InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = inp.read(b, got, n - got)
            if (r < 0) throw Socks5Failure("连接被对方关闭")
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

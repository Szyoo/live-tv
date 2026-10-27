package xyz.szyyw.livetv.net

import org.junit.Assert.*
import org.junit.Test
import xyz.szyyw.livetv.logic.ProxyAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class Socks5Test {
    /** 本机假 SOCKS5：按脚本回应问候；connectReply != null 时继续处理 CONNECT 并回一个 HTTP 状态行。 */
    private fun fake(greeting: ByteArray?, connectRep: Int? = null, httpOk: Boolean = true): ServerSocket {
        val ss = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            try {
                ss.accept().use { s ->
                    val i = s.getInputStream(); val o = s.getOutputStream()
                    i.readNBytes(3)
                    if (greeting == null) return@use
                    o.write(greeting); o.flush()
                    if (connectRep == null) return@use
                    val h = i.readNBytes(5); i.readNBytes((h[4].toInt() and 0xFF) + 2)
                    o.write(byteArrayOf(5, connectRep.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
                    if (connectRep != 0) return@use
                    i.read()
                    o.write((if (httpOk) "HTTP/1.1 200 OK\r\n" else "garbage!!\r\n").toByteArray()); o.flush()
                }
            } catch (_: Exception) {}
        }
        return ss
    }
    private fun addr(ss: ServerSocket) = ProxyAddress("127.0.0.1", ss.localPort)

    @Test fun handshakeOk() = fake(byteArrayOf(5, 0)).use { assertTrue(Socks5.handshake(addr(it)).ok) }
    @Test fun handshakeNotSocks() = fake(byteArrayOf(0x48, 0x54)).use {
        val r = Socks5.handshake(addr(it)); assertFalse(r.ok); assertTrue(r.reason.contains("SOCKS5"))
    }
    @Test fun handshakeNeedsAuth() = fake(byteArrayOf(5, 2)).use {
        val r = Socks5.handshake(addr(it)); assertFalse(r.ok); assertTrue(r.authFailed); assertTrue(r.reason.contains("用户名密码"))
    }
    @Test fun handshakeClosed() = fake(null).use { assertFalse(Socks5.handshake(addr(it)).ok) }
    @Test fun handshakeRefused() {
        val p = ServerSocket(0).use { it.localPort } // 关掉后端口无人监听
        val r = Socks5.handshake(ProxyAddress("127.0.0.1", p)); assertFalse(r.ok); assertTrue(r.reason.isNotEmpty())
    }
    @Test fun handshakeTimeout() {
        // 只 accept 不回话
        val ss = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) { try { ss.accept().use { Thread.sleep(3000) } } catch (_: Exception) {} }
        ss.use {
            val r = Socks5.handshake(addr(it), 300)
            assertFalse(r.ok); assertEquals("连接超时", r.reason)
        }
    }
    @Test fun fullTestOk() = fake(byteArrayOf(5, 0), 0).use { assertTrue(Socks5.fullTest(addr(it), 2000).ok) }
    @Test fun fullTestRefusedByProxy() = fake(byteArrayOf(5, 0), 5).use {
        val r = Socks5.fullTest(addr(it), 2000); assertFalse(r.ok); assertTrue(r.reason.contains("不肯转发"))
    }
    @Test fun fullTestBadHttp() = fake(byteArrayOf(5, 0), 0, httpOk = false).use {
        assertFalse(Socks5.fullTest(addr(it), 2000).ok)
    }

    // ---------- 用户名/密码认证（RFC 1929） ----------

    private val user = "testuser"
    private val pass = "testpass"

    /** 假 SOCKS5 服务端：记录客户端声明的方法、收到的凭据与 CONNECT 的地址类型。 */
    private class Auth(val ss: ServerSocket) : AutoCloseable {
        val methods = CopyOnWriteArrayList<Int>()
        val creds = CopyOnWriteArrayList<String>()
        val atyps = CopyOnWriteArrayList<Int>()
        override fun close() = ss.close()
    }

    /**
     * select：服务端选的方法（0/2/0xFF）；expect：要求的 (用户, 密码)，null 表示不校验；
     * authStatus：认证应答状态（0 成功）；connectRep != null 时继续处理 CONNECT。
     */
    private fun fakeAuth(select: Int, expect: Pair<String, String>? = null, connectRep: Int? = null): Auth {
        val ss = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val rec = Auth(ss)
        thread(isDaemon = true) {
            try {
                ss.accept().use { s ->
                    val i = s.getInputStream(); val o = s.getOutputStream()
                    i.readNBytes(1)
                    val n = i.read()
                    i.readNBytes(n).forEach { rec.methods.add(it.toInt() and 0xFF) }
                    o.write(byteArrayOf(5, select.toByte())); o.flush()
                    if (select == 0xFF) return@use
                    if (select == 2) {
                        i.readNBytes(1)
                        val u = String(i.readNBytes(i.read()))
                        val p = String(i.readNBytes(i.read()))
                        rec.creds.add("$u:$p")
                        val ok = expect == null || (expect.first == u && expect.second == p)
                        o.write(byteArrayOf(1, if (ok) 0 else 1)); o.flush()
                        if (!ok) return@use
                    }
                    if (connectRep == null) return@use
                    val h = i.readNBytes(4)
                    rec.atyps.add(h[3].toInt())
                    when (h[3].toInt()) {
                        1 -> i.readNBytes(4)
                        4 -> i.readNBytes(16)
                        else -> i.readNBytes(i.read())
                    }
                    i.readNBytes(2)
                    o.write(byteArrayOf(5, connectRep.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
                    if (connectRep != 0) return@use
                    i.read()
                    o.write("HTTP/1.1 200 OK\r\n".toByteArray()); o.flush()
                }
            } catch (_: Exception) {}
        }
        return rec
    }

    private fun withAuth(ss: ServerSocket, u: String? = user, p: String? = pass) = ProxyAddress("127.0.0.1", ss.localPort, u, p)

    @Test fun noAuthOffersOnlyMethod0() = fakeAuth(0).use {
        val r = Socks5.handshake(withAuth(it.ss, null, null))
        assertTrue(r.ok); assertFalse(r.authFailed)
        assertEquals(listOf(0), it.methods)
        assertTrue(it.creds.isEmpty())
    }
    @Test fun withCredsOffersBothMethodsButServerPicksNone() = fakeAuth(0).use {
        val r = Socks5.handshake(withAuth(it.ss))
        assertTrue(r.ok)
        assertEquals(listOf(0, 2), it.methods)
        assertTrue("服务端选 0 时不应发送凭据", it.creds.isEmpty())
    }
    @Test fun authSuccess() = fakeAuth(2, user to pass).use {
        val r = Socks5.handshake(withAuth(it.ss))
        assertTrue(r.reason, r.ok); assertFalse(r.authFailed)
        assertEquals(listOf(0, 2), it.methods)
        assertEquals(listOf("$user:$pass"), it.creds)
    }
    @Test fun authWrongPassword() = fakeAuth(2, user to pass).use {
        val r = Socks5.handshake(withAuth(it.ss, user, "wrongpass"))
        assertFalse(r.ok); assertTrue(r.authFailed)
        assertTrue(r.reason, r.reason.contains("用户名密码错误"))
        assertFalse(r.reason.contains(user)); assertFalse(r.reason.contains("wrongpass"))
    }
    @Test fun authRequiredButNotProvided() = fakeAuth(2).use {
        val r = Socks5.handshake(withAuth(it.ss, null, null))
        assertFalse(r.ok); assertTrue(r.authFailed)
        assertTrue(r.reason, r.reason.contains("没有提供"))
        assertTrue(it.creds.isEmpty())
    }
    @Test fun serverRejectsAllMethodsWithoutCreds() = fakeAuth(0xFF).use {
        val r = Socks5.handshake(withAuth(it.ss, null, null))
        assertFalse(r.ok); assertTrue(r.authFailed); assertTrue(r.reason.contains("没有提供"))
    }
    @Test fun serverRejectsAllMethodsWithCreds() = fakeAuth(0xFF).use {
        val r = Socks5.handshake(withAuth(it.ss))
        assertFalse(r.ok); assertTrue(r.authFailed); assertTrue(r.reason.contains("不接受用户名密码"))
    }
    @Test fun nonAuthFailuresAreNotMarkedAuth() {
        fake(byteArrayOf(0x48, 0x54)).use { assertFalse(Socks5.handshake(addr(it)).authFailed) }
        fake(null).use { assertFalse(Socks5.handshake(addr(it)).authFailed) }
        val p = ServerSocket(0).use { it.localPort }
        assertFalse(Socks5.handshake(ProxyAddress("127.0.0.1", p, user, pass)).authFailed)
    }
    @Test fun fullTestWithAuth() = fakeAuth(2, user to pass, connectRep = 0).use {
        val r = Socks5.fullTest(withAuth(it.ss), 2000)
        assertTrue(r.reason, r.ok)
        assertEquals(listOf(3), it.atyps) // 域名交给代理解析
    }
    @Test fun fullTestWrongPassword() = fakeAuth(2, user to pass, connectRep = 0).use {
        val r = Socks5.fullTest(withAuth(it.ss, user, "wrongpass"), 2000)
        assertFalse(r.ok); assertTrue(r.authFailed)
    }
    @Test fun requestUsesIpv4TypeForIpTargets() = fakeAuth(0, connectRep = 0).use {
        java.net.Socket().use { s ->
            Socks5.greet(s, withAuth(it.ss, null, null), 2000)
            Socks5.request(s, "203.0.113.7", 80)
        }
        assertEquals(listOf(1), it.atyps)
    }
    @Test fun requestUsesDomainTypeForNames() = fakeAuth(0, connectRep = 0).use {
        java.net.Socket().use { s ->
            Socks5.greet(s, withAuth(it.ss, null, null), 2000)
            Socks5.request(s, "example.com", 80)
        }
        assertEquals(listOf(3), it.atyps)
    }
    @Test fun resultsNeverLeakCredentials() {
        val results = listOf(
            fakeAuth(2, user to pass).use { Socks5.handshake(withAuth(it.ss, user, "wrongpass")) },
            fakeAuth(2).use { Socks5.handshake(withAuth(it.ss, null, null)) },
            fakeAuth(0xFF).use { Socks5.handshake(withAuth(it.ss)) },
            fakeAuth(2, user to pass).use { Socks5.handshake(withAuth(it.ss)) },
        )
        for (r in results) for (secret in listOf(user, pass, "wrongpass", "127.0.0.1")) {
            assertFalse("$secret 泄露在 $r", r.toString().contains(secret))
        }
    }
}

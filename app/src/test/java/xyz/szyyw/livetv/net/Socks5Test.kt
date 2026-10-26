package xyz.szyyw.livetv.net

import org.junit.Assert.*
import org.junit.Test
import xyz.szyyw.livetv.logic.ProxyAddress
import java.net.ServerSocket
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
        val r = Socks5.handshake(addr(it)); assertFalse(r.ok); assertTrue(r.reason.contains("认证"))
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
}

package xyz.szyyw.livetv.net

import org.junit.Assert.*
import org.junit.Test
import xyz.szyyw.livetv.logic.ProxyAddress

class ProxyAuthTest {
    private val a = ProxyAddress("127.0.0.1", 18901, "testuser", "testpass")

    @Test fun matchesOnlyOurProxy() {
        assertTrue(ProxyAuth.matches(a, "SOCKS5", "127.0.0.1", 18901))
        assertTrue(ProxyAuth.matches(a, "socks5", "127.0.0.1", 18901))
        assertTrue(ProxyAuth.matches(ProxyAddress("Proxy.Example.com", 1, "u", "p"), "SOCKS5", "proxy.example.com", 1))
    }
    @Test fun rejectsOthers() {
        assertFalse(ProxyAuth.matches(a, "http", "127.0.0.1", 18901))
        assertFalse(ProxyAuth.matches(a, "https", "127.0.0.1", 18901))
        assertFalse(ProxyAuth.matches(a, "SOCKS5", "127.0.0.1", 18902))
        assertFalse(ProxyAuth.matches(a, "SOCKS5", "example.com", 18901))
        assertFalse(ProxyAuth.matches(a, null, "127.0.0.1", 18901))
        assertFalse(ProxyAuth.matches(a, "SOCKS5", null, 18901))
        assertFalse(ProxyAuth.matches(null, "SOCKS5", "127.0.0.1", 18901))
        assertFalse(ProxyAuth.matches(ProxyAddress("127.0.0.1", 18901), "SOCKS5", "127.0.0.1", 18901)) // 没有凭据
    }
}

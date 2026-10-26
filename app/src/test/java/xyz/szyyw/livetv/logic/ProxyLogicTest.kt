package xyz.szyyw.livetv.logic

import org.junit.Assert.*
import org.junit.Test

class ProxyLogicTest {
    @Test fun parseBasic() {
        assertEquals(ProxyAddress("10.0.2.2", 1080), ProxyLogic.parse("socks5://10.0.2.2:1080"))
        assertEquals(ProxyAddress("proxy.example.com", 8080), ProxyLogic.parse("socks5://proxy.example.com:8080\n"))
        assertEquals(ProxyAddress("::1", 1080), ProxyLogic.parse("socks5://[::1]:1080"))
        assertEquals(ProxyAddress("a.example.com", 1), ProxyLogic.parse("SOCKS5H://a.example.com:1"))
    }
    @Test fun parseEmptyAndComments() {
        assertNull(ProxyLogic.parse(null))
        assertNull(ProxyLogic.parse(""))
        assertNull(ProxyLogic.parse("\n\n   \n"))
        assertNull(ProxyLogic.parse("# 只有注释\n# socks5://10.0.2.2:1080"))
        assertEquals(ProxyAddress("10.0.2.2", 1080), ProxyLogic.parse("# 说明\n\n  socks5://10.0.2.2:1080  \r\n"))
    }
    @Test fun parseWhitespaceAndBom() {
        assertEquals(ProxyAddress("10.0.2.2", 1080), ProxyLogic.parse("\uFEFF  socks5://10.0.2.2:1080\t"))
        assertNull(ProxyLogic.parse("socks5://10.0.2.2 :1080"))
        assertNull(ProxyLogic.parse("socks5:// 10.0.2.2:1080"))
    }
    @Test fun parseInvalid() {
        assertNull(ProxyLogic.parse("10.0.2.2:1080"))
        assertNull(ProxyLogic.parse("http://10.0.2.2:1080"))
        assertNull(ProxyLogic.parse("socks5://10.0.2.2"))
        assertNull(ProxyLogic.parse("socks5://:1080"))
        assertNull(ProxyLogic.parse("socks5://10.0.2.2:0"))
        assertNull(ProxyLogic.parse("socks5://10.0.2.2:65536"))
        assertNull(ProxyLogic.parse("socks5://10.0.2.2:abc"))
        assertNull(ProxyLogic.parse("socks5://u:p@10.0.2.2:1080"))
        // 只看第一条有效行：它非法就整体非法
        assertNull(ProxyLogic.parse("garbage\nsocks5://10.0.2.2:1080"))
    }
    @Test fun toStringHidesAddress() {
        assertFalse(ProxyAddress("10.0.2.2", 1080).toString().contains("10.0.2.2"))
    }

    // 三档 × 是否配置 × 代理是否可用
    @Test fun routeMatrix() {
        for (reachable in listOf(true, false)) {
            for (mode in ProxyMode.values()) {
                assertEquals("未配置 $mode $reachable", Route.DIRECT, ProxyLogic.route(mode, false, reachable))
                assertEquals(ProxyState.NOT_CONFIGURED, ProxyLogic.state(mode, false, reachable))
            }
            assertEquals(Route.DIRECT, ProxyLogic.route(ProxyMode.OFF, true, reachable))
            assertEquals(ProxyState.DISABLED, ProxyLogic.state(ProxyMode.OFF, true, reachable))
        }
        assertEquals(Route.PROXY, ProxyLogic.route(ProxyMode.AUTO, true, true))
        assertEquals(Route.DIRECT, ProxyLogic.route(ProxyMode.AUTO, true, false))
        assertEquals(Route.PROXY, ProxyLogic.route(ProxyMode.ALWAYS, true, true))
        assertEquals(Route.BLOCKED, ProxyLogic.route(ProxyMode.ALWAYS, true, false))
        assertEquals(ProxyState.IN_USE, ProxyLogic.state(ProxyMode.AUTO, true, true))
        assertEquals(ProxyState.FALLBACK_DIRECT, ProxyLogic.state(ProxyMode.AUTO, true, false))
        assertEquals(ProxyState.IN_USE, ProxyLogic.state(ProxyMode.ALWAYS, true, true))
        assertEquals(ProxyState.BLOCKED, ProxyLogic.state(ProxyMode.ALWAYS, true, false))
    }
    @Test fun labelsAndTags() {
        assertEquals("代理不可用，已直连", ProxyState.FALLBACK_DIRECT.label)
        assertEquals("direct", ProxyLogic.routeTag(Route.DIRECT))
        assertEquals("proxy", ProxyLogic.routeTag(Route.PROXY))
        assertEquals("proxy", ProxyLogic.routeTag(Route.BLOCKED))
        assertEquals(ProxyMode.AUTO, Settings.cycle(ProxyMode.OFF, +1))
    }
}

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
        // 只看第一条有效行：它非法就整体非法
        assertNull(ProxyLogic.parse("garbage\nsocks5://10.0.2.2:1080"))
    }
    @Test fun toStringHidesAddress() {
        assertFalse(ProxyAddress("10.0.2.2", 1080).toString().contains("10.0.2.2"))
    }

    // ---- 用户名/密码 ----
    @Test fun parseWithAuth() {
        assertEquals(ProxyAddress("10.0.2.2", 1080, "testuser", "testpass"), ProxyLogic.parse("socks5://testuser:testpass@10.0.2.2:1080"))
        assertEquals(ProxyAddress("proxy.example.com", 8080, "testuser", "testpass"), ProxyLogic.parse("socks5h://testuser:testpass@proxy.example.com:8080/\n"))
        assertEquals(ProxyAddress("::1", 1080, "u", "p"), ProxyLogic.parse("# 说明\nsocks5://u:p@[::1]:1080"))
        val a = ProxyLogic.parse("socks5://testuser:testpass@10.0.2.2:1080")!!
        assertTrue(a.hasCredentials)
        assertFalse(ProxyLogic.parse("socks5://10.0.2.2:1080")!!.hasCredentials)
    }
    @Test fun parsePercentEncoded() {
        assertEquals(ProxyAddress("10.0.2.2", 1080, "us@er", "p@ss:w/rd%"),
            ProxyLogic.parse("socks5://us%40er:p%40ss%3Aw%2Frd%25@10.0.2.2:1080"))
        assertEquals("密码", ProxyLogic.parse("socks5://u:%E5%AF%86%E7%A0%81@10.0.2.2:1080")!!.password)
        assertEquals("a+b", ProxyLogic.parse("socks5://u:a+b@10.0.2.2:1080")!!.password) // + 不当空格
        assertEquals("p@ss", ProxyLogic.parse("socks5://u:p%40ss@10.0.2.2:1080")!!.password)
    }
    @Test fun parseMissingPassword() {
        // 只有用户名：密码按空串处理
        assertEquals(ProxyAddress("10.0.2.2", 1080, "testuser", ""), ProxyLogic.parse("socks5://testuser@10.0.2.2:1080"))
        assertEquals(ProxyAddress("10.0.2.2", 1080, "testuser", ""), ProxyLogic.parse("socks5://testuser:@10.0.2.2:1080"))
    }
    @Test fun parseAuthInvalid() {
        assertNull(ProxyLogic.parse("socks5://:testpass@10.0.2.2:1080"))       // 空用户名
        assertNull(ProxyLogic.parse("socks5://@10.0.2.2:1080"))
        assertNull(ProxyLogic.parse("socks5://u:p@w@10.0.2.2:1080"))           // 未编码的 @
        assertNull(ProxyLogic.parse("socks5://u:p:q@10.0.2.2:1080"))           // 未编码的 :
        assertNull(ProxyLogic.parse("socks5://u:p%4@10.0.2.2:1080"))           // 不完整的编码
        assertNull(ProxyLogic.parse("socks5://u:p%ZZ@10.0.2.2:1080"))
        assertNull(ProxyLogic.parse("socks5://u:p%@10.0.2.2:1080"))
        assertNull(ProxyLogic.parse("socks5://u:%FF%FE@10.0.2.2:1080"))        // 不是合法 UTF-8
        assertNull(ProxyLogic.parse("socks5://u:p@10.0.2.2"))                  // 缺端口
        assertNull(ProxyLogic.parse("socks5://u: p@10.0.2.2:1080"))
        assertNull(ProxyLogic.parse("socks5://u:p@:1080"))
        assertNull(ProxyLogic.parse("socks5://" + "u".repeat(256) + ":p@10.0.2.2:1080"))
        assertNull(ProxyLogic.parse("socks5://u:" + "p".repeat(256) + "@10.0.2.2:1080"))
        assertNotNull(ProxyLogic.parse("socks5://" + "u".repeat(255) + ":" + "p".repeat(255) + "@10.0.2.2:1080"))
    }
    @Test fun toStringHidesCredentialsAndAddress() {
        val a = ProxyLogic.parse("socks5://testuser:testpass@10.0.2.2:18901")!!
        for (out in listOf(a.toString(), "$a", listOf(a).toString(), mapOf("k" to a).toString())) {
            for (secret in listOf("testuser", "testpass", "10.0.2.2", "18901")) assertFalse("$secret 泄露在 $out", out.contains(secret))
        }
    }
    @Test fun authFailedStates() {
        assertEquals(ProxyState.AUTH_FAILED_DIRECT, ProxyLogic.state(ProxyMode.AUTO, true, false, authFailed = true))
        assertEquals(ProxyState.AUTH_FAILED_BLOCKED, ProxyLogic.state(ProxyMode.ALWAYS, true, false, authFailed = true))
        assertEquals(ProxyState.FALLBACK_DIRECT, ProxyLogic.state(ProxyMode.AUTO, true, false, authFailed = false))
        assertEquals(ProxyState.IN_USE, ProxyLogic.state(ProxyMode.AUTO, true, true, authFailed = true))
        assertEquals(ProxyState.DISABLED, ProxyLogic.state(ProxyMode.OFF, true, false, authFailed = true))
        assertEquals("代理认证失败，已直连", ProxyState.AUTH_FAILED_DIRECT.label)
        // 认证失败时路由与普通不可用一致：自动回退直连，始终使用不回退
        assertEquals(Route.DIRECT, ProxyLogic.route(ProxyMode.AUTO, true, false))
        assertEquals(Route.BLOCKED, ProxyLogic.route(ProxyMode.ALWAYS, true, false))
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

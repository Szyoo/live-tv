package xyz.szyyw.livetv.net

import xyz.szyyw.livetv.logic.ProxyAddress
import java.net.Authenticator
import java.net.PasswordAuthentication

/**
 * 让系统的 SOCKS5 客户端（视频流与测速的 OkHttp 连接）在代理要求认证时拿到用户名密码。
 * 只对「协议为 SOCKS、主机端口正好是当前代理」的请求返回凭据，其余一律返回 null，不影响其他网络请求。
 */
object ProxyAuth {
    /** 请求者是否就是 [addr] 这个代理（纯函数，便于单测）。 */
    fun matches(addr: ProxyAddress?, protocol: String?, host: String?, port: Int): Boolean =
        addr != null && addr.hasCredentials &&
            protocol != null && protocol.startsWith("SOCKS", ignoreCase = true) &&
            port == addr.port && host != null && host.equals(addr.host, ignoreCase = true)

    fun install(current: () -> ProxyAddress?) {
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val a = current()
                if (!matches(a, requestingProtocol, requestingHost, requestingPort)) return null
                return PasswordAuthentication(a!!.username, (a.password ?: "").toCharArray())
            }
        })
    }
}

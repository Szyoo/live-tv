package xyz.szyyw.livetv.logic

/** 拉流代理的纯逻辑（无 Android 依赖，便于单测）：proxy.txt 解析、三档设置、通道选择。 */

enum class ProxyMode(val label: String) { AUTO("自动"), ALWAYS("始终使用"), OFF("关闭") }

/** 拉流实际走哪条通道：直连 / 代理 / 被拦住（「始终使用」但代理不可用，不回退直连）。 */
enum class Route { DIRECT, PROXY, BLOCKED }

/** 设置页里显示的当前状态。 */
enum class ProxyState(val label: String) {
    NOT_CONFIGURED("未配置"),
    DISABLED("已关闭"),
    IN_USE("使用中"),
    FALLBACK_DIRECT("代理不可用，已直连"),
    BLOCKED("代理不可用，未回退直连"),
    AUTH_FAILED_DIRECT("代理认证失败，已直连"),
    AUTH_FAILED_BLOCKED("代理认证失败，未回退直连"),
}

/**
 * SOCKS5 代理地址，可带 RFC 1929 用户名/密码（username 为 null 表示不带认证）。
 * toString 不含主机、端口、用户名和密码，避免进日志。
 */
data class ProxyAddress(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
) {
    val hasCredentials: Boolean get() = username != null
    override fun toString() = "ProxyAddress(***)"
}

object ProxyLogic {
    const val FILE_NAME = "proxy.txt"

    /** RFC 1929 的用户名、密码各最多 255 字节。 */
    const val MAX_CRED_BYTES = 255

    private val LINE = Regex(
        """^socks5h?://(?:([^\s:/@\[\]?#]+)(?::([^\s:/@\[\]?#]*))?@)?(\[[0-9A-Fa-f:.]+]|[^\s:/\[\]@?#]+):(\d{1,5})/?$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 解析 proxy.txt：忽略空行与 # 开头的行，取第一条有效行，格式
     * `socks5://[用户[:密码]@]主机:端口`（也接受 socks5h；主机可为 [IPv6]）。
     * 用户名、密码按百分号编码解码（密码里的 @ : / 等须写成 %40 %3A %2F）；缺密码视为空密码。
     * 没有有效行、格式不对（含空用户名、非法编码、凭据超过 255 字节）返回 null。
     */
    fun parse(text: String?): ProxyAddress? {
        val line = text?.lineSequence()?.map { it.trim().trimStart('\uFEFF').trim() }
            ?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        val m = LINE.matchEntire(line) ?: return null
        val port = m.groupValues[4].toInt()
        if (port !in 1..65535) return null
        val host = m.groupValues[3].removePrefix("[").removeSuffix("]")
        if (m.groups[1] == null) return ProxyAddress(host, port)
        val user = percentDecode(m.groupValues[1]) ?: return null
        val pass = percentDecode(m.groupValues[2]) ?: return null
        if (user.isEmpty() || user.toByteArray().size > MAX_CRED_BYTES || pass.toByteArray().size > MAX_CRED_BYTES) return null
        return ProxyAddress(host, port, user, pass)
    }

    /** 百分号解码（UTF-8，+ 不当空格）；编码不完整或不是合法 UTF-8 返回 null。 */
    internal fun percentDecode(s: String): String? {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 3 > s.length) return null
                val hex = s.substring(i + 1, i + 3)
                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                val v = hex.toInt(16)
                out.write(v); i += 3
            } else {
                out.write(c.toString().toByteArray()); i++
            }
        }
        return try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
        } catch (e: java.nio.charset.CharacterCodingException) { null }
    }

    /**
     * 通道选择。
     * 关闭 / 未配置 → 直连；自动：代理可用走代理、不可用直连；始终使用：代理可用走代理、不可用被拦住。
     */
    fun route(mode: ProxyMode, configured: Boolean, reachable: Boolean): Route = when {
        !configured || mode == ProxyMode.OFF -> Route.DIRECT
        reachable -> Route.PROXY
        mode == ProxyMode.AUTO -> Route.DIRECT
        else -> Route.BLOCKED
    }

    /** authFailed：最近一次检查是因为认证失败而不可用（与「连不上」区分显示）。 */
    fun state(mode: ProxyMode, configured: Boolean, reachable: Boolean, authFailed: Boolean = false): ProxyState = when {
        !configured -> ProxyState.NOT_CONFIGURED
        mode == ProxyMode.OFF -> ProxyState.DISABLED
        else -> when (route(mode, configured, reachable)) {
            Route.PROXY -> ProxyState.IN_USE
            Route.DIRECT -> if (authFailed) ProxyState.AUTH_FAILED_DIRECT else ProxyState.FALLBACK_DIRECT
            Route.BLOCKED -> if (authFailed) ProxyState.AUTH_FAILED_BLOCKED else ProxyState.BLOCKED
        }
    }

    /** 测速结果所属的通道标记（用于分开保存、互不混用）。 */
    fun routeTag(route: Route): String = if (route == Route.DIRECT) "direct" else "proxy"

    /** 小标记：直连时为空（不显示）。 */
    fun routeMark(route: Route): String = when (route) { Route.PROXY -> "代理"; else -> "直连" }
}

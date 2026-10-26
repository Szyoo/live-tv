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
}

/** SOCKS5 代理地址。toString 不含主机与端口，避免进日志。 */
data class ProxyAddress(val host: String, val port: Int) {
    override fun toString() = "ProxyAddress(***)"
}

object ProxyLogic {
    const val FILE_NAME = "proxy.txt"

    /**
     * 解析 proxy.txt：忽略空行与 # 开头的行，取第一条有效行，格式 `socks5://主机:端口`
     * （也接受 socks5h；主机可为 [IPv6]）。没有有效行或格式不对返回 null。
     */
    fun parse(text: String?): ProxyAddress? {
        val line = text?.lineSequence()?.map { it.trim().trimStart('\uFEFF').trim() }
            ?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        val m = Regex("""^socks5h?://(\[[0-9A-Fa-f:.]+]|[^\s:/\[\]@?#]+):(\d{1,5})/?$""", RegexOption.IGNORE_CASE).matchEntire(line)
            ?: return null
        val port = m.groupValues[2].toInt()
        if (port !in 1..65535) return null
        return ProxyAddress(m.groupValues[1].removePrefix("[").removeSuffix("]"), port)
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

    fun state(mode: ProxyMode, configured: Boolean, reachable: Boolean): ProxyState = when {
        !configured -> ProxyState.NOT_CONFIGURED
        mode == ProxyMode.OFF -> ProxyState.DISABLED
        else -> when (route(mode, configured, reachable)) {
            Route.PROXY -> ProxyState.IN_USE
            Route.DIRECT -> ProxyState.FALLBACK_DIRECT
            Route.BLOCKED -> ProxyState.BLOCKED
        }
    }

    /** 测速结果所属的通道标记（用于分开保存、互不混用）。 */
    fun routeTag(route: Route): String = if (route == Route.DIRECT) "direct" else "proxy"

    /** 小标记：直连时为空（不显示）。 */
    fun routeMark(route: Route): String = when (route) { Route.PROXY -> "代理"; else -> "直连" }
}

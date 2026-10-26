package xyz.szyyw.livetv.logic

/** 「无操作自动停止」的纯逻辑：时间由调用方传入（elapsedRealtime），便于单测。 */
object IdleLogic {
    /** 设置可选小时数；0 = 关。 */
    val HOUR_OPTIONS = listOf(0, 2, 3, 5, 8)
    const val DEFAULT_HOURS = 5
    const val HOUR_MS = 3_600_000L

    fun hoursLabel(h: Int) = if (h == 0) "关" else "$h 小时"

    /** 阈值毫秒数；override > 0（仅调试）优先；hours <= 0 表示关闭，返回 0。 */
    fun thresholdMs(hours: Int, overrideMs: Long = 0): Long =
        if (overrideMs > 0) overrideMs else if (hours > 0) hours * HOUR_MS else 0L

    /** 距离触发还剩多少毫秒；阈值为 0（关闭）返回 Long.MAX_VALUE；已到点返回 0。时钟回拨视为刚按过键。 */
    fun remainingMs(now: Long, lastKeyAt: Long, thresholdMs: Long): Long {
        if (thresholdMs <= 0) return Long.MAX_VALUE
        val idle = now - lastKeyAt
        if (idle < 0) return thresholdMs
        return (thresholdMs - idle).coerceAtLeast(0)
    }

    fun isIdle(now: Long, lastKeyAt: Long, thresholdMs: Long): Boolean = thresholdMs > 0 && remainingMs(now, lastKeyAt, thresholdMs) == 0L

    /** 对话框里的时长文字：整小时显示「N 小时」，否则分钟 / 秒（调试用的短阈值）。 */
    fun durationLabel(ms: Long): String = when {
        ms % HOUR_MS == 0L -> "${ms / HOUR_MS} 小时"
        ms % 60_000L == 0L -> "${ms / 60_000L} 分钟"
        else -> "${ms / 1000} 秒"
    }
}

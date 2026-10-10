package xyz.szyyw.livetv.logic

/**
 * 数字键选台状态机（纯 Kotlin，时间由调用方传入，便于测试）。
 * 累积数字；[timeoutMs] 内无新输入或确认后提交。
 */
class ChannelNumberInput(private val timeoutMs: Long = 1500, private val maxDigits: Int = 4) {
    private var buffer = ""
    private var lastAt = 0L

    val active: Boolean get() = buffer.isNotEmpty()

    /** 屏幕显示用，如 "1 2"。 */
    val display: String get() = buffer.toCharArray().joinToString(" ")

    fun digit(d: Int, nowMs: Long): String {
        require(d in 0..9)
        if (buffer.length >= maxDigits) buffer = ""
        buffer += d
        lastAt = nowMs
        return display
    }

    /** 超时则提交并返回编号，否则 null。 */
    fun poll(nowMs: Long): Int? = if (active && nowMs - lastAt >= timeoutMs) commit() else null

    /** 按确认：立即提交。 */
    fun confirm(): Int? = if (active) commit() else null

    fun cancel() { buffer = "" }

    private fun commit(): Int? {
        val n = buffer.toIntOrNull()
        buffer = ""
        return n
    }
}

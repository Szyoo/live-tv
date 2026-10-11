package xyz.szyyw.livetv.logic

/** 设置项的取值枚举与纯逻辑（无 Android 依赖，便于单测）。 */

enum class ResizeOption(val label: String) { FIT("适应"), ZOOM("铺满裁剪"), FILL("拉伸") }
enum class VerticalMode(val label: String) { UP_PREV("上 = 上一台"), UP_NEXT("上 = 下一台") }
enum class HorizontalMode(val label: String) { LINE("换线路"), CHANNEL("换台") }
enum class ExitMode(val label: String) { DOUBLE_BACK("按两次返回退出"), SINGLE_BACK("按一次直接退出") }
enum class StartMode(val label: String) { LAST("上次观看的频道"), FIXED("指定频道") }
enum class ClockMode(val label: String) { WITH_INFO("随信息条显示"), ALWAYS("常驻"), HIDDEN("隐藏") }

enum class Dir { UP, DOWN, LEFT, RIGHT }

/** 播放画面里一次方向键的效果：channelDelta 换台步长、lineDelta 换线路步长（至多一个非 0）。 */
data class KeyAction(val channelDelta: Int = 0, val lineDelta: Int = 0)

object Settings {
    val STALL_OPTIONS = listOf(8, 12, 20, 30)      // 卡顿多少秒自动换线路
    val INFO_OPTIONS = listOf(3, 5, 10, 0)         // 信息条显示秒数；0 = 一直显示
    const val DEFAULT_STALL = 12
    const val DEFAULT_INFO = 3

    /** 在 options 中从 current 向前/后 delta 步循环；current 不在列表中则视为落在第一项之前。 */
    fun <T> cycle(options: List<T>, current: T, delta: Int): T {
        require(options.isNotEmpty())
        val i = options.indexOf(current)
        val base = if (i < 0) 0 else i
        val n = options.size
        return options[((base + delta) % n + n) % n]
    }

    /** 枚举循环：values() 全部取值。 */
    inline fun <reified T : Enum<T>> cycle(current: T, delta: Int): T = cycle(enumValues<T>().toList(), current, delta)

    /** 从持久化的名字还原枚举；空或未知名字回退默认。 */
    inline fun <reified T : Enum<T>> parse(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    fun stallLabel(s: Int) = "$s 秒"
    fun infoLabel(s: Int) = if (s == 0) "一直显示" else "$s 秒"

    /** 播放画面中方向键 → 动作。 */
    fun mapDpad(dir: Dir, v: VerticalMode, h: HorizontalMode): KeyAction = when (dir) {
        Dir.UP -> KeyAction(channelDelta = if (v == VerticalMode.UP_PREV) -1 else +1)
        Dir.DOWN -> KeyAction(channelDelta = if (v == VerticalMode.UP_PREV) +1 else -1)
        Dir.LEFT -> if (h == HorizontalMode.LINE) KeyAction(lineDelta = -1) else KeyAction(channelDelta = -1)
        Dir.RIGHT -> if (h == HorizontalMode.LINE) KeyAction(lineDelta = +1) else KeyAction(channelDelta = +1)
    }
}

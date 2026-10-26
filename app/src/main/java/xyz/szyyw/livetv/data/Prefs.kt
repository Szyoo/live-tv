package xyz.szyyw.livetv.data

import android.content.Context
import xyz.szyyw.livetv.logic.ClockMode
import xyz.szyyw.livetv.logic.ExitMode
import xyz.szyyw.livetv.logic.HorizontalMode
import xyz.szyyw.livetv.logic.ResizeOption
import xyz.szyyw.livetv.logic.Settings
import xyz.szyyw.livetv.logic.StartMode
import xyz.szyyw.livetv.logic.VerticalMode

/** 持久化：源地址、上次频道（按名字）、每个频道上次使用的线路，以及全部设置项。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("live_tv", Context.MODE_PRIVATE)
    private val filesDir = context.filesDir

    /** 用户保存的自定义源地址；空字符串表示使用电脑推送的清单。 */
    var sourceUrl: String
        get() = sp.getString(KEY_SOURCE, null)?.trim().orEmpty()
        set(v) { sp.edit().putString(KEY_SOURCE, v).apply() }

    var lastChannel: String?
        get() = sp.getString(KEY_LAST_CHANNEL, null)
        set(v) { sp.edit().putString(KEY_LAST_CHANNEL, v).apply() }

    /** 该频道上次使用的线路 URL（旧版本存的是下标，键名不同，直接忽略）。 */
    fun lineFor(channelName: String): String? = sp.getString(KEY_LINE_URL + channelName, null)

    fun setLine(channelName: String, url: String) {
        sp.edit().putString(KEY_LINE_URL + channelName, url).apply()
    }

    // ---------- 设置项（默认值 = 加设置页之前的行为） ----------

    var startMode: StartMode
        get() = Settings.parse(sp.getString("start_mode", null), StartMode.LAST)
        set(v) { sp.edit().putString("start_mode", v.name).apply() }

    /** 「指定频道」模式下启动时播放的频道名。 */
    var startChannel: String?
        get() = sp.getString("start_channel", null)
        set(v) { sp.edit().putString("start_channel", v).apply() }

    var resize: ResizeOption
        get() = Settings.parse(sp.getString("resize", null), ResizeOption.FIT)
        set(v) { sp.edit().putString("resize", v.name).apply() }

    var stallSeconds: Int
        get() = sp.getInt("stall_seconds", Settings.DEFAULT_STALL)
        set(v) { sp.edit().putInt("stall_seconds", v).apply() }

    /** 换台后信息条显示秒数；0 = 一直显示。 */
    var infoSeconds: Int
        get() = sp.getInt("info_seconds", Settings.DEFAULT_INFO)
        set(v) { sp.edit().putInt("info_seconds", v).apply() }

    var clockMode: ClockMode
        get() = Settings.parse(sp.getString("clock_mode", null), ClockMode.WITH_INFO)
        set(v) { sp.edit().putString("clock_mode", v.name).apply() }

    var verticalMode: VerticalMode
        get() = Settings.parse(sp.getString("vertical_mode", null), VerticalMode.UP_PREV)
        set(v) { sp.edit().putString("vertical_mode", v.name).apply() }

    var horizontalMode: HorizontalMode
        get() = Settings.parse(sp.getString("horizontal_mode", null), HorizontalMode.LINE)
        set(v) { sp.edit().putString("horizontal_mode", v.name).apply() }

    var exitMode: ExitMode
        get() = Settings.parse(sp.getString("exit_mode", null), ExitMode.DOUBLE_BACK)
        set(v) { sp.edit().putString("exit_mode", v.name).apply() }

    /** 清除记忆：上次频道、每台线路记忆、自定义源缓存文件。设置项与源地址本身保留。 */
    fun clearMemory() {
        val e = sp.edit()
        e.remove(KEY_LAST_CHANNEL)
        sp.all.keys.filter { it.startsWith(KEY_LINE) || it.startsWith(KEY_LINE_URL) }.forEach { e.remove(it) }
        e.apply()
        filesDir.listFiles { f -> f.name.startsWith("playlist-cache-") }?.forEach { it.delete() }
    }

    private companion object {
        const val KEY_SOURCE = "source_url"
        const val KEY_LAST_CHANNEL = "last_channel"
        const val KEY_LINE = "line_"
        const val KEY_LINE_URL = "lineurl_"
    }
}

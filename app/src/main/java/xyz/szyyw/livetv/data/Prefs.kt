package xyz.szyyw.livetv.data

import android.content.Context

/** 持久化：源地址、上次频道（按名字）、每个频道上次使用的线路。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("live_tv", Context.MODE_PRIVATE)

    /** 用户保存的自定义源地址；空字符串表示使用内置清单。 */
    var sourceUrl: String
        get() = sp.getString(KEY_SOURCE, null)?.trim().orEmpty()
        set(v) { sp.edit().putString(KEY_SOURCE, v).apply() }

    var lastChannel: String?
        get() = sp.getString(KEY_LAST_CHANNEL, null)
        set(v) { sp.edit().putString(KEY_LAST_CHANNEL, v).apply() }

    fun lineFor(channelName: String): Int = sp.getInt(KEY_LINE + channelName, 0)

    fun setLine(channelName: String, index: Int) {
        sp.edit().putInt(KEY_LINE + channelName, index).apply()
    }

    private companion object {
        const val KEY_SOURCE = "source_url"
        const val KEY_LAST_CHANNEL = "last_channel"
        const val KEY_LINE = "line_"
    }
}

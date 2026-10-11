package xyz.szyyw.livetv.logic

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** 频道清单来源的纯逻辑（无 Android 依赖，便于单测）。清单不内置，只来自自定义地址或电脑推送的文件。 */

/** 推送文件的状态快照：修改时间 + 大小。 */
data class FileStamp(val modified: Long, val length: Long)

enum class SourceKind { CUSTOM, PUSHED, NONE }

object PlaylistSource {
    /** 推送文件在 App 外部私有目录下的文件名（getExternalFilesDir(null)/channels.m3u）。 */
    const val PUSHED_NAME = "channels.m3u"

    /** 来源优先级：填了自定义地址 → 自定义；否则有可用的推送文件 → 推送；都没有 → 无。 */
    fun choose(customUrl: String, pushed: FileStamp?): SourceKind = when {
        customUrl.isNotBlank() -> SourceKind.CUSTOM
        pushed != null && pushed.length > 0 -> SourceKind.PUSHED
        else -> SourceKind.NONE
    }

    /** 推送文件：不存在或空文件视为没有。 */
    fun usable(stamp: FileStamp?): FileStamp? = stamp?.takeIf { it.length > 0 }

    /** 当前清单来自推送文件时，文件的修改时间或大小变了就需要重新加载。 */
    fun needsReload(usesPushed: Boolean, loaded: FileStamp?, now: FileStamp?): Boolean =
        usesPushed && usable(now) != null && loaded != now

    /** 设置页「当前来源」文案。 */
    fun label(kind: SourceKind, channelCount: Int?, pushed: FileStamp?, tz: TimeZone = TimeZone.getDefault()): String = when (kind) {
        SourceKind.CUSTOM -> "自定义地址"
        SourceKind.NONE -> "无"
        SourceKind.PUSHED -> {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).also { it.timeZone = tz }
            val n = channelCount?.let { "$it 个频道，" } ?: ""
            val t = pushed?.let { "更新于 ${fmt.format(it.modified)}" } ?: "更新时间未知"
            "推送的清单（$n$t）"
        }
    }

    const val EMPTY_TITLE = "尚未导入频道清单"
    const val EMPTY_HINT = "在电脑上运行推送脚本，或在设置里填写源地址"
}

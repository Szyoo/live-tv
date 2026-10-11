package xyz.szyyw.livetv.data

import android.content.Context
import java.io.File

/** 缓存统计与清理：cacheDir（安装包、okhttp/播放器缓存）+ 自定义源清单缓存文件。绝不碰 SharedPreferences。 */
object CacheTools {
    private fun playlistCaches(c: Context): List<File> =
        c.filesDir.listFiles { f -> f.name.startsWith("playlist-cache-") }?.toList().orEmpty()

    fun size(c: Context): Long = dirSize(c.cacheDir) + playlistCaches(c).sumOf { it.length() }

    fun clear(c: Context) {
        c.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        playlistCaches(c).forEach { it.delete() }
    }

    private fun dirSize(f: File): Long =
        if (f.isFile) f.length() else f.listFiles()?.sumOf { dirSize(it) } ?: 0L

    fun format(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }
}

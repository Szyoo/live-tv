package xyz.szyyw.livetv.data

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.szyyw.livetv.logic.FileStamp
import xyz.szyyw.livetv.logic.PlaylistSource
import xyz.szyyw.livetv.playlist.Playlist
import xyz.szyyw.livetv.playlist.PlaylistParser
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 加载频道清单（阻塞调用，须在后台线程）。清单不内置：
 * url 非空 → 下载并按 url 缓存，失败先用该 url 的缓存，再退回推送文件；
 * url 为空 → 推送文件 getExternalFilesDir(null)/channels.m3u；都没有 → NoPlaylistException。
 */
class PlaylistLoader(context: Context, private val client: OkHttpClient) {
    private val filesDir = context.filesDir
    private val pushedFile: File? = pushedFile(context)

    enum class Source { PUSHED, CUSTOM, CUSTOM_CACHE, FALLBACK_PUSHED }

    /** pushedStamp：加载时推送文件的状态（仅 PUSHED / FALLBACK_PUSHED 有值）。 */
    data class Loaded(val playlist: Playlist, val source: Source, val pushedStamp: FileStamp? = null) {
        val usesPushed get() = source == Source.PUSHED || source == Source.FALLBACK_PUSHED
    }

    class NoPlaylistException : Exception(PlaylistSource.EMPTY_TITLE)

    /** 推送文件当前状态；不存在返回 null。 */
    fun pushedStamp(): FileStamp? = stampOf(pushedFile)

    fun load(url: String): Result<Loaded> = runCatching {
        if (url.isBlank()) return@runCatching pushed() ?: throw NoPlaylistException()
        val cacheFile = File(filesDir, cacheFileName(url))
        val fromNet = runCatching {
            val text = download(url)
            parseNonEmpty(text).also { runCatching { cacheFile.writeText(text) } }
        }
        fromNet.getOrNull()?.let { return@runCatching Loaded(it, Source.CUSTOM) }
        if (cacheFile.exists()) {
            runCatching { parseNonEmpty(cacheFile.readText()) }.getOrNull()
                ?.let { return@runCatching Loaded(it, Source.CUSTOM_CACHE) }
        }
        pushed()?.let { return@runCatching it.copy(source = Source.FALLBACK_PUSHED) }
        throw fromNet.exceptionOrNull() ?: NoPlaylistException()
    }

    /** 推送文件不存在/为空 → null；存在但解析失败 → 抛异常（让界面显示原因）。 */
    private fun pushed(): Loaded? {
        val f = pushedFile ?: return null
        val stamp = PlaylistSource.usable(stampOf(f)) ?: return null
        return Loaded(parseNonEmpty(f.readText()), Source.PUSHED, stamp)
    }

    private fun download(url: String): String {
        val c = client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        c.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            return r.body.string()
        }
    }

    companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        fun pushedFile(context: Context): File? =
            context.getExternalFilesDir(null)?.let { File(it, PlaylistSource.PUSHED_NAME) }

        fun stampOf(f: File?): FileStamp? =
            if (f != null && f.isFile) FileStamp(f.lastModified(), f.length()) else null

        /** 缓存文件名随 url 变化：换了地址就不会用到旧地址的缓存。 */
        fun cacheFileName(url: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(url.trim().toByteArray())
            return "playlist-cache-" + d.take(8).joinToString("") { "%02x".format(it) } + ".txt"
        }

        internal fun parseNonEmpty(text: String): Playlist =
            PlaylistParser.parse(text).also { if (it.isEmpty) error("清单为空或无法识别") }
    }
}

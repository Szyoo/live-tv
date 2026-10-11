package xyz.szyyw.livetv.data

import android.content.Context
import android.content.res.AssetManager
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.szyyw.livetv.playlist.Playlist
import xyz.szyyw.livetv.playlist.PlaylistParser
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 加载频道清单（阻塞调用，须在后台线程）。
 * url 为空 → 内置清单；否则下载并按 url 缓存，下载失败先用该 url 的缓存，再回退内置清单。
 */
class PlaylistLoader(context: Context, private val client: OkHttpClient) {
    private val filesDir = context.filesDir
    private val assets: AssetManager = context.assets

    enum class Source { BUILTIN, CUSTOM, CUSTOM_CACHE, FALLBACK_BUILTIN }

    data class Loaded(val playlist: Playlist, val source: Source)

    fun load(url: String): Result<Loaded> = runCatching {
        if (url.isBlank()) return@runCatching Loaded(builtin(), Source.BUILTIN)
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
        Loaded(builtin(), Source.FALLBACK_BUILTIN)
    }

    /** 内置清单（阻塞，后台线程调用）。 */
    fun loadBuiltin(): Playlist = builtin()

    private fun builtin(): Playlist =
        parseNonEmpty(assets.open(BUILTIN_ASSET).bufferedReader().use { it.readText() })

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
        const val BUILTIN_ASSET = "channels.m3u"

        /** 缓存文件名随 url 变化：换了地址就不会用到旧地址的缓存。 */
        fun cacheFileName(url: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(url.trim().toByteArray())
            return "playlist-cache-" + d.take(8).joinToString("") { "%02x".format(it) } + ".txt"
        }

        internal fun parseNonEmpty(text: String): Playlist =
            PlaylistParser.parse(text).also { if (it.isEmpty) error("清单为空或无法识别") }
    }
}

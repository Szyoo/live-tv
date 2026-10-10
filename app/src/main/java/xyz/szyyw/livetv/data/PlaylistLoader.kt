package xyz.szyyw.livetv.data

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.szyyw.livetv.playlist.Playlist
import xyz.szyyw.livetv.playlist.PlaylistParser
import java.io.File
import java.util.concurrent.TimeUnit

/** 下载并解析频道清单（阻塞调用，须在后台线程）。下载失败时回退到上次成功的本地缓存。 */
class PlaylistLoader(context: Context, private val client: OkHttpClient) {
    private val cacheFile = File(context.filesDir, "playlist-cache.txt")

    fun load(url: String): Result<Playlist> = runCatching {
        val text = try {
            download(url).also { runCatching { cacheFile.writeText(it) } }
        } catch (e: Exception) {
            if (cacheFile.exists()) cacheFile.readText() else throw e
        }
        PlaylistParser.parse(text).also { if (it.isEmpty) error("清单为空或无法识别") }
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
    }
}

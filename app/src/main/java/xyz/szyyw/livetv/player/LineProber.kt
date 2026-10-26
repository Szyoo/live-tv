package xyz.szyyw.livetv.player

import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import xyz.szyyw.livetv.data.PlaylistLoader
import xyz.szyyw.livetv.playlist.Stream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

enum class ProbeFail { TIMEOUT, HTTP, TOO_SMALL, HTML, NOT_MEDIA, BAD_PLAYLIST, NETWORK }

/** 一次测速的结果。millis = 从发起请求到拿到足够首包数据的耗时；失败时为已用时间。 */
data class Probe(val ok: Boolean, val millis: Long, val fail: ProbeFail? = null, val detail: String = "")

/** 测速参数；默认值即线上值，测试里可以改小。 */
data class ProbeConfig(
    val connectMs: Long = 4_000,
    val readMs: Long = 4_000,
    val callMs: Long = 10_000,
    /** 直接流（非 HLS）读够首包的时限。 */
    val directMs: Long = 6_000,
    /** 整条线路（含 HLS 的清单与分片）的总时限。 */
    val totalMs: Long = 10_000,
    val playlistMax: Int = 256 * 1024,
    val needBytes: Int = 32 * 1024,
)

/**
 * 线路测速：对一条线路取「首包数据」，判断能不能连上、多快、是不是真的流（而不是网页）。
 * HLS：读清单（master 取第一个变体）→ 第一个分片，读够 [ProbeConfig.needBytes] 算成功；
 * 其它：直接 GET 读够即成功。阻塞调用，须在后台线程。日志里不记录完整地址。
 */
class LineProber(client: OkHttpClient, private val cfg: ProbeConfig = ProbeConfig()) {
    private val http: OkHttpClient = client.newBuilder()
        .connectTimeout(cfg.connectMs, TimeUnit.MILLISECONDS)
        .readTimeout(cfg.readMs, TimeUnit.MILLISECONDS)
        .callTimeout(cfg.callMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectionPool(ConnectionPool(2, 5, TimeUnit.SECONDS))
        .build()

    private class Failed(val kind: ProbeFail, val detail: String = "") : Exception(kind.name, null, false, false)

    fun probe(stream: Stream): Probe {
        val t0 = System.nanoTime()
        return try {
            run(stream, t0)
            Probe(true, elapsed(t0))
        } catch (f: Failed) {
            Probe(false, elapsed(t0), f.kind, f.detail)
        } catch (e: InterruptedIOException) {
            Probe(false, elapsed(t0), ProbeFail.TIMEOUT)
        } catch (e: IOException) {
            Probe(false, elapsed(t0), ProbeFail.NETWORK, errText(e))
        } catch (e: Exception) {
            Probe(false, elapsed(t0), ProbeFail.NETWORK, e.javaClass.simpleName)
        }
    }

    private fun errText(e: Exception) = e.javaClass.simpleName + (e.message?.let { ": " + it.take(60).replace(Regex("https?://\\S+"), "URL") } ?: "")

    private fun elapsed(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    private fun open(url: HttpUrl, headers: Map<String, String>): Response {
        val b = Request.Builder().url(url).header("User-Agent", PlaylistLoader.UA)
        for ((k, v) in headers) b.header(k, v)
        return http.newCall(b.build()).execute()
    }

    private fun run(stream: Stream, t0: Long) {
        val url = stream.url.toHttpUrlOrNull() ?: throw Failed(ProbeFail.NETWORK, "bad url")
        val totalDeadline = t0 + cfg.totalMs * 1_000_000
        open(url, stream.headers).use { resp ->
            checkCode(resp)
            val src = resp.body.source()
            val head = peekHead(src)
            when {
                isPlaylistHead(head) -> {
                    val text = readText(src)
                    hls(resp.request.url, text, stream.headers, totalDeadline)
                }
                head.firstOrNull() == '<'.code.toByte() -> throw Failed(ProbeFail.HTML)
                else -> {
                    val direct = minOf(t0 + cfg.directMs * 1_000_000, totalDeadline)
                    readMedia(src, direct, validate = true)
                }
            }
        }
    }

    private fun hls(base: HttpUrl, text: String, headers: Map<String, String>, deadline: Long) {
        var pUrl = base
        var pText = text
        if (pText.contains("#EXT-X-STREAM-INF")) {
            val variant = uriAfter(pText, "#EXT-X-STREAM-INF") ?: throw Failed(ProbeFail.BAD_PLAYLIST, "no variant")
            val vUrl = pUrl.resolve(variant) ?: throw Failed(ProbeFail.BAD_PLAYLIST, "bad variant")
            open(vUrl, headers).use { r ->
                checkCode(r)
                val src = r.body.source()
                if (!isPlaylistHead(peekHead(src))) throw Failed(ProbeFail.BAD_PLAYLIST, "variant not playlist")
                pText = readText(src)
                pUrl = r.request.url
            }
        }
        val seg = firstSegment(pText) ?: throw Failed(ProbeFail.BAD_PLAYLIST, "no segment")
        val segUrl = pUrl.resolve(seg) ?: throw Failed(ProbeFail.BAD_PLAYLIST, "bad segment")
        val encrypted = Regex("#EXT-X-KEY:[^\\n]*METHOD=(?!NONE)").containsMatchIn(pText)
        open(segUrl, headers).use { r ->
            checkCode(r)
            readMedia(r.body.source(), deadline, validate = !encrypted)
        }
    }

    private fun checkCode(r: Response) {
        if (!r.isSuccessful) throw Failed(ProbeFail.HTTP, r.code.toString())
    }

    /** 读到至少 16 字节（或流结束）后返回开头的字节，不消耗数据。 */
    private fun peekHead(src: BufferedSource): ByteArray {
        src.request(16)
        val n = minOf(src.buffer.size, 64L)
        if (n == 0L) throw Failed(ProbeFail.TOO_SMALL, "empty")
        return src.buffer.snapshot(n.toInt()).toByteArray()
    }

    private fun readText(src: BufferedSource): String {
        src.request(cfg.playlistMax.toLong())
        val n = minOf(src.buffer.size, cfg.playlistMax.toLong())
        return src.buffer.readUtf8(n)
    }

    private fun readMedia(src: BufferedSource, deadline: Long, validate: Boolean) {
        val buf = Buffer()
        while (buf.size < cfg.needBytes) {
            if (System.nanoTime() > deadline) throw Failed(ProbeFail.TIMEOUT, "slow")
            val n = src.read(buf, 16 * 1024L)
            if (n < 0) throw Failed(ProbeFail.TOO_SMALL, "${buf.size}B")
        }
        val bytes = buf.readByteArray(cfg.needBytes.toLong())
        if (looksLikeHtml(bytes)) throw Failed(ProbeFail.HTML)
        if (validate && !looksLikeMedia(bytes)) throw Failed(ProbeFail.NOT_MEDIA)
    }

    companion object {
        internal fun isPlaylistHead(head: ByteArray): Boolean {
            var i = 0
            if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) i = 3
            while (i < head.size && (head[i] == ' '.code.toByte() || head[i] == '\n'.code.toByte() || head[i] == '\r'.code.toByte())) i++
            val tag = "#EXTM3U".toByteArray()
            if (head.size - i < tag.size) return false
            return tag.indices.all { head[i + it] == tag[it] }
        }

        internal fun uriAfter(text: String, tag: String): String? {
            val lines = text.lines()
            val i = lines.indexOfFirst { it.startsWith(tag) }
            if (i < 0) return null
            return lines.drop(i + 1).map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        }

        internal fun firstSegment(text: String): String? =
            text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }

        internal fun looksLikeHtml(b: ByteArray): Boolean {
            val s = String(b, 0, minOf(b.size, 1024), Charsets.ISO_8859_1).lowercase()
            return s.contains("<html") || s.contains("<!doctype") || s.contains("<head") || s.contains("<body")
        }

        /** TS（0x47 同步字节每隔 188）、fMP4、AAC/MP3(ID3)、FLV、PS、Ogg 的简单判断。 */
        internal fun looksLikeMedia(b: ByteArray): Boolean {
            val n = b.size
            for (p in 0 until minOf(188, n)) {
                if (b[p] != 0x47.toByte()) continue
                val ok1 = p + 188 >= n || b[p + 188] == 0x47.toByte()
                val ok2 = p + 376 >= n || b[p + 376] == 0x47.toByte()
                if (ok1 && ok2 && p + 188 < n) return true
            }
            if (n >= 8) {
                val box = String(b, 4, 4, Charsets.ISO_8859_1)
                if (box in setOf("ftyp", "styp", "moof", "moov", "sidx", "mdat", "free")) return true
            }
            if (n >= 3 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) return true
            if (n >= 3 && b[0] == 'F'.code.toByte() && b[1] == 'L'.code.toByte() && b[2] == 'V'.code.toByte()) return true
            if (n >= 4 && b[0] == 'O'.code.toByte() && b[1] == 'g'.code.toByte() && b[2] == 'g'.code.toByte() && b[3] == 'S'.code.toByte()) return true
            if (n >= 4 && b[0] == 0.toByte() && b[1] == 0.toByte() && b[2] == 1.toByte() && b[3] == 0xBA.toByte()) return true
            if (n >= 2 && b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0) return true // ADTS / MPEG 音频帧同步
            return false
        }
    }
}

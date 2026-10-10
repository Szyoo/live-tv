package xyz.szyyw.livetv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient
import xyz.szyyw.livetv.data.PlaylistLoader
import xyz.szyyw.livetv.playlist.Channel

/**
 * 播放核心：ExoPlayer + OkHttp 数据源，按线路带请求头；
 * 出错或缓冲卡住超过 [STALL_TIMEOUT_MS] 自动切到同频道下一条线路，全部失败后停止并回调错误。
 */
class PlayerController(
    context: Context,
    private val client: OkHttpClient,
    private val listener: Listener,
) {
    interface Listener {
        /** 当前线路发生变化（含自动切换）。 */
        fun onStreamChanged(channel: Channel, streamIndex: Int, automatic: Boolean)
        /** 画面开始播放。 */
        fun onPlaying(channel: Channel, streamIndex: Int)
        /** 所有线路均失败，已停止。 */
        fun onAllFailed(channel: Channel, message: String)
    }

    val player: ExoPlayer = ExoPlayer.Builder(context).build()
    private val handler = Handler(Looper.getMainLooper())

    private var channel: Channel? = null
    private var streamIndex = 0
    private var attempts = 0 // 当前一轮已尝试的线路数
    private var lastError: String = ""

    private val stallRunnable = Runnable {
        val ch = channel ?: return@Runnable
        Log.w(TAG, "buffering stalled > ${STALL_TIMEOUT_MS}ms on ${ch.name} line ${streamIndex + 1}")
        lastError = "缓冲超时"
        failover()
    }

    private val retryRunnable = Runnable { startCurrent() }

    init {
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val ch = channel ?: return
                when (state) {
                    Player.STATE_BUFFERING -> armStallTimer()
                    Player.STATE_READY -> {
                        handler.removeCallbacks(stallRunnable)
                        attempts = 0
                        Log.i(TAG, "ready: ${ch.name} line ${streamIndex + 1}/${ch.streams.size}")
                        listener.onPlaying(ch, streamIndex)
                    }
                    Player.STATE_ENDED -> { lastError = "流已结束"; failover() }
                    else -> handler.removeCallbacks(stallRunnable)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (channel == null) return
                Log.w(TAG, "player error: ${error.errorCodeName}", error)
                lastError = error.errorCodeName
                failover()
            }
        })
    }

    /** 用户主动选择频道与线路，重置失败计数。 */
    fun play(ch: Channel, index: Int, automatic: Boolean = false) {
        channel = ch
        streamIndex = ((index % ch.streams.size) + ch.streams.size) % ch.streams.size
        attempts = 0
        lastError = ""
        listener.onStreamChanged(ch, streamIndex, automatic)
        startCurrent()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        channel = null
        player.release()
    }

    fun pause() {
        handler.removeCallbacksAndMessages(null)
        player.stop()
    }

    private fun failover() {
        val ch = channel ?: return
        handler.removeCallbacks(stallRunnable)
        attempts++
        if (attempts >= ch.streams.size) {
            Log.e(TAG, "all ${ch.streams.size} streams failed for ${ch.name}: $lastError")
            player.stop()
            listener.onAllFailed(ch, lastError)
            channel = null // 不再自动重试，等待用户操作
            return
        }
        streamIndex = (streamIndex + 1) % ch.streams.size
        Log.i(TAG, "failover -> ${ch.name} line ${streamIndex + 1}/${ch.streams.size} (attempt ${attempts + 1})")
        listener.onStreamChanged(ch, streamIndex, true)
        // 不做无延迟的快速重试：留 0.5s 间隔
        handler.removeCallbacks(retryRunnable)
        player.stop()
        handler.postDelayed(retryRunnable, RETRY_DELAY_MS)
    }

    private fun startCurrent() {
        val ch = channel ?: return
        val stream = ch.streams[streamIndex]
        val headers = LinkedHashMap<String, String>().apply {
            put("User-Agent", PlaylistLoader.UA)
            putAll(stream.headers) // 线路自带的 UA/Referer 覆盖默认
        }
        val ua = headers.remove("User-Agent")!!
        val dsFactory = OkHttpDataSource.Factory(client)
            .setUserAgent(ua)
            .setDefaultRequestProperties(headers)
        val builder = MediaItem.Builder().setUri(stream.url)
        if (stream.url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        Log.i(TAG, "start: ${ch.name} line ${streamIndex + 1}/${ch.streams.size} ${redact(stream.url)}")
        player.setMediaSource(DefaultMediaSourceFactory(dsFactory).createMediaSource(builder.build()))
        player.prepare()
        armStallTimer()
    }

    private fun armStallTimer() {
        handler.removeCallbacks(stallRunnable)
        handler.postDelayed(stallRunnable, STALL_TIMEOUT_MS)
    }

    private fun redact(url: String) = url.substringBefore('?')

    companion object {
        const val TAG = "LiveTv"
        const val STALL_TIMEOUT_MS = 12_000L
        const val RETRY_DELAY_MS = 500L
    }
}

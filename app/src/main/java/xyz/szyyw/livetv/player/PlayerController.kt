package xyz.szyyw.livetv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import okhttp3.OkHttpClient
import xyz.szyyw.livetv.data.PlaylistLoader
import xyz.szyyw.livetv.logic.Failure
import xyz.szyyw.livetv.logic.FailureKind
import xyz.szyyw.livetv.logic.LineRank
import xyz.szyyw.livetv.playlist.Channel

/**
 * 播放核心：ExoPlayer + OkHttp 数据源，按线路带请求头；
 * 出错或缓冲卡住超过 [stallTimeoutMs] 自动切到同频道下一条线路，全部失败后停止并回调错误。
 * 无后缀地址若按渐进式加载时报"格式无法识别"，先按 HLS 对同一条线路重试一次。
 */
class PlayerController(
    context: Context,
    private val client: OkHttpClient,
    private val listener: Listener,
) {
    interface Listener {
        fun onStreamChanged(channel: Channel, streamIndex: Int, automatic: Boolean)
        fun onLoading(loading: Boolean)
        fun onPlaying(channel: Channel, streamIndex: Int)
        fun onAllFailed(channel: Channel, failure: Failure)
    }

    val player: ExoPlayer = ExoPlayer.Builder(context).build()

    /** 卡顿多久自动换线路（设置里可调，默认 12 秒）。 */
    @Volatile var stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS
    private val handler = Handler(Looper.getMainLooper())

    /** 某条线路（按 URL）是否已被测速判为失败：自动故障转移会先跳过它们。 */
    @Volatile var isLineFailed: (String) -> Boolean = { false }

    private var channel: Channel? = null
    private var streamIndex = 0
    private var attempts = 0
    private var lastFailure = Failure(FailureKind.OTHER)
    private var forceHls = false
    private var currentIsHls = false
    private var hlsRetried = false

    // 清单加载失败只重试 1 次（快速切线）；分片保留少量重试
    private val loadPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getMinimumLoadableRetryCount(dataType: Int): Int =
            if (dataType == C.DATA_TYPE_MANIFEST) 1 else 2
    }

    private val stallRunnable = Runnable {
        val ch = channel ?: return@Runnable
        Log.w(TAG, "buffering stalled > ${stallTimeoutMs}ms on ${ch.name} line ${streamIndex + 1}")
        failover(Failure(FailureKind.STALL))
    }

    private val retryRunnable = Runnable { startCurrent() }

    init {
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val ch = channel ?: return
                when (state) {
                    Player.STATE_BUFFERING -> { listener.onLoading(true); armStallTimer() }
                    Player.STATE_READY -> {
                        handler.removeCallbacks(stallRunnable)
                        attempts = 0
                        Log.i(TAG, "ready: ${ch.name} line ${streamIndex + 1}/${ch.streams.size}")
                        listener.onLoading(false)
                        listener.onPlaying(ch, streamIndex)
                    }
                    Player.STATE_ENDED -> failover(Failure(FailureKind.ENDED))
                    else -> handler.removeCallbacks(stallRunnable)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (channel == null) return
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // 直播窗口落后：重新定位到直播边缘即可，不算线路失败
                    Log.i(TAG, "behind live window, re-prepare")
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                var status: Int? = null
                var c: Throwable? = error
                while (c != null) {
                    if (c is HttpDataSource.InvalidResponseCodeException) { status = c.responseCode; break }
                    c = c.cause
                }
                val f = Failure.classify(error.errorCode, status)
                Log.w(TAG, "player error: ${error.errorCodeName}(${error.errorCode}) -> ${f.kind} ${f.httpStatus ?: ""}")
                failover(f)
            }
        })
    }

    /** 用户主动选择频道与线路，重置失败计数。 */
    fun play(ch: Channel, index: Int, automatic: Boolean = false) {
        handler.removeCallbacks(retryRunnable)
        channel = ch
        streamIndex = ((index % ch.streams.size) + ch.streams.size) % ch.streams.size
        attempts = 0
        hlsRetried = false
        forceHls = false
        listener.onStreamChanged(ch, streamIndex, automatic)
        startCurrent()
    }

    /** 测速结果改变了线路顺序：换成新顺序的频道视图，正在播放的线路继续播放（只更新序号），不重新起播。 */
    fun updateOrder(view: Channel) {
        val cur = channel ?: return
        if (view.name != cur.name) return
        val url = cur.streams.getOrNull(streamIndex)?.url ?: return
        val i = view.streams.indexOfFirst { it.url == url }
        if (i < 0) return
        channel = view
        streamIndex = i
    }

    /** 正在播放（或正在尝试）的线路在当前顺序里的下标；没有频道时为 -1。 */
    val currentIndex: Int get() = if (channel == null) -1 else streamIndex

    fun release() {
        handler.removeCallbacksAndMessages(null)
        channel = null
        player.release()
    }

    fun pause() {
        handler.removeCallbacksAndMessages(null)
        channel = null
        player.stop()
    }

    private fun failover(f: Failure) {
        val ch = channel ?: return
        handler.removeCallbacks(stallRunnable)
        lastFailure = f
        // 无后缀 HLS：渐进式识别失败时，同一条线路改按 HLS 再试一次（不计入失败次数）
        if (f.kind == FailureKind.FORMAT && !currentIsHls && !hlsRetried) {
            hlsRetried = true
            forceHls = true
            Log.i(TAG, "format not recognized, retry same line as HLS: ${ch.name} line ${streamIndex + 1}")
            player.stop()
            startCurrent()
            return
        }
        attempts++
        if (attempts >= LineRank.failoverLimit(ch.streams) { isLineFailed(it) }) {
            Log.e(TAG, "all ${ch.streams.size} streams failed for ${ch.name}: ${f.kind}")
            player.stop()
            listener.onLoading(false)
            channel = null
            listener.onAllFailed(ch, f)
            return
        }
        streamIndex = LineRank.nextCandidate(ch.streams, streamIndex) { isLineFailed(it) }
        hlsRetried = false
        forceHls = false
        Log.i(TAG, "failover -> ${ch.name} line ${streamIndex + 1}/${ch.streams.size} (attempt ${attempts + 1})")
        listener.onStreamChanged(ch, streamIndex, true)
        handler.removeCallbacks(retryRunnable)
        player.stop()
        handler.postDelayed(retryRunnable, RETRY_DELAY_MS)
    }

    private fun startCurrent() {
        val ch = channel ?: return
        val stream = ch.streams[streamIndex]
        val headers = LinkedHashMap<String, String>().apply {
            put("User-Agent", PlaylistLoader.UA)
            putAll(stream.headers)
        }
        val ua = headers.remove("User-Agent")!!
        val dsFactory = OkHttpDataSource.Factory(client)
            .setUserAgent(ua)
            .setDefaultRequestProperties(headers)
        val builder = MediaItem.Builder().setUri(stream.url)
        currentIsHls = forceHls ||
            stream.url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)
        if (currentIsHls) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        Log.i(TAG, "start: ${ch.name} line ${streamIndex + 1}/${ch.streams.size} hls=$currentIsHls ${stream.url.substringBefore('?')}")
        listener.onLoading(true)
        val factory = DefaultMediaSourceFactory(dsFactory).setLoadErrorHandlingPolicy(loadPolicy)
        player.setMediaSource(factory.createMediaSource(builder.build()))
        player.prepare()
        armStallTimer()
    }

    private fun armStallTimer() {
        handler.removeCallbacks(stallRunnable)
        handler.postDelayed(stallRunnable, stallTimeoutMs)
    }

    companion object {
        const val TAG = "LiveTv"
        const val DEFAULT_STALL_TIMEOUT_MS = 12_000L
        const val RETRY_DELAY_MS = 300L
    }
}

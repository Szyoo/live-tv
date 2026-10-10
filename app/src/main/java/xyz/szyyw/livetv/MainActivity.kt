package xyz.szyyw.livetv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import xyz.szyyw.livetv.data.PlaylistLoader
import xyz.szyyw.livetv.data.Prefs
import xyz.szyyw.livetv.player.PlayerController
import xyz.szyyw.livetv.playlist.Channel
import xyz.szyyw.livetv.playlist.Playlist
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), PlayerController.Listener {
    private lateinit var playerView: PlayerView
    private lateinit var infoBar: TextView
    private lateinit var statusText: TextView
    private lateinit var prefs: Prefs

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private var controller: PlayerController? = null
    private var playlist: Playlist? = null
    private var current: Channel? = null
    private var loading = false
    private val hideInfo = Runnable { infoBar.visibility = android.view.View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.player_view)
        infoBar = findViewById(R.id.info_bar)
        statusText = findViewById(R.id.status_text)
        prefs = Prefs(this)
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        applyDebugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (applyDebugIntent(intent)) {
            playlist = null
            current = null
            controller?.pause()
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) startUp()
        }
    }

    /** 仅 debug 构建：`--es source_url <url>` 覆盖已保存的源地址（用于自测）。 */
    private fun applyDebugIntent(i: Intent?): Boolean {
        if (!BuildConfig.DEBUG) return false
        val url = i?.getStringExtra("source_url") ?: return false
        Log.i(TAG, "debug: source url overridden")
        prefs.sourceUrl = url
        return true
    }

    override fun onStart() {
        super.onStart()
        controller = PlayerController(this, client, this).also { playerView.player = it.player }
        val pl = playlist
        if (pl != null) resume(pl) else startUp()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        playerView.player = null
        controller?.release()
        controller = null
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    // ---------- 启动流程 ----------

    private fun startUp() {
        if (loading) return
        val url = prefs.sourceUrl
        if (url.isBlank()) {
            showStatus("未设置频道源地址")
            return
        }
        loading = true
        showStatus("正在加载频道清单…")
        val loader = PlaylistLoader(applicationContext, client)
        io.execute {
            val r = loader.load(url)
            handler.post {
                loading = false
                r.onSuccess { pl ->
                    playlist = pl
                    Log.i(TAG, "playlist loaded: ${pl.channels.size} channels")
                    if (controller != null) resume(pl)
                }.onFailure { e ->
                    Log.e(TAG, "playlist load failed", e)
                    showStatus("频道清单加载失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun resume(pl: Playlist) {
        val last = prefs.lastChannel
        val ch = pl.channels.firstOrNull { it.name == last } ?: pl.channels.first()
        playChannel(ch, prefs.lineFor(ch.name))
    }

    // ---------- 换台 / 换线 ----------

    private fun playChannel(ch: Channel, line: Int) {
        current = ch
        statusText.visibility = android.view.View.GONE
        prefs.lastChannel = ch.name
        controller?.play(ch, line)
    }

    private fun stepChannel(delta: Int) {
        val pl = playlist ?: return
        val cur = current ?: return
        val n = pl.channels.size
        val idx = (cur.number - 1 + delta + n) % n
        val next = pl.channels[idx]
        playChannel(next, prefs.lineFor(next.name))
    }

    private fun stepLine(delta: Int) {
        val ch = current ?: return
        val c = controller ?: return
        val line = (currentLine + delta + ch.streams.size) % ch.streams.size
        statusText.visibility = android.view.View.GONE
        c.play(ch, line)
    }

    private var currentLine = 0

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { stepChannel(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { stepChannel(+1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { stepLine(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { stepLine(+1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- PlayerController.Listener ----------

    override fun onStreamChanged(channel: Channel, streamIndex: Int, automatic: Boolean) {
        currentLine = streamIndex
        prefs.setLine(channel.name, streamIndex)
        showInfo(channel, streamIndex, automatic)
    }

    override fun onPlaying(channel: Channel, streamIndex: Int) {
        statusText.visibility = android.view.View.GONE
    }

    override fun onAllFailed(channel: Channel, message: String) {
        showStatus("「${channel.name}」所有线路（${channel.streams.size} 条）均无法播放\n$message\n按 上/下 换台，左/右 重试线路")
    }

    // ---------- UI ----------

    private fun showInfo(ch: Channel, line: Int, automatic: Boolean) {
        val text = "%03d  %s   线路 %d/%d".format(ch.number, ch.name, line + 1, ch.streams.size) +
            if (automatic) "  (自动切换)" else ""
        infoBar.text = text
        infoBar.visibility = android.view.View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 3000)
    }

    private fun showStatus(msg: String) {
        statusText.text = msg
        statusText.visibility = android.view.View.VISIBLE
    }

    private companion object {
        const val TAG = "LiveTv"
    }
}

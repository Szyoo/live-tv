package xyz.szyyw.livetv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextClock
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.OkHttpClient
import xyz.szyyw.livetv.data.PlaylistLoader
import xyz.szyyw.livetv.data.Prefs
import xyz.szyyw.livetv.logic.ChannelNumberInput
import xyz.szyyw.livetv.logic.Failure
import xyz.szyyw.livetv.player.PlayerController
import xyz.szyyw.livetv.playlist.Channel
import xyz.szyyw.livetv.playlist.Playlist
import xyz.szyyw.livetv.ui.Row
import xyz.szyyw.livetv.ui.RowAdapter
import xyz.szyyw.livetv.ui.focusRow
import xyz.szyyw.livetv.update.UpdateInfo
import xyz.szyyw.livetv.update.Updater
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), PlayerController.Listener {
    private lateinit var playerView: PlayerView
    private lateinit var infoBar: View
    private lateinit var infoTitle: TextView
    private lateinit var infoSub: TextView
    private lateinit var clock: TextClock
    private lateinit var numberText: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var overlay: View
    private lateinit var groupList: RecyclerView
    private lateinit var channelList: RecyclerView
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
    private var currentLine = 0
    private var loading = false
    private var pendingIdx: Int? = null
    private var lastBackAt = 0L
    private var settingsDialog: AlertDialog? = null
    private val numberInput = ChannelNumberInput()
    private val updateExec = Executors.newSingleThreadExecutor()
    private val updater by lazy { Updater(applicationContext, client) }
    private var pendingApk: File? = null

    // 浮层数据
    private var groups: List<String> = emptyList()
    private var selectedGroup = 0
    private var groupChannels: List<Channel> = emptyList()
    private lateinit var groupAdapter: RowAdapter
    private lateinit var channelAdapter: RowAdapter

    private val hideInfo = Runnable { infoBar.visibility = View.GONE; clock.visibility = View.GONE }
    private val hideHint = Runnable { hintText.visibility = View.GONE }
    private val closeOverlay = Runnable { hideOverlay() }
    private val numberPoll = object : Runnable {
        override fun run() {
            val n = numberInput.poll(SystemClock.uptimeMillis())
            if (n != null) commitNumber(n)
            else if (numberInput.active) handler.postDelayed(this, 200)
        }
    }
    private val commitPending = Runnable {
        val i = pendingIdx ?: return@Runnable
        pendingIdx = null
        val ch = playlist?.channels?.getOrNull(i) ?: return@Runnable
        playChannel(ch, prefs.lineFor(ch.name))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.player_view)
        infoBar = findViewById(R.id.info_bar)
        infoTitle = findViewById(R.id.info_title)
        infoSub = findViewById(R.id.info_sub)
        clock = findViewById(R.id.clock)
        numberText = findViewById(R.id.number_text)
        spinner = findViewById(R.id.spinner)
        statusText = findViewById(R.id.status_text)
        hintText = findViewById(R.id.hint_text)
        overlay = findViewById(R.id.overlay)
        groupList = findViewById(R.id.group_list)
        channelList = findViewById(R.id.channel_list)
        prefs = Prefs(this)
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())

        groupAdapter = RowAdapter(onClick = ::onGroupClick, onFocus = ::onGroupFocus)
        channelAdapter = RowAdapter(onClick = ::onChannelClick, onFocus = {})
        for (rv in listOf(groupList, channelList)) {
            rv.layoutManager = LinearLayoutManager(this)
            rv.itemAnimator = null
        }
        groupList.adapter = groupAdapter
        channelList.adapter = channelAdapter

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })
        applyDebugIntent(intent)
        checkForUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (applyDebugIntent(intent)) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) reloadPlaylist()
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
        pendingIdx = null
        numberInput.cancel()
        numberText.visibility = View.GONE
        hideOverlay()
        playerView.player = null
        controller?.release()
        controller = null
    }

    override fun onResume() {
        super.onResume()
        val apk = pendingApk
        if (apk != null && updater.canInstall()) {
            pendingApk = null
            updater.install(apk)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
        updateExec.shutdownNow()
    }

    // ---------- 启动 / 加载 ----------

    private fun startUp() {
        if (loading) return
        val url = prefs.sourceUrl
        if (url.isBlank()) {
            showStatus("尚未设置频道源地址\n请在设置中填写")
            showSettings()
            return
        }
        loading = true
        showStatus("正在加载频道清单…")
        spinner.visibility = View.VISIBLE
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
                    spinner.visibility = View.GONE
                    showStatus("频道清单加载失败：${e.message ?: e.javaClass.simpleName}\n按「确认」长按或「菜单」键打开设置修改源地址")
                }
            }
        }
    }

    private fun reloadPlaylist() {
        handler.removeCallbacks(commitPending)
        pendingIdx = null
        playlist = null
        current = null
        controller?.pause()
        startUp()
    }

    private fun resume(pl: Playlist) {
        val last = prefs.lastChannel
        val ch = pl.channels.firstOrNull { it.name == last } ?: pl.channels.first()
        playChannel(ch, prefs.lineFor(ch.name))
    }

    // ---------- 换台 / 换线 ----------

    private fun playChannel(ch: Channel, line: Int) {
        current = ch
        pendingIdx = null
        handler.removeCallbacks(commitPending)
        statusText.visibility = View.GONE
        prefs.lastChannel = ch.name
        controller?.play(ch, line)
    }

    /** 连按只更新目标并显示信息条，停止 400ms 后才真正起播。 */
    private fun stepChannel(delta: Int) {
        val pl = playlist ?: return
        val base = pendingIdx ?: current?.let { it.number - 1 } ?: return
        val n = pl.channels.size
        val idx = ((base + delta) % n + n) % n
        pendingIdx = idx
        val ch = pl.channels[idx]
        showInfo(ch, prefs.lineFor(ch.name), false)
        handler.removeCallbacks(commitPending)
        handler.postDelayed(commitPending, 400)
    }

    private fun stepLine(delta: Int) {
        if (pendingIdx != null) { handler.removeCallbacks(commitPending); commitPending.run() }
        val ch = current ?: return
        val c = controller ?: return
        statusText.visibility = View.GONE
        c.play(ch, (currentLine + delta + ch.streams.size) % ch.streams.size)
    }

    private fun jumpToNumber(n: Int) {
        val pl = playlist ?: return
        val ch = pl.channels.getOrNull(n - 1)
        if (ch == null) { showHint("没有 %d 号频道（共 %d 个）".format(n, pl.channels.size)); return }
        playChannel(ch, prefs.lineFor(ch.name))
    }

    private fun commitNumber(n: Int) {
        numberText.visibility = View.GONE
        jumpToNumber(n)
    }

    // ---------- 按键 ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (overlay.visibility == View.VISIBLE) {
            handler.removeCallbacks(closeOverlay)
            handler.postDelayed(closeOverlay, OVERLAY_TIMEOUT_MS)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (overlay.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { focusGroupColumn(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { focusChannelColumn(); return true }
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // 首行按上 / 末行按下：停在原地，不让焦点跳到另一列
                val rv = if (groupList.hasFocus()) groupList else channelList
                val pos = currentFocus?.let { rv.findContainingViewHolder(it)?.bindingAdapterPosition } ?: -1
                val last = (rv.adapter?.itemCount ?: 0) - 1
                if (pos < 0 || (keyCode == KeyEvent.KEYCODE_DPAD_UP && pos == 0) || (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && pos == last)) return true
            }
            return super.onKeyDown(keyCode, event) // 其余上下交给焦点导航
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { stepChannel(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { stepChannel(+1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { stepLine(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { stepLine(+1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                event.startTracking()
                return true
            }
            KeyEvent.KEYCODE_MENU -> return true
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { onDigit(keyCode - KeyEvent.KEYCODE_0); return true }
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { onDigit(keyCode - KeyEvent.KEYCODE_NUMPAD_0); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (overlay.visibility != View.VISIBLE &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            showSettings()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (overlay.visibility != View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.isTracking && !event.isCanceled) {
                        val n = numberInput.confirm()
                        if (n != null) commitNumber(n) else showOverlay()
                    }
                    return true
                }
                KeyEvent.KEYCODE_MENU -> { showSettings(); return true }
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun onDigit(d: Int) {
        if (playlist == null) return
        handler.removeCallbacks(commitPending)
        pendingIdx = null
        numberText.text = numberInput.digit(d, SystemClock.uptimeMillis())
        numberText.visibility = View.VISIBLE
        handler.removeCallbacks(numberPoll)
        handler.postDelayed(numberPoll, 200)
    }

    private fun onBack() {
        when {
            overlay.visibility == View.VISIBLE -> hideOverlay()
            numberInput.active -> { numberInput.cancel(); numberText.visibility = View.GONE }
            else -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastBackAt <= 2000) {
                    Log.i(TAG, "back x2: exit")
                    finish()
                } else {
                    lastBackAt = now
                    showHint("再按一次返回退出", 2000)
                }
            }
        }
    }

    // ---------- 频道列表浮层 ----------

    private fun showOverlay() {
        val pl = playlist ?: return
        val cur = current ?: pl.channels.first()
        groups = pl.channels.map { it.group }.distinct()
        val rows = groups.map { g -> Row("$g  (${pl.channels.count { it.group == g }})") } + Row("⚙ 设置")
        groupAdapter.rows = rows
        selectGroup(groups.indexOf(cur.group).coerceAtLeast(0))
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(closeOverlay)
        handler.postDelayed(closeOverlay, OVERLAY_TIMEOUT_MS)
        val pos = groupChannels.indexOfFirst { it.number == cur.number }.coerceAtLeast(0)
        channelList.focusRow(pos)
    }

    private fun hideOverlay() {
        handler.removeCallbacks(closeOverlay)
        overlay.visibility = View.GONE
    }

    private fun selectGroup(i: Int) {
        selectedGroup = i
        val pl = playlist ?: return
        val g = groups.getOrNull(i) ?: return
        groupChannels = pl.channels.filter { it.group == g }
        val curNum = current?.number
        channelAdapter.rows = groupChannels.map { Row("%03d  %s".format(it.number, it.name), it.number == curNum) }
        groupAdapter.setHighlighted(i)
        channelList.scrollToPosition(0)
    }

    private fun onGroupFocus(pos: Int) {
        if (pos < groups.size && pos != selectedGroup) selectGroup(pos)
    }

    private fun onGroupClick(pos: Int) {
        if (pos >= groups.size) { hideOverlay(); showSettings() } else focusChannelColumn()
    }

    private fun onChannelClick(pos: Int) {
        val ch = groupChannels.getOrNull(pos) ?: return
        hideOverlay()
        playChannel(ch, prefs.lineFor(ch.name))
    }

    private fun focusGroupColumn() {
        groupList.focusRow(selectedGroup)
    }

    private fun focusChannelColumn() {
        if (groupChannels.isEmpty() || groupList.focusedChild == null) return
        val cur = current?.number
        val pos = groupChannels.indexOfFirst { it.number == cur }.coerceAtLeast(0)
        channelList.focusRow(pos)
    }

    // ---------- 设置对话框 ----------

    private fun showSettings() {
        if (settingsDialog?.isShowing == true) return
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val input = v.findViewById<EditText>(R.id.source_input)
        input.setText(prefs.sourceUrl)
        input.setSelection(input.text.length)
        v.findViewById<TextView>(R.id.version_text).text = "版本 ${BuildConfig.VERSION_NAME}"
        val dlg = AlertDialog.Builder(this).setView(v).create()
        v.findViewById<Button>(R.id.btn_save).setOnClickListener {
            prefs.sourceUrl = input.text.toString().trim()
            Log.i(TAG, "settings: source saved, reloading")
            dlg.dismiss()
            reloadPlaylist()
        }
        v.findViewById<Button>(R.id.btn_reload).setOnClickListener {
            dlg.dismiss()
            reloadPlaylist()
        }
        v.findViewById<Button>(R.id.btn_cancel).setOnClickListener { dlg.dismiss() }
        settingsDialog = dlg
        if (prefs.sourceUrl.isNotBlank()) {
            // 已有源地址：焦点放在「重新加载清单」，不自动弹键盘；移到输入框按确认才弹
            dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            dlg.show()
            v.findViewById<Button>(R.id.btn_reload).requestFocus()
        } else {
            dlg.show()
            input.requestFocus() // 首次使用：直接输入并弹键盘
        }
    }

    // ---------- 自动更新 ----------

    /** 每次启动在后台检查一次；失败一律静默。"以后再说"只对本次启动有效。 */
    private fun checkForUpdate() {
        val url = BuildConfig.UPDATE_URL
        if (url.isBlank()) return
        updateExec.execute {
            val info = updater.check(url, BuildConfig.VERSION_CODE) ?: return@execute
            Log.i(TAG, "update available: ${info.versionName} (${info.versionCode})")
            runOnUiThread { if (!isFinishing && !isDestroyed) showUpdateDialog(info) }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        val msg = buildString {
            append("当前版本 ${BuildConfig.VERSION_NAME}，最新版本 ${info.versionName}")
            if (info.notes.isNotBlank()) append("\n\n").append(info.notes)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("发现新版本 ${info.versionName}")
            .setMessage(msg)
            .setPositiveButton("更新") { _, _ -> startDownload(info) }
            .setNegativeButton("以后再说", null)
            .show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
    }

    private fun startDownload(info: UpdateInfo) {
        val dlg = AlertDialog.Builder(this)
            .setTitle("正在下载 ${info.versionName}")
            .setMessage("0%")
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> updater.cancel() }
            .show()
        updateExec.execute {
            try {
                val f = updater.download(info) { p ->
                    runOnUiThread { dlg.setMessage(if (p >= 0) "$p%" else "下载中…") }
                }
                Log.i(TAG, "update downloaded: ${f.length()} bytes")
                runOnUiThread { dlg.dismiss(); installApk(f) }
            } catch (e: Exception) {
                Log.w(TAG, "update download failed", e)
                runOnUiThread {
                    dlg.dismiss()
                    showHint("更新下载失败，请稍后重试或检查网络", 4000)
                }
            }
        }
    }

    private fun installApk(f: File) {
        if (updater.canInstall()) {
            updater.install(f)
        } else {
            pendingApk = f
            Log.i(TAG, "need unknown-sources permission")
            showHint("请允许本应用安装未知应用，授权后按返回键继续安装", 5000)
            if (!updater.openUnknownSourcesSettings()) {
                showHint("此设备无法打开授权页面，请在系统设置中允许本应用安装未知应用", 6000)
            }
        }
    }

    // ---------- PlayerController.Listener ----------

    override fun onStreamChanged(channel: Channel, streamIndex: Int, automatic: Boolean) {
        currentLine = streamIndex
        prefs.setLine(channel.name, streamIndex)
        showInfo(channel, streamIndex, automatic)
    }

    override fun onLoading(loading: Boolean) {
        spinner.visibility = if (loading) View.VISIBLE else View.GONE
    }

    override fun onPlaying(channel: Channel, streamIndex: Int) {
        statusText.visibility = View.GONE
    }

    override fun onAllFailed(channel: Channel, failure: Failure) {
        showStatus(
            "「${channel.name}」的 ${channel.streams.size} 条线路都无法播放\n${failure.describe()}\n\n上/下 换台，左/右 重试线路"
        )
    }

    // ---------- UI ----------

    private fun showInfo(ch: Channel, line: Int, automatic: Boolean) {
        infoTitle.text = "%03d  %s".format(ch.number, ch.name)
        infoSub.text = "${ch.group}  ·  线路 ${line + 1}/${ch.streams.size}" + if (automatic) "  (自动切换)" else ""
        infoBar.visibility = View.VISIBLE
        clock.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 3000)
    }

    private fun showStatus(msg: String) {
        statusText.text = msg
        statusText.visibility = View.VISIBLE
    }

    private fun showHint(msg: String, ms: Long = 2500) {
        hintText.text = msg
        hintText.visibility = View.VISIBLE
        handler.removeCallbacks(hideHint)
        handler.postDelayed(hideHint, ms)
    }

    private companion object {
        const val TAG = "LiveTv"
        const val OVERLAY_TIMEOUT_MS = 8000L
    }
}

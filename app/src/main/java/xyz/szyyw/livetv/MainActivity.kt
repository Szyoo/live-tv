package xyz.szyyw.livetv

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Build
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
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.OkHttpClient
import xyz.szyyw.livetv.data.CacheTools
import xyz.szyyw.livetv.data.PlaylistLoader
import xyz.szyyw.livetv.data.Prefs
import xyz.szyyw.livetv.logic.ChannelNumberInput
import xyz.szyyw.livetv.logic.ClockMode
import xyz.szyyw.livetv.logic.Dir
import xyz.szyyw.livetv.logic.ExitMode
import xyz.szyyw.livetv.logic.Failure
import xyz.szyyw.livetv.logic.HorizontalMode
import xyz.szyyw.livetv.logic.FileStamp
import xyz.szyyw.livetv.logic.PlaylistSource
import xyz.szyyw.livetv.logic.ResizeOption
import xyz.szyyw.livetv.logic.Settings
import xyz.szyyw.livetv.logic.StartMode
import xyz.szyyw.livetv.logic.VerticalMode
import xyz.szyyw.livetv.player.MediaSessionHolder
import xyz.szyyw.livetv.player.PlayerController
import xyz.szyyw.livetv.playlist.Channel
import xyz.szyyw.livetv.playlist.Playlist
import xyz.szyyw.livetv.ui.Row
import xyz.szyyw.livetv.ui.SHeader
import xyz.szyyw.livetv.ui.SItem
import xyz.szyyw.livetv.ui.SRow
import xyz.szyyw.livetv.ui.SettingsAdapter
import xyz.szyyw.livetv.ui.RowAdapter
import xyz.szyyw.livetv.ui.focusRow
import xyz.szyyw.livetv.update.CheckResult
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
    private var reloadAfterLoad = false
    private var loadedUsesPushed = false
    private var loadedStamp: FileStamp? = null
    private var pendingIdx: Int? = null
    private var lastBackAt = 0L
    private lateinit var settingsPanel: View
    private lateinit var settingsList: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private var mediaSession: MediaSessionHolder? = null
    private var started = false           // 本次进程是否已做过「启动时播放」
    private var debugUpdateUrl: String? = null
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

    private val hideInfo = Runnable {
        infoBar.visibility = View.GONE
        if (prefs.clockMode != ClockMode.ALWAYS) clock.visibility = View.GONE
    }
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
        settingsPanel = findViewById(R.id.settings_panel)
        settingsList = findViewById(R.id.settings_list)
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
        settingsAdapter = SettingsAdapter(onClick = { activateSetting(it.id, 0) })
        settingsList.layoutManager = LinearLayoutManager(this)
        settingsList.itemAnimator = null
        settingsList.adapter = settingsAdapter
        groupList.adapter = groupAdapter
        channelList.adapter = channelAdapter

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })
        updater.onInstallResult = { ok, reason ->
            if (!ok && !isFinishing && !isDestroyed) {
                showMessage("安装失败", "安装失败：$reason，可在设置里重新安装")
            }
        }
        applyDebugIntent(intent)
        applyClockMode()
        updateExec.execute { updater.cleanup(BuildConfig.VERSION_CODE) }
        checkForUpdate()
        showHint("长按「确定」打开设置", 5000)
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
        i?.getStringExtra("update_url")?.let { debugUpdateUrl = it; Log.i(TAG, "debug: update url overridden") }
        val url = i?.getStringExtra("source_url") ?: return false
        Log.i(TAG, "debug: source url overridden")
        prefs.sourceUrl = url
        return true
    }

    override fun onStart() {
        super.onStart()
        controller = PlayerController(this, client, this).also {
            it.stallTimeoutMs = prefs.stallSeconds * 1000L
            playerView.player = it.player
            mediaSession = MediaSessionHolder(applicationContext, it.player)
        }
        applyResize()
        ReloadReceiver.foregroundHook = { reloadPlaylist() }
        val pl = playlist
        if (pl == null) startUp()
        else if (pushedChanged()) { Log.i(TAG, "pushed playlist changed, reloading"); reloadPlaylist() }
        else resume(pl)
    }

    override fun onStop() {
        super.onStop()
        ReloadReceiver.foregroundHook = null
        handler.removeCallbacksAndMessages(null)
        pendingIdx = null
        numberInput.cancel()
        numberText.visibility = View.GONE
        hideOverlay()
        hideSettings()
        hintText.visibility = View.GONE
        mediaSession?.release()
        mediaSession = null
        playerView.player = null
        controller?.release()
        controller = null
    }

    override fun onResume() {
        super.onResume()
        // onStart 之后再检查一次：从设置里装 APK 等场景回来只会走 onResume
        if (playlist != null && !loading && pushedChanged()) { Log.i(TAG, "pushed playlist changed (resume), reloading"); reloadPlaylist() }
        val apk = pendingApk
        if (apk != null && updater.canInstall()) {
            pendingApk = null
            updater.install(apk)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
        updater.close()
        updateExec.shutdownNow()
    }

    // ---------- 启动 / 加载 ----------

    private fun pushedChanged(): Boolean {
        val now = PlaylistLoader.stampOf(PlaylistLoader.pushedFile(applicationContext))
        return PlaylistSource.needsReload(loadedUsesPushed, loadedStamp, now)
    }

    private fun startUp() {
        if (loading) return
        val url = prefs.sourceUrl
        loading = true
        showStatus("正在加载频道清单…")
        spinner.visibility = View.VISIBLE
        val loader = PlaylistLoader(applicationContext, client)
        io.execute {
            val r = loader.load(url)
            handler.post {
                loading = false
                r.onSuccess { res ->
                    val pl = res.playlist
                    playlist = pl
                    loadedUsesPushed = res.usesPushed
                    loadedStamp = res.pushedStamp
                    Log.i(TAG, "playlist loaded: ${pl.channels.size} channels, ${pl.channels.sumOf { it.streams.size }} lines (${res.source})")
                    if (res.source == PlaylistLoader.Source.FALLBACK_PUSHED) {
                        Toast.makeText(this, "自定义源加载失败，已改用推送的清单", Toast.LENGTH_LONG).show()
                    }
                    if (reloadAfterLoad) { reloadAfterLoad = false; reloadPlaylist() }
                    else if (controller != null) resume(pl)
                }.onFailure { e ->
                    playlist = null
                    loadedUsesPushed = false
                    loadedStamp = null
                    spinner.visibility = View.GONE
                    if (e is PlaylistLoader.NoPlaylistException) {
                        Log.i(TAG, "no playlist: nothing pushed and no custom url")
                        showStatus("${PlaylistSource.EMPTY_TITLE}\n${PlaylistSource.EMPTY_HINT}")
                    } else {
                        Log.e(TAG, "playlist load failed", e)
                        showStatus("频道清单加载失败：${e.message ?: e.javaClass.simpleName}\n按「确认」长按或「菜单」键打开设置修改源地址")
                    }
                }
            }
        }
    }

    /** 重新加载清单；当前频道靠 prefs.lastChannel 按名字匹配保持。 */
    private fun reloadPlaylist() {
        if (loading) { reloadAfterLoad = true; return }
        handler.removeCallbacks(commitPending)
        pendingIdx = null
        playlist = null
        current = null
        controller?.pause()
        startUp()
    }

    private fun resume(pl: Playlist) {
        // 「指定频道」只在本次进程第一次起播时生效；之后回到前台/重新加载仍接着上次看的
        val wanted = if (!started && prefs.startMode == StartMode.FIXED) prefs.startChannel else prefs.lastChannel
        started = true
        val ch = pl.channels.firstOrNull { it.name == wanted } ?: pl.channels.firstOrNull { it.name == prefs.lastChannel } ?: pl.channels.first()
        playChannel(ch, prefs.lineFor(ch.name))
    }

    // ---------- 换台 / 换线 ----------

    private fun playChannel(ch: Channel, line: Int) {
        current = ch
        pendingIdx = null
        handler.removeCallbacks(commitPending)
        statusText.visibility = View.GONE
        prefs.lastChannel = ch.name
        mediaSession?.setTitle(ch.name)
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
        if (settingsPanel.visibility == View.VISIBLE) return onSettingsKey(keyCode, event)
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
            KeyEvent.KEYCODE_DPAD_UP -> { applyDpad(Dir.UP); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { applyDpad(Dir.DOWN); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { applyDpad(Dir.LEFT); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { applyDpad(Dir.RIGHT); return true }
            KeyEvent.KEYCODE_CHANNEL_UP -> { stepChannel(-1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { stepChannel(+1); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { controller?.player?.let { if (it.isPlaying) it.pause() else it.play() }; return true }
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

    /** 播放画面的方向键：按「按键」设置映射为换台或换线路。 */
    private fun applyDpad(dir: Dir) {
        val a = Settings.mapDpad(dir, prefs.verticalMode, prefs.horizontalMode)
        Log.i(TAG, "dpad $dir -> channel ${a.channelDelta} line ${a.lineDelta}")
        if (a.channelDelta != 0) stepChannel(a.channelDelta)
        if (a.lineDelta != 0) stepLine(a.lineDelta)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (overlay.visibility != View.VISIBLE && settingsPanel.visibility != View.VISIBLE &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            showSettings()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (overlay.visibility != View.VISIBLE && settingsPanel.visibility != View.VISIBLE) {
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
            settingsPanel.visibility == View.VISIBLE -> hideSettings()
            overlay.visibility == View.VISIBLE -> hideOverlay()
            numberInput.active -> { numberInput.cancel(); numberText.visibility = View.GONE }
            prefs.exitMode == ExitMode.SINGLE_BACK -> {
                Log.i(TAG, "back x1: exit")
                finish()
            }
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

    // ---------- 设置界面 ----------

    private fun showSettings() {
        if (settingsPanel.visibility == View.VISIBLE) return
        hideOverlay()
        refreshSettings()
        settingsPanel.visibility = View.VISIBLE
        settingsList.focusRow(1)
        Log.i(TAG, "settings: opened")
    }

    private fun hideSettings() {
        if (settingsPanel.visibility != View.VISIBLE) return
        settingsPanel.visibility = View.GONE
        Log.i(TAG, "settings: closed")
    }

    private fun refreshSettings() { settingsAdapter.rows = buildSettingsRows() }

    private fun buildSettingsRows(): List<SRow> {
        val src = prefs.sourceUrl
        val pushedStamp = PlaylistLoader.stampOf(PlaylistLoader.pushedFile(applicationContext))
        val kind = PlaylistSource.choose(src, pushedStamp)
        val cur = current
        val startLabel = when (prefs.startMode) {
            StartMode.LAST -> StartMode.LAST.label
            StartMode.FIXED -> "指定：${prefs.startChannel ?: "未选择"}"
        }
        return listOf(
            SHeader("频道源"),
            SItem("src_now", "当前来源", PlaylistSource.label(kind, playlist?.channels?.size, pushedStamp), false),
            SItem("src_edit", "修改源地址", if (src.isBlank()) "未设置（使用推送的清单）" else src, false),
            SItem("src_reset", "清除自定义地址（改用推送的清单）", "", false),
            SItem("src_reload", "立即重新加载清单", "", false),
            SHeader("播放"),
            SItem("start", "启动时播放", startLabel, true),
            SItem("resize", "画面比例", prefs.resize.label, true),
            SItem("stall", "卡顿多久自动换线路", Settings.stallLabel(prefs.stallSeconds), true),
            SItem("info", "换台后信息条显示", Settings.infoLabel(prefs.infoSeconds), true),
            SItem("clock", "右上角时钟", prefs.clockMode.label, true),
            SHeader("当前频道"),
            SItem("line", "选择线路", if (cur == null) "无" else "线路 ${currentLine + 1}/${cur.streams.size}（${cur.name}）", false),
            SHeader("按键"),
            SItem("vertical", "上下键换台方向", prefs.verticalMode.label, true),
            SItem("horizontal", "左右键功能", prefs.horizontalMode.label, true),
            SItem("exit", "退出方式", prefs.exitMode.label, true),
            SHeader("系统"),
            SItem("update", "检查更新", "当前 ${BuildConfig.VERSION_NAME}", false),
            SItem("clear_memory", "清除记忆", "上次频道 / 线路记忆", false),
            SItem("clear_cache", "清除缓存", "占用 ${CacheTools.format(CacheTools.size(applicationContext))}", false),
            SItem("keys", "按键说明", "", false),
            SItem("about", "关于", "", false),
        )
    }

    private fun settingsFocusPos(): Int =
        currentFocus?.let { settingsList.findContainingViewHolder(it)?.bindingAdapterPosition } ?: -1

    private fun onSettingsKey(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { moveSettingsFocus(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveSettingsFocus(+1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val item = settingsAdapter.rows.getOrNull(settingsFocusPos()) as? SItem
                if (item != null && item.cyclic) activateSetting(item.id, if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else +1)
                return true
            }
        }
        return super.onKeyDown(keyCode, event) // 确定键交给获得焦点的行（触发点击）
    }

    /** 上下移动焦点，跳过分组标题；到头停住。 */
    private fun moveSettingsFocus(delta: Int) {
        val rows = settingsAdapter.rows
        var p = settingsFocusPos()
        if (p < 0) p = if (delta > 0) -1 else rows.size
        var t = p + delta
        while (t in rows.indices && rows[t] !is SItem) t += delta
        if (t in rows.indices) settingsList.focusRow(t)
    }

    /** delta：0 = 按确定，±1 = 左右键。循环项确定等于 +1。 */
    private fun activateSetting(id: String, delta: Int) {
        val d = if (delta == 0) 1 else delta
        when (id) {
            "src_now" -> {}
            "src_edit" -> showSourceDialog()
            "src_reset" -> {
                if (prefs.sourceUrl.isBlank()) { Toast.makeText(this, "当前没有自定义地址", Toast.LENGTH_SHORT).show() }
                else {
                    prefs.sourceUrl = ""
                    Log.i(TAG, "settings: custom url cleared")
                    hideSettings(); reloadPlaylist()
                    Toast.makeText(this, "已清除自定义地址", Toast.LENGTH_SHORT).show()
                }
            }
            "src_reload" -> { Log.i(TAG, "settings: reload playlist"); hideSettings(); reloadPlaylist() }
            "start" -> {
                if (delta == 0) pickStartChannel()
                else {
                    val m = Settings.cycle(prefs.startMode, d)
                    prefs.startMode = m
                    if (m == StartMode.FIXED && prefs.startChannel == null) {
                        prefs.startChannel = current?.name ?: playlist?.channels?.firstOrNull()?.name
                    }
                    Log.i(TAG, "settings: start mode=$m channel=${prefs.startChannel}")
                }
            }
            "resize" -> { prefs.resize = Settings.cycle(prefs.resize, d); applyResize(); Log.i(TAG, "settings: resize=${prefs.resize}") }
            "stall" -> {
                prefs.stallSeconds = Settings.cycle(Settings.STALL_OPTIONS, prefs.stallSeconds, d)
                controller?.stallTimeoutMs = prefs.stallSeconds * 1000L
                Log.i(TAG, "settings: stall=${prefs.stallSeconds}s")
            }
            "info" -> { prefs.infoSeconds = Settings.cycle(Settings.INFO_OPTIONS, prefs.infoSeconds, d); Log.i(TAG, "settings: info=${prefs.infoSeconds}s") }
            "clock" -> { prefs.clockMode = Settings.cycle(prefs.clockMode, d); applyClockMode(); Log.i(TAG, "settings: clock=${prefs.clockMode}") }
            "line" -> pickLine()
            "vertical" -> { prefs.verticalMode = Settings.cycle(prefs.verticalMode, d); Log.i(TAG, "settings: vertical=${prefs.verticalMode}") }
            "horizontal" -> { prefs.horizontalMode = Settings.cycle(prefs.horizontalMode, d); Log.i(TAG, "settings: horizontal=${prefs.horizontalMode}") }
            "exit" -> { prefs.exitMode = Settings.cycle(prefs.exitMode, d); Log.i(TAG, "settings: exit=${prefs.exitMode}") }
            "update" -> manualCheckUpdate()
            "clear_memory" -> confirm(
                "清除记忆",
                "将忘掉「上次观看的频道」和「每个频道记住的线路」，并删除自定义源的清单缓存。\n\n源地址和所有设置项不受影响。",
            ) {
                prefs.clearMemory()
                Log.i(TAG, "settings: memory cleared")
                refreshSettings()
                Toast.makeText(this, "已清除记忆", Toast.LENGTH_SHORT).show()
            }
            "clear_cache" -> confirm(
                "清除缓存",
                "将删除已下载的更新安装包、临时缓存和自定义源的清单缓存（当前占用 ${CacheTools.format(CacheTools.size(applicationContext))}）。\n\n源地址、所有设置、上次频道、线路记忆都会保留。",
            ) {
                io.execute {
                    CacheTools.clear(applicationContext)
                    Log.i(TAG, "settings: cache cleared")
                    runOnUiThread { refreshSettings(); Toast.makeText(this, "已清除缓存", Toast.LENGTH_SHORT).show() }
                }
            }
            "keys" -> showMessage("按键说明", KEY_HELP)
            "about" -> showAbout()
        }
        refreshSettings()
    }

    private fun applyResize() {
        playerView.resizeMode = when (prefs.resize) {
            ResizeOption.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            ResizeOption.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            ResizeOption.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
    }

    private fun applyClockMode() {
        clock.visibility = when (prefs.clockMode) {
            ClockMode.ALWAYS -> View.VISIBLE
            ClockMode.HIDDEN -> View.GONE
            ClockMode.WITH_INFO -> if (infoBar.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        }
    }

    // ---------- 设置里用到的对话框 ----------

    /** 列表选择（只用方向键 + 确定）：选中即关闭并回调。 */
    private fun showChoice(title: String, items: List<String>, checked: Int, onPick: (Int) -> Unit) {
        val dlg = AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), checked) { d, which -> d.dismiss(); onPick(which) }
            .create()
        dlg.show()
        dlg.listView?.apply {
            selector = ColorDrawable(0xFF1E88E5.toInt())
            if (checked >= 0) setSelection(checked)
            requestFocus()
        }
    }

    private fun showMessage(title: String, msg: String) {
        val dlg = AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("确定", null).show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
    }

    private fun confirm(title: String, msg: String, onYes: () -> Unit) {
        val dlg = AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定清除") { _, _ -> onYes() }
            .show()
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus() // 默认落在「取消」，防误触
    }

    private fun showSourceDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_source, null)
        val input = v.findViewById<EditText>(R.id.source_input)
        input.setText(prefs.sourceUrl)
        input.setSelection(input.text.length)
        val dlg = AlertDialog.Builder(this).setView(v).create()
        v.findViewById<Button>(R.id.btn_save).setOnClickListener {
            prefs.sourceUrl = input.text.toString().trim()
            Log.i(TAG, "settings: source saved, reloading")
            dlg.dismiss()
            hideSettings()
            reloadPlaylist()
        }
        v.findViewById<Button>(R.id.btn_cancel).setOnClickListener { dlg.dismiss() }
        // 不自动弹键盘：焦点放在「保存」，移到输入框按确认才弹
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dlg.show()
        v.findViewById<Button>(R.id.btn_save).requestFocus()
    }

    private fun pickStartChannel() {
        val pl = playlist ?: return
        val items = listOf(StartMode.LAST.label) + pl.channels.map { "%03d  %s".format(it.number, it.name) }
        val checked = if (prefs.startMode == StartMode.LAST) 0 else 1 + pl.channels.indexOfFirst { it.name == prefs.startChannel }.coerceAtLeast(0)
        showChoice("启动时播放", items, checked) { i ->
            if (i == 0) prefs.startMode = StartMode.LAST
            else { prefs.startMode = StartMode.FIXED; prefs.startChannel = pl.channels[i - 1].name }
            Log.i(TAG, "settings: start mode=${prefs.startMode} channel=${prefs.startChannel}")
            refreshSettings()
        }
    }

    private fun pickLine() {
        val ch = current ?: return
        val items = ch.streams.indices.map { "线路 ${it + 1}" + if (it == currentLine) "（正在使用）" else "" }
        showChoice("选择线路 · ${ch.name}", items, currentLine) { i ->
            Log.i(TAG, "settings: pick line ${i + 1}/${ch.streams.size} of ${ch.name}")
            statusText.visibility = View.GONE
            controller?.play(ch, i)   // 回调 onStreamChanged 会记忆该线路
            refreshSettings()
        }
    }

    private fun showAbout() {
        val pl = playlist
        val info = if (pl == null) "当前清单：无" else
            "当前清单：${pl.channels.size} 个频道，共 ${pl.channels.sumOf { it.streams.size }} 条线路"
        val msg = "版本：${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）\n$info\nAndroid ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）"
        showMessage("关于", msg)
    }

    private fun manualCheckUpdate() {
        val url = updateUrl()
        Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show()
        updateExec.execute {
            val r = updater.checkNow(url, BuildConfig.VERSION_CODE)
            val cached = (r as? CheckResult.Newer)?.let { updater.cachedApk(it.info) != null } ?: false
            Log.i(TAG, "manual update check: ${r.javaClass.simpleName} cached=$cached")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (r) {
                    is CheckResult.Newer -> showUpdateDialog(r.info, cached)
                    CheckResult.UpToDate -> showMessage("检查更新", "已是最新版本（${BuildConfig.VERSION_NAME}）")
                    is CheckResult.Failed -> showMessage("检查更新失败", r.reason)
                }
            }
        }
    }

    // ---------- 自动更新 ----------

    /** 每次启动在后台检查一次；失败一律静默。"以后再说"只对本次启动有效。 */
    private fun checkForUpdate() {
        val url = updateUrl()
        if (url.isBlank()) return
        updateExec.execute {
            val info = updater.check(url, BuildConfig.VERSION_CODE) ?: return@execute
            Log.i(TAG, "update available: ${info.versionName} (${info.versionCode})")
            val cached = updater.cachedApk(info) != null
            runOnUiThread { if (!isFinishing && !isDestroyed) showUpdateDialog(info, cached) }
        }
    }

    private fun updateUrl() = debugUpdateUrl ?: BuildConfig.UPDATE_URL

    private fun showUpdateDialog(info: UpdateInfo, cached: Boolean = false) {
        val msg = buildString {
            append("当前版本 ${BuildConfig.VERSION_NAME}，最新版本 ${info.versionName}")
            if (cached) append("\n\n安装包已下载，可直接安装")
            if (info.notes.isNotBlank()) append("\n\n").append(info.notes)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("发现新版本 ${info.versionName}")
            .setMessage(msg)
            .setPositiveButton(if (cached) "直接安装" else "更新") { _, _ -> startDownload(info) }
            .setNegativeButton("以后再说", null)
            .show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
    }

    private fun startDownload(info: UpdateInfo) {
        val cachedFile = updater.cachedApk(info)
        if (cachedFile != null) {
            Log.i(TAG, "update already downloaded, install directly")
            installApk(cachedFile)
            return
        }
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
        if (prefs.clockMode != ClockMode.HIDDEN) clock.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        if (prefs.infoSeconds > 0) handler.postDelayed(hideInfo, prefs.infoSeconds * 1000L)
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
        val KEY_HELP = """播放画面：
上 / 下：换台（方向可在设置里对调）
左 / 右：换线路（可在设置里改成换台）
确定（短按）：打开频道列表
确定（长按）：打开设置
返回：按两次退出（可在设置里改成一次）

频道列表：
上 / 下：移动；左 / 右：在分组与频道间切换
确定：选择；返回：关闭列表

设置：
上 / 下：选择条目
左 / 右 或 确定：切换选项值
返回：关闭设置""".trimIndent()
    }
}

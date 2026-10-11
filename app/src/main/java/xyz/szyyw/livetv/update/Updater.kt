package xyz.szyyw.livetv.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** 检查更新、下载 APK、调起系统安装器。网络方法均为阻塞调用，须在后台线程。 */
class Updater(private val context: Context, client: OkHttpClient) {
    private val http = client.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    @Volatile private var call: Call? = null

    /** 启动时的静默检查：只有「有新版」才返回，其余（含失败）一律 null。 */
    fun check(url: String, currentVersionCode: Int): UpdateInfo? =
        (checkNow(url, currentVersionCode) as? CheckResult.Newer)?.info

    /** 手动检查：区分有新版 / 已是最新 / 失败（附原因）。 */
    fun checkNow(url: String, currentVersionCode: Int): CheckResult {
        if (url.isBlank()) return CheckResult.Failed("未配置更新地址")
        return try {
            val c = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
            val text = c.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return CheckResult.Failed("服务器返回 HTTP ${r.code}")
                r.body.string()
            }
            UpdateInfo.evaluate(text, currentVersionCode)
        } catch (e: java.net.UnknownHostException) {
            Log.i(TAG, "update check failed: ${e.javaClass.simpleName}")
            CheckResult.Failed("无法解析服务器地址，请检查网络")
        } catch (e: java.net.SocketTimeoutException) {
            Log.i(TAG, "update check failed: ${e.javaClass.simpleName}")
            CheckResult.Failed("连接超时，请检查网络")
        } catch (e: Exception) {
            Log.i(TAG, "update check failed: ${e.javaClass.simpleName}")
            CheckResult.Failed("网络异常（${e.javaClass.simpleName}）")
        }
    }

    private val dir: File get() = File(context.cacheDir, "updates").apply { mkdirs() }
    private fun apkFile(versionCode: Int) = File(dir, "live-tv-$versionCode.apk")

    /** 已下载且校验通过（可解析、包名与 versionCode 一致）的安装包；校验失败的会被删除。 */
    fun cachedApk(info: UpdateInfo): File? {
        val f = apkFile(info.versionCode)
        if (!f.isFile) return null
        val pi = try { context.packageManager.getPackageArchiveInfo(f.absolutePath, 0) } catch (e: Exception) { null }
        @Suppress("DEPRECATION")
        val code = pi?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else it.versionCode }
        if (pi == null || pi.packageName != context.packageName || code != info.versionCode) {
            Log.w(TAG, "cached apk invalid, deleting: ${f.name}")
            f.delete()
            return null
        }
        return f
    }

    /** 当前已是该版本或更新时，清掉旧的安装包与残留的 .part 文件。 */
    fun cleanup(currentVersionCode: Int) {
        dir.listFiles()?.forEach { f ->
            val code = Regex("""live-tv-(\d+)\.apk""").matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()
            if (f.name.endsWith(".part") || (code != null && code <= currentVersionCode)) {
                Log.i(TAG, "cleanup update cache: ${f.name}")
                f.delete()
            }
        }
    }

    /**
     * 下载到私有缓存目录（先写 .part，完整后 rename）；返回文件，失败抛异常。
     * 已有校验通过的同版本安装包则直接返回（不重复下载）。onProgress 为 0..100，-1 表示未知大小。
     */
    fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File {
        cachedApk(info)?.let { Log.i(TAG, "update cached, skip download: ${it.name}"); return it }
        val target = apkFile(info.versionCode)
        // 只清其它版本和残留的半截文件
        dir.listFiles()?.forEach { if (it.name != target.name) it.delete() }
        val part = File(dir, target.name + ".part")
        val c = http.newCall(Request.Builder().url(info.apk).build())
        call = c
        Log.i(TAG, "update download start: ${info.apk.substringBefore('?')}")
        try {
            c.execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val body = r.body
                val total = body.contentLength()
                var done = 0L
                var last = -2
                body.byteStream().use { input ->
                    part.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            done += n
                            val p = if (total > 0) (done * 100 / total).toInt() else -1
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
                if (total > 0 && done != total) error("下载不完整")
            }
            if (!part.renameTo(target)) error("无法保存安装包")
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            call = null
        }
        return target
    }

    fun cancel() { call?.cancel() }

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** 打开"允许安装未知应用"设置页；设备上没有该页面时返回 false。 */
    fun openUnknownSourcesSettings(): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) {
        false
    }

    /** 安装结果回调（主线程）：ok=false 时 reason 为中文原因。成功安装时本进程通常会被系统杀掉，不一定能收到。 */
    @Volatile var onInstallResult: ((ok: Boolean, reason: String) -> Unit)? = null
    private var receiver: BroadcastReceiver? = null

    private fun ensureReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Log.i(TAG, "install status=$status msg=$msg")
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        else i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirm != null) {
                            try { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            catch (e: Exception) { onInstallResult?.invoke(false, "无法打开系统安装确认页") }
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> onInstallResult?.invoke(true, "")
                    else -> onInstallResult?.invoke(false, describeStatus(status, msg))
                }
            }
        }
        ContextCompat.registerReceiver(context, r, IntentFilter(ACTION_INSTALL_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    fun close() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    /** 用 PackageInstaller 会话安装（能拿到失败原因）；会话创建/写入出错时回退 ACTION_VIEW，存储满则直接报错。 */
    fun install(apk: File) {
        try {
            ensureReceiver()
            val pi = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            params.setSize(apk.length())
            val id = pi.createSession(params)
            pi.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("live-tv.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(ACTION_INSTALL_RESULT).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
            Log.i(TAG, "install session committed")
        } catch (e: java.io.IOException) {
            Log.w(TAG, "install session write failed", e)
            val full = (e.message ?: "").contains("ENOSPC") || (e.message ?: "").contains("space", true)
            if (full) onInstallResult?.invoke(false, "存储空间不足，请清理空间后重试")
            else installViaView(apk)
        } catch (e: Exception) {
            Log.w(TAG, "install session failed, fallback ACTION_VIEW", e)
            installViaView(apk)
        }
    }

    private fun installViaView(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    companion object {
        private const val TAG = "LiveTv"
        const val ACTION_INSTALL_RESULT = "xyz.szyyw.livetv.INSTALL_RESULT"

        /** PackageInstaller 状态码 → 中文原因。 */
        fun describeStatus(status: Int, msg: String): String = when (status) {
            PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足，请清理空间后重试"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "与已安装版本冲突（签名或版本不一致）"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "安装包与本机不兼容"
            PackageInstaller.STATUS_FAILURE_INVALID -> "安装包无效或已损坏"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "被系统阻止安装"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "已取消安装"
            else -> msg.ifBlank { "未知错误（代码 $status）" }
        }
    }
}

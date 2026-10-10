package xyz.szyyw.livetv.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
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

    /** 失败（网络、格式、地址空）一律返回 null。 */
    fun check(url: String, currentVersionCode: Int): UpdateInfo? {
        if (url.isBlank()) return null
        return try {
            val c = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
            val text = c.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body.string()
            }
            UpdateInfo.parse(text)?.takeIf { it.isNewerThan(currentVersionCode) }
        } catch (e: Exception) {
            Log.i(TAG, "update check skipped: ${e.javaClass.simpleName}")
            null
        }
    }

    /** 下载到私有缓存目录；返回文件，失败抛异常。onProgress 为 0..100，-1 表示未知大小。 */
    fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "live-tv-${info.versionCode}.apk")
        val c = http.newCall(Request.Builder().url(info.apk).build())
        call = c
        try {
            c.execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val body = r.body
                val total = body.contentLength()
                var done = 0L
                var last = -2
                body.byteStream().use { input ->
                    out.outputStream().use { os ->
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
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            call = null
        }
        return out
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

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private companion object { const val TAG = "LiveTv" }
}

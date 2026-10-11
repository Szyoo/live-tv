package xyz.szyyw.livetv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 推送脚本发来的重新加载广播（`am broadcast -n xyz.szyyw.livetv/.ReloadReceiver -a xyz.szyyw.livetv.RELOAD`）。
 * 只在 App 在前台（MainActivity 已 onStart）时才重新加载；不在前台什么也不做，绝不拉起界面。
 */
class ReloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val hook = foregroundHook
        Log.i("LiveTv", "reload broadcast received, foreground=${hook != null}")
        hook?.invoke()
    }

    companion object {
        const val ACTION = "xyz.szyyw.livetv.RELOAD"

        /** MainActivity 在 onStart 设置、onStop 清除（均在主线程）。 */
        @Volatile var foregroundHook: (() -> Unit)? = null
    }
}

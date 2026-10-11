package xyz.szyyw.livetv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 「媒体按键接收方」占位：系统在没有活动媒体会话时会把播放/暂停等按键发给最后播放过媒体的 App。
 * 本 App 注册成这个接收方，但什么都不做——绝不启动 Activity，避免别的 App（如 B 站）被按键拉起。
 */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("LiveTv", "media button received (ignored): ${intent.action}")
    }
}

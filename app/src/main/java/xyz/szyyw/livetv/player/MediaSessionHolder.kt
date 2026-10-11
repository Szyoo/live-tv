package xyz.szyyw.livetv.player

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import androidx.media3.common.Player
import xyz.szyyw.livetv.MediaButtonReceiver

/**
 * 平台 MediaSession：播放期间让系统把本 App 记作「最后播放媒体的 App」，
 * 并把媒体按键接收器指向 [MediaButtonReceiver]（空实现）。遥控器播放/暂停键可暂停/继续。
 */
class MediaSessionHolder(private val context: Context, private val player: Player) {
    private val session = MediaSession(context, "LiveTv")

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()
        override fun onPlaybackStateChanged(playbackState: Int) = refresh()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = refresh()
    }

    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { player.play() }
            override fun onPause() { player.pause() }
        })
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(ComponentName(context, MediaButtonReceiver::class.java))
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        session.setMediaButtonReceiver(PendingIntent.getBroadcast(context, 0, intent, flags))
        player.addListener(playerListener)
        refresh()
        session.isActive = true
        Log.i(TAG, "media session active")
    }

    fun setTitle(title: String) {
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).build())
    }

    private fun refresh() {
        val st = when {
            player.playbackState == Player.STATE_BUFFERING -> PlaybackState.STATE_BUFFERING
            player.playWhenReady && player.playbackState == Player.STATE_READY -> PlaybackState.STATE_PLAYING
            player.playWhenReady -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_PAUSED
        }
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
        session.setPlaybackState(
            PlaybackState.Builder().setActions(actions)
                .setState(st, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build()
        )
    }

    fun release() {
        runCatching { player.removeListener(playerListener) }
        session.isActive = false
        session.release()
        Log.i(TAG, "media session released")
    }

    private companion object { const val TAG = "LiveTv" }
}

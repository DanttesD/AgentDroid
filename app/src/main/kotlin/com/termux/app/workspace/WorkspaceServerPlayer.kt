package com.termux.app.workspace

import android.R
import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.termux.app.TermuxActivity

/** Created only on explicit playback. Owns no service, cache, terminal or global window state.*/
class WorkspaceServerPlayer : AutoCloseable {
    private val dialog: Dialog
    private val stopped: Runnable
    private val view: PlayerView
    private var player: ExoPlayer? = null
    private var closed: Boolean = false
    private var handedOff: Boolean = false

    @OptIn(markerClass = [UnstableApi::class])
    constructor(activity: TermuxActivity, name: String, url: String, stopped: Runnable, external: Runnable, download: Runnable) {
        if (!validUrl(url)) {
            throw IllegalArgumentException("INVALID_MEDIA_URL")
        }
        this.stopped = stopped
        val ru: Boolean = WorkspaceLanguage.code(activity)!!.equals("ru")
        dialog = Dialog(activity, R.style.Theme_Material_NoActionBar)
        val root = LinearLayout(activity)
        root.setOrientation(LinearLayout.VERTICAL)
        root.setBackgroundColor(Color.BLACK)
        val title = TextView(activity)
        title.setText(name)
        title.setTextColor(Pal.TEXT)
        title.setTextSize(15.0f)
        title.setPadding(16, 12, 16, 12)
        root.addView(title)
        view = PlayerView(activity)
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1.0f))
        val error = TextView(activity)
        error.setTextSize(12.0f)
        error.setTextIsSelectable(true)
        error.setPadding(12, 8, 12, 8)
        error.setTextColor(Pal.DANGER)
        error.setVisibility(View.GONE)
        root.addView(error)
        val actions = LinearLayout(activity)
        root.addView(actions)
        val exit = Button(activity)
        exit.setText(if (ru) "Закрыть" else "Close")
        actions.addView(exit, LinearLayout.LayoutParams(0, -2, 1.0f))
        exit.setOnClickListener(View.OnClickListener { v -> close() })
        val other = Button(activity)
        other.setText(if (ru) "Внешний плеер" else "External player")
        actions.addView(other, LinearLayout.LayoutParams(0, -2, 1.0f))
        other.setOnClickListener(View.OnClickListener { v ->
            handedOff = true
            close()
            external.run()
        })
        val save = Button(activity)
        save.setText(if (ru) "Скачать" else "Download")
        actions.addView(save, LinearLayout.LayoutParams(0, -2, 1.0f))
        save.setOnClickListener(View.OnClickListener { v ->
            close()
            download.run()
        })
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener(DialogInterface.OnDismissListener { d -> close() })
        try {
            // No cross-protocol redirects or software decoder extensions. All networking is loopback SSH.
            val http: DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory().setConnectTimeoutMs(15000).setReadTimeoutMs(20000).setAllowCrossProtocolRedirects(false)
            player = ExoPlayer.Builder(activity).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
            player!!.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            player!!.setHandleAudioBecomingNoisy(true)
            view.setPlayer(player)
            view.setKeepScreenOn(true)
            player!!.addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    error.setText(ServerMediaDiagnostics.describe(e, ru))
                    error.setVisibility(View.VISIBLE)
                    view.setKeepScreenOn(false)
                }
            })
            dialog.show()
            dialog.getWindow()!!.setLayout(-1, -1)
            player!!.setMediaItem(MediaItem.fromUri(url))
            player!!.prepare()
            player!!.play()
        } catch (e: RuntimeException) {
            close()
            throw e
        }
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        view.setPlayer(null)
        view.setKeepScreenOn(false)
        if (player != null) {
            player!!.release()
            player = null
        }
        dialog.dismiss()
        if (!handedOff) {
            stopped.run()
        }
    }

    companion object {
        @JvmStatic
        fun validUrl(url: String): Boolean {
            try {
                val u: Uri = Uri.parse(url)
                return "http".equals(u.getScheme()) && "127.0.0.1".equals(u.getHost()) && u.getPort() > 0 && u.getPort() <= 65535 && u.getUserInfo() == null && u.getQuery() == null && u.getFragment() == null && u.getPath() != null && (u.getPath()!! as java.lang.String).matches("/[a-f0-9]{64}")
            } catch (e: Exception) {
                return false
            }
        }
    }
}

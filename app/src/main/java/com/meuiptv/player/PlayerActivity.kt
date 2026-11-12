package com.meuiptv.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var tvInfo: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { tvInfo.visibility = View.GONE }
    private var ext = "m3u8"
    private var triedTs = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Store.playlist.isEmpty()) {
            finish()
            return
        }

        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        tvInfo = findViewById(R.id.tvInfo)
        playerView.useController = false
    }

    override fun onStart() {
        super.onStart()
        if (Store.playlist.isNotEmpty()) startPlayer()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(hideInfo)
        player?.release()
        player = null
        if (::playerView.isInitialized) playerView.player = null
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun startPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 11; TV) IPTVPlayer/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()

        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!triedTs) {
                    triedTs = true
                    ext = "ts"
                    playCurrent(false)
                } else {
                    showInfo("Não foi possível reproduzir este canal.", false)
                }
            }
        })

        playerView.player = p
        player = p
        playCurrent(true)
    }

    private fun playCurrent(reset: Boolean) {
        val p = player ?: return
        val ch = Store.playlist.getOrNull(Store.index) ?: return
        if (reset) {
            ext = "m3u8"
            triedTs = false
        }
        p.setMediaItem(MediaItem.fromUri(Api.streamUrl(ch, ext)))
        p.prepare()
        p.playWhenReady = true
        showInfo(channelLabel(), true)
    }

    private fun channelLabel(): String {
        val ch = Store.playlist.getOrNull(Store.index)
        val name = ch?.name?.ifBlank { "Canal ${ch.id}" } ?: ""
        return "${Store.index + 1}/${Store.playlist.size}  $name"
    }

    private fun showInfo(text: String, autoHide: Boolean) {
        tvInfo.text = text
        tvInfo.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        if (autoHide) handler.postDelayed(hideInfo, 4000)
    }

    private fun change(delta: Int) {
        val n = Store.playlist.size
        if (n == 0) return
        Store.index = ((Store.index + delta) % n + n) % n
        playCurrent(true)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val handledKey = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true
            else -> false
        }
        if (!handledKey) return super.onKeyDown(keyCode, event)
        if ((event?.repeatCount ?: 0) > 0) return true

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> change(1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> change(-1)
            else -> showInfo(channelLabel(), true)
        }
        return true
    }
}

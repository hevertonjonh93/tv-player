package com.meuiptv.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
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
    private var usingFallback = false
    private var isLive = true
    private var epgText = ""
    private var epgToken = 0

    private val current: Item? get() = Store.playlist.getOrNull(Store.index)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val item = current
        if (item == null) {
            finish()
            return
        }
        isLive = item.kind == Kind.LIVE

        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        tvInfo = findViewById(R.id.tvInfo)
        // Ao vivo: zapping com cima/baixo. Filmes/séries: barra com pausar, avançar e voltar.
        playerView.useController = !isLive
    }

    override fun onStart() {
        super.onStart()
        if (current != null) startPlayer()
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        handler.removeCallbacks(hideInfo)
        epgToken++
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
                val pl = player ?: return
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    pl.seekToDefaultPosition()
                    pl.prepare()
                    return
                }
                if (!usingFallback && current?.fallback != null) {
                    usingFallback = true
                    playCurrent(false)
                } else {
                    showInfo("Não foi possível reproduzir este conteúdo.", false)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED || isLive) return
                val item = current ?: return
                Store.savePosition(this@PlayerActivity, item, 0)
                // Séries: passa para o próximo episódio automaticamente.
                if (item.kind == Kind.SERIES && Store.index + 1 < Store.playlist.size) {
                    Store.index++
                    playCurrent(true)
                } else {
                    finish()
                }
            }
        })

        playerView.player = p
        player = p
        playCurrent(true)
        if (!isLive) playerView.requestFocus()
    }

    private fun playCurrent(reset: Boolean) {
        val p = player ?: return
        val item = current ?: return
        if (reset) usingFallback = false
        val url = if (usingFallback) item.fallback ?: item.url else item.url
        val media = MediaItem.fromUri(url)

        val resume = if (!isLive && reset) Store.position(this, item) else 0L
        if (resume > 5000) {
            p.setMediaItem(media, resume)
            Toast.makeText(this, "Continuando de onde parou (${fmt(resume)})", Toast.LENGTH_SHORT).show()
        } else {
            p.setMediaItem(media)
        }
        p.prepare()
        p.playWhenReady = true

        if (reset && item.kind != Kind.SERIES) Store.addRecent(this, item)
        if (reset) {
            epgText = ""
            if (isLive) loadEpg(item)
        }
        showInfo(label(), true)
    }

    private fun loadEpg(item: Item) {
        if (Store.mode != Store.XTREAM || item.epgId.isEmpty()) return
        val token = ++epgToken
        Thread {
            val text = try {
                Api.shortEpg(item.epgId)
            } catch (e: Exception) {
                ""
            }
            runOnUiThread {
                if (token == epgToken && text.isNotEmpty() && !isFinishing) {
                    epgText = text
                    showInfo(label(), true)
                }
            }
        }.start()
    }

    private fun savePosition() {
        if (isLive) return
        val p = player ?: return
        val item = current ?: return
        val dur = p.duration
        val pos = p.currentPosition
        // Se faltar menos de 1 minuto, considera assistido.
        val keep = if (dur > 0 && pos > dur - 60000) 0L else pos
        Store.savePosition(this, item, keep)
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }

    private fun label(): String {
        val item = current ?: return ""
        if (!isLive) return item.label
        val base = "${Store.index + 1}/${Store.playlist.size}  ${item.label}"
        return if (epgText.isNotEmpty()) "$base\n$epgText" else base
    }

    private fun showInfo(text: String, autoHide: Boolean) {
        tvInfo.text = text
        tvInfo.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        if (autoHide) handler.postDelayed(hideInfo, 5000)
    }

    private fun change(delta: Int) {
        val n = Store.playlist.size
        if (n == 0) return
        Store.index = ((Store.index + delta) % n + n) % n
        playCurrent(true)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Filmes/séries: o controle remoto comanda a barra do player.
        if (!isLive && ::playerView.isInitialized && event.keyCode != KeyEvent.KEYCODE_BACK) {
            if (playerView.dispatchKeyEvent(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isLive) return super.onKeyDown(keyCode, event)
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
            else -> showInfo(label(), true)
        }
        return true
    }
}

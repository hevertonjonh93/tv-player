package com.meuiptv.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : BaseActivity() {

    private lateinit var tvClock: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val tick = object : Runnable {
        override fun run() {
            tvClock.text = clockFmt.format(Date())
            handler.postDelayed(this, 15000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Store.load(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_home)
        tvClock = findViewById(R.id.tvClock)
        findViewById<TextView>(R.id.tvAccount).text =
            Store.accountInfo.ifBlank { if (Store.mode == Store.M3U) "Lista M3U" else Store.user }

        val live = findViewById<TextView>(R.id.cardLive)
        live.setOnClickListener { open(Kind.LIVE) }
        findViewById<TextView>(R.id.cardMovies).setOnClickListener { open(Kind.MOVIE) }
        findViewById<TextView>(R.id.cardSeries).setOnClickListener { open(Kind.SERIES) }

        findViewById<Button>(R.id.btnParental).setOnClickListener {
            startActivity(Intent(this, ParentalActivity::class.java))
        }
        findViewById<Button>(R.id.btnReload).setOnClickListener {
            Store.resetContent()
            Toast.makeText(this, "As listas serão recarregadas ao abrir cada seção.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            Store.clear(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        live.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (::tvClock.isInitialized) handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun open(kind: Kind) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_KIND, kind.name))
    }
}

package com.meuiptv.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView

class LoginActivity : BaseActivity() {

    private lateinit var etServer: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etM3u: EditText
    private lateinit var btnXtream: Button
    private lateinit var btnM3u: Button
    private lateinit var groupXtream: View
    private lateinit var groupM3u: View
    private lateinit var btnLogin: Button
    private lateinit var tvError: TextView
    private lateinit var progress: ProgressBar
    private var useM3u = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Store.load(this)) {
            openHome()
            return
        }

        setContentView(R.layout.activity_login)
        etServer = findViewById(R.id.etServer)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        etM3u = findViewById(R.id.etM3u)
        btnXtream = findViewById(R.id.btnModeXtream)
        btnM3u = findViewById(R.id.btnModeM3u)
        groupXtream = findViewById(R.id.groupXtream)
        groupM3u = findViewById(R.id.groupM3u)
        btnLogin = findViewById(R.id.btnLogin)
        tvError = findViewById(R.id.tvError)
        progress = findViewById(R.id.progress)

        fitBoxToScreen()

        btnXtream.setOnClickListener { setMode(false) }
        btnM3u.setOnClickListener { setMode(true) }
        btnLogin.setOnClickListener { attempt() }
        etPass.setOnEditorActionListener { _, _, _ -> attempt(); true }
        etM3u.setOnEditorActionListener { _, _, _ -> attempt(); true }

        setMode(false)
        btnXtream.requestFocus()
    }

    /** No celular a caixa de 480dp não cabe; limita à largura da tela. */
    private fun fitBoxToScreen() {
        val box = findViewById<View>(R.id.loginBox)
        val d = resources.displayMetrics.density
        val avail = resources.displayMetrics.widthPixels - (48 * d).toInt()
        val lp = box.layoutParams
        lp.width = minOf((480 * d).toInt(), avail)
        box.layoutParams = lp
    }

    private fun setMode(m3u: Boolean) {
        useM3u = m3u
        btnXtream.isSelected = !m3u
        btnM3u.isSelected = m3u
        groupXtream.visibility = if (m3u) View.GONE else View.VISIBLE
        groupM3u.visibility = if (m3u) View.VISIBLE else View.GONE
        tvError.visibility = View.GONE
    }

    private fun attempt() {
        if (!btnLogin.isEnabled) return
        if (useM3u) attemptM3u() else attemptXtream()
    }

    private fun attemptXtream() {
        val server = etServer.text.toString().trim()
        val user = etUser.text.toString().trim()
        val pass = etPass.text.toString()

        if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            showError("Preencha servidor, usuário e senha.")
            return
        }

        setBusy(true)
        Thread {
            try {
                val base = Api.normalizeBase(server)
                val info = Api.login(base, user, pass)
                runOnUiThread { finishXtream(base, user, pass, info) }
            } catch (e: Throwable) {
                runOnUiThread {
                    setBusy(false)
                    showError(Api.friendly(e))
                }
            }
        }.start()
    }

    private fun attemptM3u() {
        val raw = etM3u.text.toString().trim()
        if (raw.isEmpty()) {
            showError("Cole o link da lista M3U / M3U8.")
            return
        }
        val url = Api.normalizeUrl(raw)

        setBusy(true)
        Thread {
            try {
                // Links get.php do Xtream funcionam melhor pela API (categorias, séries, EPG).
                val xt = Api.xtreamFromUrl(url)
                if (xt != null) {
                    val info = try {
                        Api.login(xt.first, xt.second, xt.third)
                    } catch (e: Exception) {
                        null
                    }
                    if (info != null) {
                        runOnUiThread { finishXtream(xt.first, xt.second, xt.third, info) }
                        return@Thread
                    }
                }
                val data = Api.loadM3u(url)
                runOnUiThread {
                    Store.mode = Store.M3U
                    Store.m3uUrl = url
                    Store.base = ""
                    Store.user = ""
                    Store.pass = ""
                    Store.accountInfo = "Lista M3U"
                    Store.applyM3u(data)
                    Store.save(this)
                    openHome()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    setBusy(false)
                    showError(Api.friendly(e))
                }
            }
        }.start()
    }

    private fun finishXtream(base: String, user: String, pass: String, info: String) {
        Store.mode = Store.XTREAM
        Store.base = base
        Store.user = user
        Store.pass = pass
        Store.m3uUrl = ""
        Store.accountInfo = info
        Store.resetContent()
        Store.save(this)
        openHome()
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !busy
        if (busy) tvError.visibility = View.GONE
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }

    private fun openHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}

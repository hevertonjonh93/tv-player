package com.meuiptv.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var etServer: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvError: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Store.load(this)) {
            openMain()
            return
        }

        setContentView(R.layout.activity_login)
        etServer = findViewById(R.id.etServer)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        btnLogin = findViewById(R.id.btnLogin)
        tvError = findViewById(R.id.tvError)
        progress = findViewById(R.id.progress)

        btnLogin.setOnClickListener { attempt() }
        etPass.setOnEditorActionListener { _, _, _ ->
            attempt()
            true
        }
    }

    private fun attempt() {
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
                Api.login(base, user, pass)
                runOnUiThread {
                    Store.base = base
                    Store.user = user
                    Store.pass = pass
                    Store.categories = emptyList()
                    Store.channels = emptyList()
                    Store.save(this)
                    openMain()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    showError(Api.friendly(e))
                }
            }
        }.start()
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

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

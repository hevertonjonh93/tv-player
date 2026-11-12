package com.meuiptv.player

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ParentalActivity : BaseActivity() {

    private lateinit var groupCreate: View
    private lateinit var groupManage: View
    private lateinit var tvCreateTitle: TextView
    private lateinit var etPin1: EditText
    private lateinit var etPin2: EditText
    private lateinit var tvPinError: TextView
    private lateinit var rv: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var tvMessage: TextView
    private lateinit var tabs: Map<Kind, Button>
    private lateinit var adapter: RowAdapter<Category>

    private var kind = Kind.LIVE
    private var locked: MutableSet<String> = HashSet()
    private var loadToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Store.load(this)) {
            finish()
            return
        }
        setContentView(R.layout.activity_parental)
        groupCreate = findViewById(R.id.groupCreate)
        groupManage = findViewById(R.id.groupManage)
        tvCreateTitle = findViewById(R.id.tvCreateTitle)
        etPin1 = findViewById(R.id.etPin1)
        etPin2 = findViewById(R.id.etPin2)
        tvPinError = findViewById(R.id.tvPinError)
        rv = findViewById(R.id.rvLockCats)
        progress = findViewById(R.id.lockProgress)
        tvMessage = findViewById(R.id.tvLockMessage)
        tabs = mapOf(
            Kind.LIVE to findViewById<Button>(R.id.tabLive),
            Kind.MOVIE to findViewById<Button>(R.id.tabMovies),
            Kind.SERIES to findViewById<Button>(R.id.tabSeries)
        )

        // Caixa de senha com no máximo 480dp (cabe no celular e fica bonita na TV).
        val box = findViewById<View>(R.id.createBox)
        val d = resources.displayMetrics.density
        val lp = box.layoutParams
        lp.width = minOf((480 * d).toInt(), resources.displayMetrics.widthPixels - (48 * d).toInt())
        box.layoutParams = lp

        adapter = RowAdapter<Category>({ (if (locked.contains(it.id)) "🔒  " else "🔓  ") + it.name }) { c, _ ->
            if (!locked.remove(c.id)) locked.add(c.id)
            Parental.setLocked(this, kind, locked)
            adapter.refresh()
        }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        for ((k, b) in tabs) b.setOnClickListener { selectKind(k) }
        findViewById<Button>(R.id.btnSavePin).setOnClickListener { savePin() }
        etPin2.setOnEditorActionListener { _, _, _ -> savePin(); true }
        findViewById<Button>(R.id.btnChangePin).setOnClickListener { showCreate(true) }
        findViewById<Button>(R.id.btnDisable).setOnClickListener { confirmDisable() }

        if (Parental.hasPin(this)) {
            groupManage.visibility = View.INVISIBLE
            Parental.askPin(this, "Digite a senha do controle parental",
                onOk = { showManage() },
                onCancel = { finish() })
        } else {
            showCreate(false)
        }
    }

    private fun showCreate(changing: Boolean) {
        groupManage.visibility = View.GONE
        groupCreate.visibility = View.VISIBLE
        tvPinError.visibility = View.GONE
        etPin1.setText("")
        etPin2.setText("")
        tvCreateTitle.text = if (changing) "Digite a nova senha." else "Crie uma senha numérica para bloquear pastas."
        etPin1.requestFocus()
    }

    private fun savePin() {
        val p1 = etPin1.text.toString()
        val p2 = etPin2.text.toString()
        val err = when {
            p1.length < 4 -> "A senha precisa ter pelo menos 4 números."
            p1 != p2 -> "As senhas não são iguais."
            else -> null
        }
        if (err != null) {
            tvPinError.text = err
            tvPinError.visibility = View.VISIBLE
            return
        }
        Parental.setPin(this, p1)
        Toast.makeText(this, "Senha salva", Toast.LENGTH_SHORT).show()
        showManage()
    }

    private fun showManage() {
        groupCreate.visibility = View.GONE
        groupManage.visibility = View.VISIBLE
        selectKind(kind)
        tabs[kind]?.requestFocus()
    }

    private fun selectKind(k: Kind) {
        kind = k
        for ((kk, b) in tabs) b.isSelected = kk == k
        locked = HashSet(Parental.locked(this, k))
        adapter.submit(emptyList())
        tvMessage.visibility = View.GONE

        if (Store.items[k] != null) {
            showCats()
            return
        }
        val token = ++loadToken
        progress.visibility = View.VISIBLE
        Thread {
            try {
                Api.ensureLoaded(k)
                runOnUiThread {
                    if (token != loadToken) return@runOnUiThread
                    progress.visibility = View.GONE
                    showCats()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    if (token != loadToken) return@runOnUiThread
                    progress.visibility = View.GONE
                    tvMessage.text = "Não foi possível carregar as pastas.\n" + Api.friendly(e)
                    tvMessage.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun showCats() {
        val cats = Store.categories[kind] ?: emptyList()
        adapter.submit(cats)
        if (cats.isEmpty()) {
            tvMessage.text = "Nenhuma pasta nesta seção."
            tvMessage.visibility = View.VISIBLE
        }
    }

    private fun confirmDisable() {
        AlertDialog.Builder(this)
            .setTitle("Desativar controle parental?")
            .setMessage("A senha e todos os bloqueios serão apagados.")
            .setPositiveButton("Desativar") { _, _ ->
                Parental.disable(this)
                Toast.makeText(this, "Controle parental desativado", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}

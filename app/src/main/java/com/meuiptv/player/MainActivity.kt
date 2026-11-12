package com.meuiptv.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var tvMessage: TextView
    private lateinit var tvChannelsTitle: TextView
    private lateinit var catAdapter: RowAdapter<Category>
    private lateinit var chAdapter: RowAdapter<Channel>
    private var shown: List<Channel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Store.load(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        rvCategories = findViewById(R.id.rvCategories)
        rvChannels = findViewById(R.id.rvChannels)
        progress = findViewById(R.id.progress)
        tvMessage = findViewById(R.id.tvMessage)
        tvChannelsTitle = findViewById(R.id.tvChannelsTitle)

        catAdapter = RowAdapter<Category>({ it.name }) { c, pos ->
            catAdapter.select(pos)
            showCategory(c)
        }
        chAdapter = RowAdapter<Channel>({ it.name.ifBlank { "Canal ${it.id}" } }) { _, pos ->
            Store.playlist = shown
            Store.index = pos
            startActivity(Intent(this, PlayerActivity::class.java))
        }

        rvCategories.layoutManager = LinearLayoutManager(this)
        rvChannels.layoutManager = LinearLayoutManager(this)
        rvCategories.adapter = catAdapter
        rvChannels.adapter = chAdapter

        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            Store.clear(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        load()
    }

    private fun load() {
        if (Store.channels.isNotEmpty()) {
            populate()
            return
        }
        setLoading(true)
        Thread {
            try {
                val cats = Api.categories()
                val chs = Api.channels()
                runOnUiThread {
                    Store.categories = cats
                    Store.channels = chs
                    setLoading(false)
                    populate()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setLoading(false)
                    showMessage("Não foi possível carregar os canais.\n" + Api.friendly(e))
                }
            }
        }.start()
    }

    private fun populate() {
        val cats = ArrayList<Category>()
        cats.add(Category("", "Todos os canais"))
        cats.addAll(Store.categories)
        catAdapter.submit(cats)
        catAdapter.select(0)
        showCategory(cats[0])
        if (Store.channels.isEmpty()) showMessage("Nenhum canal encontrado nesta conta.")
        rvCategories.post {
            rvCategories.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun showCategory(c: Category) {
        shown = if (c.id.isEmpty()) Store.channels else Store.channels.filter { it.categoryId == c.id }
        chAdapter.submit(shown)
        tvChannelsTitle.text = "${c.name} (${shown.size})"
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        tvMessage.visibility = View.GONE
    }

    private fun showMessage(msg: String) {
        tvMessage.text = msg
        tvMessage.visibility = View.VISIBLE
    }
}

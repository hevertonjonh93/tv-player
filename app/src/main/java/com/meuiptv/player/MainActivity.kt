package com.meuiptv.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/** Tela de conteúdo de uma seção (Canais, Filmes ou Séries). */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KIND = "kind"
        private const val CAT_FAV = "__fav"
        private const val CAT_RECENT = "__recent"
        private const val CAT_ALL = ""
    }

    private lateinit var kind: Kind
    private lateinit var rvCategories: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var tvMessage: TextView
    private lateinit var tvChannelsTitle: TextView
    private lateinit var etSearch: EditText
    private lateinit var catAdapter: RowAdapter<Category>
    private lateinit var chAdapter: RowAdapter<Item>

    private var shown: List<Item> = emptyList()
    private var currentCat: Category? = null
    private var openSeries: Item? = null
    private var favSet: Set<String> = emptySet()
    private var loaded = false

    private val handler = Handler(Looper.getMainLooper())
    private val doSearch = Runnable { applySearch() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Store.load(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        kind = try {
            Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: Kind.LIVE.name)
        } catch (e: Exception) {
            Kind.LIVE
        }

        setContentView(R.layout.activity_main)
        rvCategories = findViewById(R.id.rvCategories)
        rvChannels = findViewById(R.id.rvChannels)
        progress = findViewById(R.id.progress)
        tvMessage = findViewById(R.id.tvMessage)
        tvChannelsTitle = findViewById(R.id.tvChannelsTitle)
        etSearch = findViewById(R.id.etSearch)
        findViewById<TextView>(R.id.tvSection).text = kind.title

        catAdapter = RowAdapter<Category>({ it.name }) { c, pos ->
            catAdapter.select(pos)
            if (etSearch.text.isNotEmpty()) etSearch.setText("")
            showCategory(c)
        }
        chAdapter = RowAdapter<Item>(
            label = { itemLabel(it) },
            onLongClick = { item, _ -> toggleFavorite(item) }
        ) { item, pos -> onItemClick(item, pos) }

        rvCategories.layoutManager = LinearLayoutManager(this)
        rvChannels.layoutManager = LinearLayoutManager(this)
        rvCategories.adapter = catAdapter
        rvChannels.adapter = chAdapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(doSearch)
                handler.postDelayed(doSearch, 350)
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    openSeries != null -> closeSeries()
                    etSearch.text.isNotEmpty() -> etSearch.setText("")
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        load()
    }

    override fun onResume() {
        super.onResume()
        if (!loaded) return
        // Atualiza favoritos/recentes ao voltar do player.
        favSet = Store.favorites(this)
        if (openSeries == null && etSearch.text.isEmpty() && currentCat?.id == CAT_RECENT) {
            currentCat?.let { showCategory(it) }
        } else {
            chAdapter.refresh()
        }
    }

    private fun load() {
        if (Store.items[kind] != null) {
            populate()
            return
        }
        setLoading(true)
        Thread {
            try {
                Api.ensureLoaded(kind)
                runOnUiThread {
                    setLoading(false)
                    populate()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    setLoading(false)
                    showMessage("Não foi possível carregar.\n" + Api.friendly(e))
                }
            }
        }.start()
    }

    private fun all(): List<Item> = Store.items[kind] ?: emptyList()

    private fun populate() {
        loaded = true
        favSet = Store.favorites(this)
        val allName = when (kind) {
            Kind.LIVE -> "Todos os canais"
            Kind.MOVIE -> "Todos os filmes"
            Kind.SERIES -> "Todas as séries"
        }
        val cats = ArrayList<Category>()
        cats.add(Category(CAT_FAV, "★ Favoritos"))
        cats.add(Category(CAT_RECENT, "🕘 Vistos recentemente"))
        cats.add(Category(CAT_ALL, allName))
        cats.addAll(Store.categories[kind] ?: emptyList())
        catAdapter.submit(cats)
        catAdapter.select(2)
        showCategory(cats[2])
        if (all().isEmpty()) showMessage("Nenhum conteúdo encontrado nesta seção.")
        rvCategories.post {
            rvCategories.findViewHolderForAdapterPosition(2)?.itemView?.requestFocus()
        }
    }

    private fun showCategory(c: Category) {
        currentCat = c
        openSeries = null
        val list = all()
        shown = when (c.id) {
            CAT_ALL -> list
            CAT_FAV -> list.filter { favSet.contains(it.key) }
            CAT_RECENT -> {
                val byKey = list.associateBy { it.key }
                Store.recents(this, kind).mapNotNull { byKey[it] }
            }
            else -> list.filter { it.categoryId == c.id }
        }
        show(shown, "${c.name} (${shown.size})")
        if (shown.isEmpty() && list.isNotEmpty()) {
            showMessage(
                when (c.id) {
                    CAT_FAV -> "Nenhum favorito ainda.\nSegure OK em um item para favoritar."
                    CAT_RECENT -> "Nada assistido ainda."
                    else -> "Categoria vazia."
                }
            )
        }
    }

    private fun applySearch() {
        if (!loaded) return
        val q = etSearch.text.toString().trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) {
            currentCat?.let { showCategory(it) }
            return
        }
        openSeries = null
        shown = all().filter { it.name.lowercase(Locale.getDefault()).contains(q) }
        show(shown, "Busca: \"$q\" (${shown.size})")
        if (shown.isEmpty()) showMessage("Nada encontrado.")
    }

    private fun show(list: List<Item>, title: String) {
        tvMessage.visibility = View.GONE
        chAdapter.submit(list)
        tvChannelsTitle.text = title
        rvChannels.scrollToPosition(0)
    }

    private fun itemLabel(it: Item): String {
        val star = if (favSet.contains(it.key)) "★ " else ""
        return star + it.label
    }

    private fun onItemClick(item: Item, pos: Int) {
        if (item.isSeries) {
            openSeriesEpisodes(item)
            return
        }
        Store.playlist = shown
        Store.index = pos
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun openSeriesEpisodes(series: Item) {
        Store.addRecent(this, series)
        setLoading(true)
        Thread {
            try {
                val eps = Api.episodes(series)
                runOnUiThread {
                    setLoading(false)
                    openSeries = series
                    shown = eps
                    show(eps, "${series.label} · ${eps.size} episódios  (Voltar para sair)")
                    if (eps.isEmpty()) showMessage("Nenhum episódio encontrado.")
                    rvChannels.post {
                        rvChannels.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    setLoading(false)
                    Toast.makeText(this, Api.friendly(e), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun closeSeries() {
        val series = openSeries
        openSeries = null
        if (etSearch.text.isNotEmpty()) applySearch() else currentCat?.let { showCategory(it) }
        val idx = if (series != null) shown.indexOfFirst { it.key == series.key } else -1
        if (idx >= 0) {
            rvChannels.scrollToPosition(idx)
            rvChannels.post {
                rvChannels.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus()
            }
        }
    }

    private fun toggleFavorite(item: Item) {
        // Episódios não entram nos favoritos; favorite a série.
        if (openSeries != null) {
            Toast.makeText(this, "Volte à lista para favoritar a série.", Toast.LENGTH_SHORT).show()
            return
        }
        val added = Store.toggleFavorite(this, item)
        favSet = Store.favorites(this)
        Toast.makeText(
            this,
            if (added) "Adicionado aos favoritos" else "Removido dos favoritos",
            Toast.LENGTH_SHORT
        ).show()
        if (currentCat?.id == CAT_FAV && etSearch.text.isEmpty()) {
            currentCat?.let { showCategory(it) }
        } else {
            chAdapter.refresh()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Botão MENU do controle também favorita o item em foco.
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val v = rvChannels.focusedChild
            if (v != null) {
                val pos = rvChannels.getChildAdapterPosition(v)
                chAdapter.itemAt(pos)?.let { toggleFavorite(it) }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(doSearch)
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

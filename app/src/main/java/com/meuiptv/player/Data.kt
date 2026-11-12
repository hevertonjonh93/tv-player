package com.meuiptv.player

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Kind(val title: String) {
    LIVE("Canais ao vivo"),
    MOVIE("Filmes"),
    SERIES("Séries")
}

data class Category(val id: String, val name: String)

/**
 * Um item da lista: canal, filme, série (container que abre episódios) ou episódio.
 * [url] é o endereço de reprodução; [fallback] é tentado se o primeiro falhar.
 */
data class Item(
    val id: String,
    val name: String,
    val categoryId: String,
    val kind: Kind,
    val url: String = "",
    val fallback: String? = null,
    val isSeries: Boolean = false,
    val epgId: String = ""
) {
    val key: String get() = "${kind.name}:$id"
    val label: String get() = name.ifBlank { "Sem nome" }
}

class M3uData(
    val categories: Map<Kind, List<Category>>,
    val items: Map<Kind, List<Item>>,
    val episodes: Map<String, List<Item>>
)

object Store {
    const val XTREAM = "xtream"
    const val M3U = "m3u"

    var mode = XTREAM
    var base = ""
    var user = ""
    var pass = ""
    var m3uUrl = ""
    var accountInfo = ""

    val categories = HashMap<Kind, List<Category>>()
    val items = HashMap<Kind, List<Item>>()
    val m3uEpisodes = HashMap<String, List<Item>>()

    var playlist: List<Item> = emptyList()
    var index = 0

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE)

    // Favoritos, recentes e posições ficam separados para não sumirem ao sair da conta.
    private fun userPrefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("iptv_user", Context.MODE_PRIVATE)

    fun load(ctx: Context): Boolean {
        val p = prefs(ctx)
        mode = p.getString("mode", XTREAM) ?: XTREAM
        base = p.getString("base", "") ?: ""
        user = p.getString("user", "") ?: ""
        pass = p.getString("pass", "") ?: ""
        m3uUrl = p.getString("m3u", "") ?: ""
        accountInfo = p.getString("info", "") ?: ""
        return if (mode == M3U) m3uUrl.isNotEmpty()
        else base.isNotEmpty() && user.isNotEmpty() && pass.isNotEmpty()
    }

    fun save(ctx: Context) {
        prefs(ctx).edit()
            .putString("mode", mode)
            .putString("base", base)
            .putString("user", user)
            .putString("pass", pass)
            .putString("m3u", m3uUrl)
            .putString("info", accountInfo)
            .apply()
    }

    fun resetContent() {
        categories.clear()
        items.clear()
        m3uEpisodes.clear()
        playlist = emptyList()
        index = 0
    }

    fun applyM3u(d: M3uData) {
        resetContent()
        categories.putAll(d.categories)
        items.putAll(d.items)
        m3uEpisodes.putAll(d.episodes)
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        mode = XTREAM
        base = ""
        user = ""
        pass = ""
        m3uUrl = ""
        accountInfo = ""
        resetContent()
    }

    fun scope(): String = if (mode == M3U) "m3u:$m3uUrl" else "xt:$base|$user"

    // ---------- Favoritos ----------
    fun favorites(ctx: Context): Set<String> =
        userPrefs(ctx).getStringSet("fav|" + scope(), emptySet()) ?: emptySet()

    /** Retorna true se foi adicionado, false se foi removido. */
    fun toggleFavorite(ctx: Context, item: Item): Boolean {
        val s = HashSet(favorites(ctx))
        val added = if (s.contains(item.key)) {
            s.remove(item.key); false
        } else {
            s.add(item.key); true
        }
        userPrefs(ctx).edit().putStringSet("fav|" + scope(), s).apply()
        return added
    }

    // ---------- Recentes ----------
    fun recents(ctx: Context, kind: Kind): List<String> =
        (userPrefs(ctx).getString("rec|${kind.name}|" + scope(), "") ?: "")
            .split('\n').filter { it.isNotEmpty() }

    fun addRecent(ctx: Context, item: Item) {
        val list = recents(ctx, item.kind).toMutableList()
        list.remove(item.key)
        list.add(0, item.key)
        while (list.size > 30) list.removeAt(list.size - 1)
        userPrefs(ctx).edit().putString("rec|${item.kind.name}|" + scope(), list.joinToString("\n")).apply()
    }

    // ---------- Continuar assistindo ----------
    fun position(ctx: Context, item: Item): Long =
        userPrefs(ctx).getLong("pos|${item.key}|" + scope(), 0L)

    fun savePosition(ctx: Context, item: Item, ms: Long) {
        val e = userPrefs(ctx).edit()
        val k = "pos|${item.key}|" + scope()
        if (ms > 0) e.putLong(k, ms) else e.remove(k)
        e.apply()
    }
}

object Api {
    private const val UA = "Mozilla/5.0 (Linux; Android 11; TV) IPTVPlayer/1.0"

    fun normalizeBase(input: String): String {
        var s = input.trim()
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
            s = "http://$s"
        }
        try {
            val u = URL(s)
            if (u.host.isNullOrEmpty()) throw IOException()
            val port = if (u.port != -1) ":${u.port}" else ""
            return "${u.protocol}://${u.host}$port"
        } catch (e: Exception) {
            throw IOException("Endereço do servidor inválido")
        }
    }

    fun normalizeUrl(input: String): String {
        val s = input.trim()
        return if (s.startsWith("http://", true) || s.startsWith("https://", true)) s else "http://$s"
    }

    /** Se for um link get.php do Xtream, devolve (servidor, usuário, senha). */
    fun xtreamFromUrl(url: String): Triple<String, String, String>? {
        return try {
            val uri = Uri.parse(url)
            if (uri.path?.endsWith("get.php") != true) return null
            val u = uri.getQueryParameter("username") ?: return null
            val p = uri.getQueryParameter("password") ?: return null
            if (u.isEmpty() || p.isEmpty()) return null
            Triple(normalizeBase(url), u, p)
        } catch (e: Exception) {
            null
        }
    }

    private fun q(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Abre a conexão seguindo redirecionamentos (inclusive http -> https). */
    private fun open(url: String): HttpURLConnection {
        var current = url
        repeat(6) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrEmpty()) throw IOException("Redirecionamento inválido do servidor")
                current = URL(URL(current), loc).toString()
                return@repeat
            }
            if (code !in 200..299) {
                c.disconnect()
                throw IOException("O servidor respondeu com erro $code")
            }
            return c
        }
        throw IOException("Muitos redirecionamentos")
    }

    private fun get(url: String): String {
        val c = open(url)
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun str(o: JSONObject, key: String): String {
        if (o.isNull(key)) return ""
        return o.optString(key, "")
    }

    private fun apiUrl(action: String): String =
        "${Store.base}/player_api.php?username=${q(Store.user)}&password=${q(Store.pass)}&action=$action"

    private fun path(kind: String, id: String, ext: String): String =
        "${Store.base}/$kind/${Uri.encode(Store.user)}/${Uri.encode(Store.pass)}/$id.$ext"

    // ---------------- Xtream ----------------

    /** Faz login e devolve um texto com as informações da conta. */
    fun login(base: String, user: String, pass: String): String {
        val url = "$base/player_api.php?username=${q(user)}&password=${q(pass)}"
        val json = JSONObject(get(url))
        val info = json.optJSONObject("user_info") ?: throw IOException("Usuário ou senha inválidos")
        if (info.optInt("auth", 0) != 1) throw IOException("Usuário ou senha inválidos")
        val status = str(info, "status")
        if (status.isNotEmpty() && !status.equals("Active", true)) {
            throw IOException("Conta com status: $status")
        }
        val exp = str(info, "exp_date").toLongOrNull()
        val expText = if (exp == null || exp <= 0) "Sem data de vencimento"
        else "Vence em " + SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(exp * 1000))
        val conns = str(info, "max_connections")
        return if (conns.isNotEmpty()) "$user · $expText · $conns tela(s)" else "$user · $expText"
    }

    private fun xtreamCategories(action: String): List<Category> {
        val arr = JSONArray(get(apiUrl(action)))
        val out = ArrayList<Category>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Category(str(o, "category_id"), str(o, "category_name").ifBlank { "Sem nome" }))
        }
        return out
    }

    private fun xtreamItems(kind: Kind): List<Item> {
        val action = when (kind) {
            Kind.LIVE -> "get_live_streams"
            Kind.MOVIE -> "get_vod_streams"
            Kind.SERIES -> "get_series"
        }
        val arr = JSONArray(get(apiUrl(action)))
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = str(o, "name")
            val cat = str(o, "category_id")
            when (kind) {
                Kind.LIVE -> {
                    val id = str(o, "stream_id")
                    if (id.isEmpty()) continue
                    out.add(Item(id, name, cat, kind,
                        url = path("live", id, "m3u8"),
                        fallback = path("live", id, "ts"),
                        epgId = id))
                }
                Kind.MOVIE -> {
                    val id = str(o, "stream_id")
                    if (id.isEmpty()) continue
                    val ext = str(o, "container_extension").ifBlank { "mp4" }
                    out.add(Item(id, name, cat, kind, url = path("movie", id, ext)))
                }
                Kind.SERIES -> {
                    val id = str(o, "series_id")
                    if (id.isEmpty()) continue
                    out.add(Item(id, name, cat, kind, isSeries = true))
                }
            }
        }
        return out
    }

    private fun xtreamEpisodes(series: Item): List<Item> {
        val json = JSONObject(get(apiUrl("get_series_info") + "&series_id=${q(series.id)}"))
        val out = ArrayList<Item>()
        val eps = json.opt("episodes")
        val seasons = ArrayList<JSONArray>()
        when (eps) {
            is JSONObject -> {
                val keys = eps.keys().asSequence().toList()
                    .sortedWith(compareBy<String>({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
                for (k in keys) eps.optJSONArray(k)?.let { seasons.add(it) }
            }
            is JSONArray -> for (i in 0 until eps.length()) eps.optJSONArray(i)?.let { seasons.add(it) }
        }
        for (arr in seasons) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = str(o, "id")
                if (id.isEmpty()) continue
                val season = str(o, "season")
                val num = str(o, "episode_num")
                val title = str(o, "title")
                val ext = str(o, "container_extension").ifBlank { "mp4" }
                val prefix = buildString {
                    if (season.isNotEmpty()) append("T$season ")
                    if (num.isNotEmpty()) append("E$num")
                }.trim()
                val name = if (prefix.isNotEmpty()) "$prefix - $title" else title
                out.add(Item("ep$id", name, series.id, Kind.SERIES, url = path("series", id, ext)))
            }
        }
        return out
    }

    /** Programação atual/próxima de um canal (só Xtream). */
    fun shortEpg(streamId: String): String {
        val json = JSONObject(get(apiUrl("get_short_epg") + "&stream_id=${q(streamId)}&limit=2"))
        val arr = json.optJSONArray("epg_listings") ?: return ""
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val lines = ArrayList<String>()
        for (i in 0 until minOf(arr.length(), 2)) {
            val o = arr.optJSONObject(i) ?: continue
            val title = decode(str(o, "title"))
            if (title.isBlank()) continue
            val start = str(o, "start_timestamp").toLongOrNull()
            val stop = str(o, "stop_timestamp").toLongOrNull()
            val time = if (start != null && stop != null)
                " (${fmt.format(Date(start * 1000))} - ${fmt.format(Date(stop * 1000))})" else ""
            lines.add((if (i == 0) "Agora: " else "Depois: ") + title + time)
        }
        return lines.joinToString("\n")
    }

    private fun decode(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
    } catch (e: Exception) {
        s
    }

    // ---------------- M3U ----------------

    private val attrRe = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")
    private val epRe = Regex("^(.*?)[\\s._-]*[Ss](\\d{1,2})[\\s._-]*[Ee](\\d{1,3})")
    private val videoExt = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm")

    private fun classify(name: String, group: String, url: String): Kind {
        val u = url.lowercase(Locale.ROOT).substringBefore('?')
        if (u.contains("/series/")) return Kind.SERIES
        if (u.contains("/movie/")) return Kind.MOVIE
        if (u.contains("/live/")) return Kind.LIVE
        val g = group.lowercase(Locale.ROOT)
        if (epRe.containsMatchIn(name)) return Kind.SERIES
        if (g.contains("serie") || g.contains("série")) return Kind.SERIES
        if (u.substringAfterLast('.', "") in videoExt) return Kind.MOVIE
        if (g.contains("filme") || g.contains("movie") || g.contains("vod")) return Kind.MOVIE
        return Kind.LIVE
    }

    fun loadM3u(url: String): M3uData {
        val cats = HashMap<Kind, LinkedHashSet<String>>()
        Kind.values().forEach { cats[it] = LinkedHashSet() }
        val live = ArrayList<Item>()
        val movies = ArrayList<Item>()
        val series = ArrayList<Item>()
        val episodes = LinkedHashMap<String, MutableList<Item>>()
        var hls = false
        var count = 0

        val c = open(url)
        try {
            c.inputStream.bufferedReader().use { r ->
                var name: String? = null
                var group = ""
                while (true) {
                    val raw = r.readLine() ?: break
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    when {
                        line.startsWith("#EXTINF", true) -> {
                            val attrs = HashMap<String, String>()
                            attrRe.findAll(line).forEach {
                                attrs[it.groupValues[1].lowercase(Locale.ROOT)] = it.groupValues[2]
                            }
                            val lastQuote = line.lastIndexOf('"')
                            val comma = line.indexOf(',', if (lastQuote >= 0) lastQuote else 0)
                            val title = if (comma >= 0) line.substring(comma + 1).trim() else ""
                            name = title.ifBlank { attrs["tvg-name"] ?: "" }
                            group = attrs["group-title"] ?: ""
                        }
                        line.startsWith("#EXTGRP:", true) -> {
                            if (group.isEmpty()) group = line.substring(8).trim()
                        }
                        line.startsWith("#EXT-X-", true) -> {
                            hls = true
                        }
                        line.startsWith("#") -> {
                        }
                        else -> {
                            val n = name ?: line.substringBefore('?').substringAfterLast('/')
                            val g = group.ifBlank { "Sem categoria" }
                            val kind = classify(n, g, line)
                            cats[kind]!!.add(g)
                            when (kind) {
                                Kind.LIVE -> live.add(Item(line, n, g, kind, url = line))
                                Kind.MOVIE -> movies.add(Item(line, n, g, kind, url = line))
                                Kind.SERIES -> {
                                    val m = epRe.find(n)
                                    val sName = m?.groupValues?.get(1)?.trim()?.trimEnd('-', '|', ':', '.')?.trim()
                                        .takeUnless { it.isNullOrEmpty() } ?: n
                                    val sid = "s:$g|$sName"
                                    val list = episodes.getOrPut(sid) {
                                        series.add(Item(sid, sName, g, kind, isSeries = true))
                                        ArrayList()
                                    }
                                    list.add(Item(line, n, g, kind, url = line))
                                }
                            }
                            count++
                            name = null
                            group = ""
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }

        if (hls) {
            // O link é um único stream HLS (.m3u8), não uma lista de canais.
            val ch = Item(url, "Canal", "Lista", Kind.LIVE, url = url)
            return M3uData(
                mapOf(Kind.LIVE to listOf(Category("Lista", "Lista")), Kind.MOVIE to emptyList(), Kind.SERIES to emptyList()),
                mapOf(Kind.LIVE to listOf(ch), Kind.MOVIE to emptyList(), Kind.SERIES to emptyList()),
                emptyMap()
            )
        }
        if (count == 0) throw IOException("A lista está vazia ou não é uma lista M3U válida.")

        val catMap = HashMap<Kind, List<Category>>()
        for ((k, set) in cats) catMap[k] = set.map { Category(it, it) }
        return M3uData(
            catMap,
            mapOf(Kind.LIVE to live, Kind.MOVIE to movies, Kind.SERIES to series),
            episodes
        )
    }

    // ---------------- Comum ----------------

    /** Garante que o conteúdo da seção está carregado (roda em segundo plano). */
    @Synchronized
    fun ensureLoaded(kind: Kind) {
        if (Store.items[kind] != null) return
        if (Store.mode == Store.M3U) {
            Store.applyM3u(loadM3u(Store.m3uUrl))
        } else {
            val catAction = when (kind) {
                Kind.LIVE -> "get_live_categories"
                Kind.MOVIE -> "get_vod_categories"
                Kind.SERIES -> "get_series_categories"
            }
            val cats = xtreamCategories(catAction)
            val list = xtreamItems(kind)
            Store.categories[kind] = cats
            Store.items[kind] = list
        }
    }

    fun episodes(series: Item): List<Item> =
        if (Store.mode == Store.M3U) Store.m3uEpisodes[series.id] ?: emptyList()
        else xtreamEpisodes(series)

    fun friendly(e: Throwable): String = when (e) {
        is OutOfMemoryError -> "A lista é grande demais para este aparelho."
        is UnknownHostException -> "Servidor não encontrado. Confira o endereço (DNS) e a conexão com a internet."
        is SocketTimeoutException -> "Tempo esgotado ao conectar no servidor."
        is ConnectException -> "Não foi possível conectar ao servidor. Confira o endereço e a porta."
        is JSONException -> "Resposta inválida do servidor. Confira se o endereço está correto."
        is IOException -> e.message ?: "Erro de conexão."
        else -> "Erro inesperado: ${e.message}"
    }
}

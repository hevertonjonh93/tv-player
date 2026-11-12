package com.meuiptv.player

import android.content.Context
import android.net.Uri
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

data class Category(val id: String, val name: String)
data class Channel(val id: String, val name: String, val categoryId: String)

object Store {
    var base = ""
    var user = ""
    var pass = ""
    var categories: List<Category> = emptyList()
    var channels: List<Channel> = emptyList()
    var playlist: List<Channel> = emptyList()
    var index = 0

    fun load(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE)
        base = p.getString("base", "") ?: ""
        user = p.getString("user", "") ?: ""
        pass = p.getString("pass", "") ?: ""
        return base.isNotEmpty() && user.isNotEmpty() && pass.isNotEmpty()
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE).edit()
            .putString("base", base)
            .putString("user", user)
            .putString("pass", pass)
            .apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE).edit().clear().apply()
        base = ""
        user = ""
        pass = ""
        categories = emptyList()
        channels = emptyList()
        playlist = emptyList()
        index = 0
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

    private fun q(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.setRequestProperty("User-Agent", UA)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("O servidor respondeu com erro $code")
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

    fun login(base: String, user: String, pass: String) {
        val url = "$base/player_api.php?username=${q(user)}&password=${q(pass)}"
        val json = JSONObject(get(url))
        val info = json.optJSONObject("user_info") ?: throw IOException("Usuário ou senha inválidos")
        if (info.optInt("auth", 0) != 1) throw IOException("Usuário ou senha inválidos")
        val status = str(info, "status")
        if (status.isNotEmpty() && !status.equals("Active", true)) {
            throw IOException("Conta com status: $status")
        }
    }

    fun categories(): List<Category> {
        val arr = JSONArray(get(apiUrl("get_live_categories")))
        val out = ArrayList<Category>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Category(str(o, "category_id"), str(o, "category_name").ifBlank { "Sem nome" }))
        }
        return out
    }

    fun channels(): List<Channel> {
        val arr = JSONArray(get(apiUrl("get_live_streams")))
        val out = ArrayList<Channel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = str(o, "stream_id")
            if (id.isEmpty()) continue
            out.add(Channel(id, str(o, "name"), str(o, "category_id")))
        }
        return out
    }

    fun streamUrl(ch: Channel, ext: String): String =
        "${Store.base}/live/${Uri.encode(Store.user)}/${Uri.encode(Store.pass)}/${ch.id}.$ext"

    fun friendly(e: Exception): String = when (e) {
        is UnknownHostException -> "Servidor não encontrado. Confira o endereço (DNS) e a conexão com a internet."
        is SocketTimeoutException -> "Tempo esgotado ao conectar no servidor."
        is ConnectException -> "Não foi possível conectar ao servidor. Confira o endereço e a porta."
        is JSONException -> "Resposta inválida do servidor. Confira se o endereço está correto."
        is IOException -> e.message ?: "Erro de conexão."
        else -> "Erro inesperado: ${e.message}"
    }
}

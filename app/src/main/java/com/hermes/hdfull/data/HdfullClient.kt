package com.hermes.hdfull.data

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

object HdfullClient {

    val HOSTS = listOf(
        "https://hdfull.love/",
        "https://hdfull.today/",
        "https://hdfull.sbs/",
        "https://www3.hdfull.one/",
        "https://hdfull.org/"
    )
    private const val HOST_THUMB = "https://hdfullcdn.cc/"
    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /** In-memory cookie jar: la sesión del login nativo vive aquí. */
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = cookieStore.getOrPut(url.host) { mutableListOf() }
            for (c in cookies) {
                list.removeAll { it.name == c.name }
                list.add(c)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookieStore[url.host]?.filter { it.matches(url) } ?: emptyList()
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Copia las cookies del WebView (fallback Cloudflare) al jar de OkHttp. */
    fun importWebViewCookies(url: String) {
        try {
            val raw = CookieManager.getInstance().getCookie(url) ?: return
            val httpUrl = url.toHttpUrl()
            val cookies = raw.split(";").mapNotNull { part ->
                val kv = part.trim().split("=", limit = 2)
                if (kv.size != 2 || kv[0].isBlank()) null
                else Cookie.Builder()
                    .name(kv[0].trim())
                    .value(kv[1].trim())
                    .domain(httpUrl.host)
                    .path("/")
                    .build()
            }
            if (cookies.isNotEmpty()) cookieJar.saveFromResponse(httpUrl, cookies)
        } catch (e: Exception) {
            // ignorar
        }
    }

    fun clearCookies() = cookieStore.clear()

    /** Exporta las cookies de la sesión nativa al WebView (pantallas internas). */
    fun exportCookiesToWebView(url: String) {
        try {
            val httpUrl = url.toHttpUrl()
            val cm = CookieManager.getInstance()
            val cookies = cookieStore[httpUrl.host] ?: return
            for (c in cookies) {
                cm.setCookie(url, "${c.name}=${c.value}")
            }
            cm.flush()
        } catch (e: Exception) {
            // ignorar
        }
    }

    private fun buildRequest(url: String, builder: Request.Builder.() -> Unit = {}): Request {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        b.builder()
        return b.build()
    }

    /** Detecta página de reto de Cloudflare. */
    fun isCloudflareChallenge(html: String): Boolean {
        val h = html.take(6000)
        return h.contains("challenge-platform", ignoreCase = true) ||
                h.contains("cf-challenge", ignoreCase = true) ||
                h.contains("Just a moment", ignoreCase = true) ||
                h.contains("__cf_bm", ignoreCase = true) ||
                h.contains("cf_clearance", ignoreCase = true) ||
                h.contains("Attention Required", ignoreCase = true)
    }

    fun isLoggedIn(html: String): Boolean =
        html.contains("id=\"header-signout\"") || html.contains("id='header-signout'")

    sealed interface LoginResult {
        data object Success : LoginResult
        data object CloudflareBlocked : LoginResult
        data class Error(val message: String) : LoginResult
    }

    /**
     * Login nativo por HTTP: GET login (sid) -> POST a/login -> verifica header-signout.
     * Si Cloudflare bloquea la petición, devuelve CloudflareBlocked para usar el WebView.
     */
    suspend fun login(host: String, username: String, password: String): LoginResult =
        withContext(Dispatchers.IO) {
            try {
                val loginUrl = host + "login"
                val loginPage: String
                try {
                    val resp = client.newCall(buildRequest(loginUrl)).execute()
                    loginPage = resp.use { it.body?.string() ?: "" }
                    if (!resp.isSuccessful && resp.code == 403) return@withContext LoginResult.CloudflareBlocked
                } catch (e: Exception) {
                    return@withContext LoginResult.Error("Sin conexión con $host")
                }
                if (isCloudflareChallenge(loginPage)) return@withContext LoginResult.CloudflareBlocked
                if (isLoggedIn(loginPage)) return@withContext LoginResult.Success

                val sid = extractSid(loginPage)
                    ?: return@withContext LoginResult.Error("No se pudo iniciar sesión (sid)")

                val form = FormBody.Builder()
                    .add("__csrf_magic", sid)
                    .add("username", username)
                    .add("password", password)
                    .add("action", "login")
                    .build()
                val req = Request.Builder()
                    .url(host + "a/login")
                    .header("User-Agent", UA)
                    .header("Referer", loginUrl)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .post(form)
                    .build()
                val resultPage: String
                try {
                    val resp = client.newCall(req).execute()
                    resultPage = resp.use { it.body?.string() ?: "" }
                    if (!resp.isSuccessful && resp.code == 403) return@withContext LoginResult.CloudflareBlocked
                } catch (e: Exception) {
                    return@withContext LoginResult.Error("Sin conexión con $host")
                }
                if (isCloudflareChallenge(resultPage)) return@withContext LoginResult.CloudflareBlocked
                // verifica la sesión con una petición a la portada
                val home = try {
                    client.newCall(buildRequest(host)).execute().use { it.body?.string() ?: "" }
                } catch (e: Exception) {
                    ""
                }
                if (isLoggedIn(resultPage) || isLoggedIn(home)) {
                    LoginResult.Success
                } else {
                    LoginResult.Error("Usuario o contraseña incorrectos")
                }
            } catch (e: Exception) {
                LoginResult.Error("Error: ${e.message ?: "desconocido"}")
            }
        }

    /** Comprueba la sesión actual (tras importar cookies del WebView). */
    suspend fun checkSession(host: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val html = client.newCall(buildRequest(host)).execute().use { it.body?.string() ?: "" }
            isLoggedIn(html)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getHtml(url: String): String = withContext(Dispatchers.IO) {
        val resp = client.newCall(buildRequest(url)).execute()
        resp.use { it.body?.string() ?: "" }
    }

    suspend fun postForm(url: String, params: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
            params.forEach { (k, v) -> form.add(k, v) }
            val req = buildRequest(url) {
                post(form.build())
                header("Referer", url)
                header("X-Requested-With", "XMLHttpRequest")
            }
            val resp = client.newCall(req).execute()
            resp.use { it.body?.string() ?: "" }
        }

    suspend fun findWorkingHost(): String = withContext(Dispatchers.IO) {
        for (h in HOSTS) {
            try {
                val req = Request.Builder().url(h).header("User-Agent", UA).head().build()
                client.newCall(req).execute().use { r ->
                    if (r.isSuccessful) return@withContext h
                }
            } catch (e: Exception) {
                // try next
            }
        }
        HOSTS.first()
    }

    fun extractSid(html: String): String? {
        val r1 = Regex("""<input[^>]*name=['"]__csrf_magic['"][^>]*value=["']([^"']+)["']""").find(html)
        if (r1 != null) return r1.groupValues[1]
        val r2 = Regex("""name=['"]__csrf_magic['"][^>]*value=['"]([^'"]+)['"]""").find(html)
        return r2?.groupValues?.get(1)
    }

    fun parseCatalog(html: String): List<MediaItem> {
        val doc = Jsoup.parse(html)
        val container = doc.selectFirst("div.container-flex.main-wrapper") ?: doc
        return container.select("div.span-6").mapNotNull { el ->
            try {
                val h5a = el.selectFirst("h5.left a")
                val img = el.selectFirst("a img")
                val title = (h5a?.attr("title")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("alt")?.takeIf { it.isNotBlank() }) ?: return@mapNotNull null
                val url = h5a?.attr("href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val imgSrc = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("src")?.takeIf { it.isNotBlank() } ?: ""
                val thumb = if (imgSrc.startsWith("http")) imgSrc else HOST_THUMB + imgSrc.trimStart('/')
                val lang = el.selectFirst("a")?.attr("data-langs") ?: ""
                val seen = el.selectFirst("div.seen-box")?.attr("data-seen") ?: ""
                val infoId = if (seen.isNotBlank()) seen else {
                    val onclick = el.selectFirst("span.rating-pod-actions a.logged-req")?.attr("onclick") ?: ""
                    Regex("""\d+,\s*(\d+),\s*\d+""").find(onclick)?.groupValues?.get(1) ?: ""
                }
                val mediatype =
                    if (url.contains("serie/") || url.contains("/tags-tv")) "tvshow" else "movie"
                MediaItem(url, title.trim(), thumb, lang, mediatype, infoId)
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun catalog(url: String): List<MediaItem> = parseCatalog(getHtml(url))

    suspend fun search(host: String, query: String): List<MediaItem> {
        // sid needed for the search POST; grab it from the home page
        val home = getHtml(host)
        val sid = extractSid(home) ?: ""
        val html = postForm(
            host + "buscar",
            mapOf("__csrf_magic" to sid, "menu" to "search", "query" to query)
        )
        return parseCatalog(html)
    }

    /** Resuelve una URL relativa contra el host activo. */
    fun resolveUrl(host: String, url: String): String {
        if (url.startsWith("http")) return url
        return host.trimEnd('/') + "/" + url.trimStart('/')
    }

    data class SeriesInfo(val seasons: List<Season>, val showId: String, val title: String)

    data class MovieInfo(
        val title: String,
        val poster: String,
        val synopsis: String,
        val year: String,
        val genre: String,
        val cast: String
    )

    suspend fun movieDetail(host: String, url: String): MovieInfo {
        val fullUrl = resolveUrl(host, url)
        val html = getHtml(fullUrl)
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title().substringBefore(" - ")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        // La sinopsis suele estar en el div de descripción principal
        var synopsis = doc.selectFirst("div.show-details div.desc p")?.text()?.trim() ?: ""
        if (synopsis.isBlank()) {
            synopsis = doc.selectFirst("div.ficha div.desc")?.text()?.trim() ?: ""
        }
        if (synopsis.isBlank()) {
            // Buscar el párrafo más largo dentro de show-details (suele ser la sinopsis)
            synopsis = doc.select("div.show-details p")
                .map { it.text().trim() }
                .filter { it.length > 80 && !it.contains("Elenco:", ignoreCase = true) }
                .maxByOrNull { it.length } ?: ""
        }
        var year = ""
        var genre = ""
        var cast = ""
        // Año/género/elenco en los <p> de la ficha
        doc.select("div.show-details p").forEach { p ->
            val t = p.text()
            when {
                t.contains("Año:", ignoreCase = true) && year.isBlank() ->
                    year = Regex("""Año:\s*(\d{4})""").find(t)?.groupValues?.get(1) ?: ""
                t.contains("nero:", ignoreCase = true) && genre.isBlank() ->
                    genre = t.substringAfter("nero:", "").trim().substringBefore("Director:").trim()
                t.contains("Elenco:", ignoreCase = true) && cast.isBlank() ->
                    cast = t.substringAfter("Elenco:", "").trim()
            }
        }
        if (year.isBlank()) {
            year = Regex("""Año:\s*(\d{4})""").find(html)?.groupValues?.get(1) ?: ""
        }
        if (genre.isBlank()) {
            genre = Regex("""Género:\s*([^<]+?)(?:Director:|Elenco:|<)""").find(html)?.groupValues?.get(1)?.trim() ?: ""
        }
        return MovieInfo(title.trim(), poster, synopsis, year, genre, cast)
    }

    suspend fun seriesDetail(host: String, url: String): SeriesInfo {
        val fullUrl = resolveUrl(host, url)
        val html = getHtml(fullUrl)
        val doc = Jsoup.parse(html)
        val seasons = doc.select("ul#season-list li").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val num = Regex("""(\d+)""").find(a.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            Season(num)
        }.distinctBy { it.number }.sortedBy { it.number }
        val showId = Regex("""var\s+sid\s*=\s*'(\d+)'""").find(html)?.groupValues?.get(1) ?: ""
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title()
        return SeriesInfo(seasons, showId, title)
    }

    suspend fun episodes(host: String, showId: String, season: Int): List<Episode> {
        val body = postForm(
            host + "a/episodes",
            mapOf("action" to "season", "start" to "0", "limit" to "0", "show" to showId, "season" to season.toString())
        )
        val arr = try {
            JSONArray(body)
        } catch (e: Exception) {
            // sometimes wrapped; try to find the array
            val s = body.indexOf('[')
            val e = body.lastIndexOf(']')
            if (s >= 0 && e > s) JSONArray(body.substring(s, e + 1)) else JSONArray()
        }
        val out = mutableListOf<Episode>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val se = o.optInt("season", season)
                val ep = o.optInt("episode", 0)
                val showTitle = o.optJSONObject("show")?.optJSONObject("title")
                val title = (showTitle?.optString("es")?.takeIf { it.isNotBlank() }
                    ?: showTitle?.optString("en") ?: "")
                val epTitleObj = o.optJSONObject("title")
                val epTitle = (epTitleObj?.optString("es")?.takeIf { it.isNotBlank() }
                    ?: epTitleObj?.optString("en") ?: "")
                val fullTitle = if (epTitle.isNotBlank()) "$title: $epTitle" else title
                val thumbPath = o.optString("thumbnail").takeIf { it.isNotBlank() }
                    ?: o.optString("thumb")
                val thumb = if (thumbPath.startsWith("http")) thumbPath else host + "thumbs/" + thumbPath.trimStart('/')
                val perma = o.optString("permalink").takeIf { it.isNotBlank() }
                    ?: o.optString("perma")
                if (perma.isBlank()) continue
                val url = host + "serie/" + perma.trim('/') +
                        "/temporada-$se/episodio-${ep.toString().padStart(2, '0')}"
                out.add(Episode(se, ep, fullTitle.ifBlank { "Episodio $ep" }, thumb, url, o.optString("languages"), showId))
            } catch (e: Exception) {
                // skip bad entries
            }
        }
        return out.sortedWith(compareBy({ it.season }, { it.episode }))
    }
}

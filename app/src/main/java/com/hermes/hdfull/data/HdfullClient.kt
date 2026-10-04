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

    /** CookieJar compartido: los resolutores lo usan para heredar sesión y Cloudflare. */
    val sharedCookieJar: CookieJar get() = cookieJar

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

    fun clearCookies() {
        cookieStore.clear()
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            // CookieManager puede no estar disponible durante un cierre temprano.
        }
    }

    /** Guarda las cookies en el SessionManager para persistir la sesión. */
    fun saveCookies(session: SessionManager) {
        try {
            val all = mutableListOf<Map<String, String>>()
            for ((host, list) in cookieStore) {
                for (c in list) {
                    all.add(mapOf(
                        "host" to host,
                        "name" to c.name,
                        "value" to c.value,
                        "path" to c.path,
                        "expires" to c.expiresAt.toString(),
                        "secure" to c.secure.toString(),
                        "httpOnly" to c.httpOnly.toString()
                    ))
                }
            }
            val json = org.json.JSONArray(all.map { m ->
                org.json.JSONObject(m as Map<*, *>)
            }).toString()
            session.cookiesJson = json
        } catch (e: Exception) { /* ignore */ }
    }

    /** Restaura las cookies desde el SessionManager al arrancar. */
    fun loadCookies(session: SessionManager) {
        try {
            val json = session.cookiesJson
            if (json.isBlank()) return
            val arr = org.json.JSONArray(json)
            cookieStore.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val host = o.optString("host")
                val cookie = Cookie.Builder()
                    .name(o.optString("name"))
                    .value(o.optString("value"))
                    .domain(host)
                    .path(o.optString("path", "/"))
                    .apply {
                        val exp = o.optString("expires", "0").toLongOrNull() ?: 0
                        if (exp > System.currentTimeMillis()) expiresAt(exp)
                        if (o.optString("secure") == "true") secure()
                        if (o.optString("httpOnly") == "true") httpOnly()
                    }
                    .build()
                cookieStore.getOrPut(host) { mutableListOf() }.add(cookie)
            }
        } catch (e: Exception) { /* ignore */ }
    }

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
        val title = doc.selectFirst("#summary-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title().substringBefore(" - ")
        val poster = doc.selectFirst(".show-poster img.video-page-thumbnail")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        // Sinopsis: div[itemprop=description], texto antes de "Elenco:"
        var synopsis = doc.selectFirst("#summary-overview-wrapper .show-overview-text")?.text()?.trim() ?: ""
        if (synopsis.isBlank()) {
            synopsis = doc.selectFirst("div[itemprop=description]")?.text()?.trim() ?: ""
        }
        if (synopsis.contains("Elenco:", ignoreCase = true)) {
            synopsis = synopsis.substringBefore("Elenco:").trim()
        }
        val year = doc.selectFirst(".show-details a[href^=\"/buscar/year\"]")?.text()?.trim()
            ?: Regex("""Año:\s*(\d{4})""").find(html)?.groupValues?.get(1) ?: ""
        val genre = doc.select(".show-details a[itemprop=genre]").map { it.text().trim() }
            .filter { it.isNotBlank() }.joinToString(" ").ifBlank {
                Regex("""Género:\s*([^<]+?)(?:Director:|Elenco:|<)""").find(html)?.groupValues?.get(1)?.trim() ?: ""
            }
        val cast = doc.selectFirst("#summary-overview-wrapper .show-overview-text")?.let { el ->
            val t = el.text()
            if (t.contains("Elenco:", ignoreCase = true)) t.substringAfter("Elenco:").trim() else ""
        } ?: ""
        return MovieInfo(title.trim(), poster, synopsis, year, genre, cast)
    }

    /** Enlace de vídeo extraído de la lista de servidores. */
    data class ServerLink(
        val server: String,
        val language: String,
        val quality: String,
        val extUrl: String, // URL /ext/... que redirige al embed
        val dataId: String
    )

    /** Extrae la lista de servidores de una página de película/episodio. */
    suspend fun serverLinks(host: String, url: String): List<ServerLink> {
        val fullUrl = resolveUrl(host, url)
        val html = getHtml(fullUrl)
        val out = mutableListOf<ServerLink>()

        // Método 1: Jsoup con la estructura #embed-list
        try {
            val doc = Jsoup.parse(html, host)
            for (el in doc.select("#embed-list .embed-selector[data-id]")) {
                try {
                    val dataId = el.attr("data-id")
                    val server = el.selectFirst(".provider")?.text()?.trim() ?: ""
                    var language = ""
                    var quality = ""
                    el.select("h5.left span").forEach { sp ->
                        val t = sp.text()
                        when {
                            t.contains("Idioma:", ignoreCase = true) -> language = t.substringAfter("Idioma:", "").trim()
                            t.contains("Calidad:", ignoreCase = true) -> quality = t.substringAfter("Calidad:", "").trim()
                        }
                    }
                    val a = el.select("ul.action-buttons a[target=\"_blank\"]").firstOrNull { it.hasAttr("href") }
                        ?: el.select("ul.action-buttons a[href]").firstOrNull { it.attr("href").contains("/ext/") }
                        ?: continue
                    var href = a.attr("abs:href")
                    if (href.isBlank()) {
                        href = host.trimEnd('/') + "/" + a.attr("href").trimStart('/')
                    }
                    if (server.isBlank() && href.isBlank()) continue
                    out.add(ServerLink(server, language, quality, href, dataId))
                } catch (e: Exception) { /* skip */ }
            }
        } catch (e: Exception) { /* fallback a regex */ }

        // Método 2 (respaldo): buscar /ext/... directamente en el HTML con regex
        if (out.isEmpty()) {
            try {
                // Buscar bloques de embed-selector con regex
                val blockRe = Regex("""data-id="(\d+)"[^>]*>.*?class="provider"[^>]*>([^<]+)<.*?href="(/ext/[^"]+)"""", RegexOption.DOT_MATCHES_ALL)
                // Enfoque más simple: todos los /ext/ únicos
                val extRe = Regex("""/ext/([A-Za-z0-9+/=]+)""")
                val seen = mutableSetOf<String>()
                for (m in extRe.findAll(html)) {
                    val extPath = m.value
                    if (!seen.add(extPath)) continue
                    val fullExt = if (extPath.startsWith("http")) extPath else host.trimEnd('/') + extPath
                    // Intentar deducir servidor del contexto cercano (200 chars antes)
                    val ctxStart = maxOf(0, m.range.first - 2000)
                    val ctx = html.substring(ctxStart, m.range.first)
                    val srvM = Regex("""class="provider"[^>]*>([^<]+)<""").findAll(ctx).lastOrNull()
                    val server = srvM?.groupValues?.get(1)?.trim() ?: ""
                    val langM = Regex("""Idioma:</b>\s*([^<]+)""").findAll(ctx).lastOrNull()
                    val language = langM?.groupValues?.get(1)?.trim() ?: ""
                    val qM = Regex("""Calidad:</b>\s*([^<]+)""").findAll(ctx).lastOrNull()
                    val quality = qM?.groupValues?.get(1)?.trim() ?: ""
                    val idM = Regex("""data-id="(\d+)"""").findAll(ctx).lastOrNull()
                    val dataId = idM?.groupValues?.get(1) ?: ""
                    out.add(ServerLink(server, language, quality, fullExt, dataId))
                }
            } catch (e: Exception) { /* ignore */ }
        }

        return out.distinctBy { it.extUrl }
    }

    /**
     * Resuelve una URL /ext/... siguiendo redirecciones hasta el embed final.
     * Devuelve la URL del reproductor embebido.
     */
    suspend fun resolveExtUrl(extUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            // Sin followRedirects para capturar la redirección manualmente
            val noRedirect = client.newBuilder().followRedirects(false).build()
            var current = extUrl
            repeat(5) {
                noRedirect.newCall(
                    Request.Builder()
                        .url(current)
                        .header("User-Agent", UA)
                        .header("Referer", current)
                        .build()
                )
                    .execute().use { resp ->
                        val loc = resp.header("Location")
                        if ((resp.code == 301 || resp.code == 302 || resp.code == 303 || resp.code == 307 || resp.code == 308) && loc != null) {
                            current = current.toHttpUrl().resolve(loc)?.toString() ?: return@withContext null
                            return@repeat
                        }
                        // Si devuelve HTML, buscar iframe o URL del embed
                        val body = resp.body?.string() ?: ""
                        val iframe = Regex("""<iframe[^>]+src=["']([^"']+)""").find(body)?.groupValues?.get(1)
                        if (iframe != null) {
                            current = current.toHttpUrl().resolve(iframe)?.toString() ?: iframe
                            return@use
                        }
                        return@withContext current
                    }
            }
            current
        } catch (e: Exception) {
            null
        }
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

package com.hermes.hdfull.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import kotlin.coroutines.resume

/**
 * Extrae los enlaces de vídeo usando un WebView invisible.
 *
 * La página real de HDFull descifra su variable `ad` con su propio JavaScript
 * y genera el DOM de #embed-list. Este extractor deja que la página haga el
 * trabajo y luego lee el HTML resultante.
 *
 * El WebView nunca es visible para el usuario (0x0 píxeles, no se añade a la UI).
 */
object WebViewLinkExtractor {

    /**
     * Carga [pageUrl] en un WebView oculto, espera a que aparezcan los
     * .embed-selector dentro de #embed-list y devuelve los ServerLink.
     *
     * @param timeoutMs tiempo máximo de espera total.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extract(context: Context, pageUrl: String, timeoutMs: Long = 30000): List<HdfullClient.ServerLink> =
        when (val r = extractWithStatus(context, pageUrl, timeoutMs)) {
            is ExtractResult.Success -> r.links
            else -> emptyList()
        }

    /** Resultado con estado para diagnóstico (punto 6 del análisis: no ocultar errores). */
    sealed interface ExtractResult {
        data class Success(val links: List<HdfullClient.ServerLink>) : ExtractResult
        data object CloudflareBlocked : ExtractResult
        data object NoSession : ExtractResult
        data object Timeout : ExtractResult
        data class Error(val message: String) : ExtractResult
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extractWithStatus(context: Context, pageUrl: String, timeoutMs: Long = 30000): ExtractResult =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                var webView: WebView? = null
                val handler = Handler(Looper.getMainLooper())
                var finished = false

                fun finish(result: ExtractResult) {
                    if (finished) return
                    finished = true
                    handler.removeCallbacksAndMessages(null)
                    try {
                        webView?.stopLoading()
                        webView?.destroy()
                    } catch (e: Exception) { /* ignore */ }
                    webView = null
                    if (cont.isActive) cont.resume(result)
                }

                // Timeout global
                handler.postDelayed({ finish(ExtractResult.Timeout) }, timeoutMs)

                try {
                    // Sincronizar cookies de la sesión nativa al WebView
                    HdfullClient.exportCookiesToWebView(pageUrl)
                    CookieManager.getInstance().flush()

                    webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = HdfullClient.UA
                        // Invisible: nunca se adjunta a la jerarquía de vistas
                    }

                    val wv = webView!!

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // Sondeo: esperar a que el JS de la página genere los enlaces
                            pollForLinks(wv, handler, ::finish, attempts = 40)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?
                        ) {
                            super.onReceivedError(view, errorCode, description, failingUrl)
                            // No terminar aquí: la página puede recuperarse; el timeout manda
                        }
                    }

                    wv.loadUrl(pageUrl)
                } catch (e: Exception) {
                    finish(ExtractResult.Error(e.message ?: "Error desconocido"))
                }

                cont.invokeOnCancellation {
                    try {
                        webView?.stopLoading()
                        webView?.destroy()
                    } catch (e: Exception) { /* ignore */ }
                }
            }
        }

    /**
     * Sondea el DOM hasta que #embed-list contenga .embed-selector,
     * o hasta agotar los intentos. Al encontrarlos, extrae el HTML.
     */
    private fun pollForLinks(
        wv: WebView,
        handler: Handler,
        finish: (ExtractResult) -> Unit,
        attempts: Int
    ) {
        if (attempts <= 0) {
            finish(ExtractResult.Timeout)
            return
        }
        val js = """
            (function() {
                var html = document.documentElement.innerHTML;
                // Punto 1: validar que es la página real, no Cloudflare/login
                if (html.indexOf('challenge-platform') !== -1 ||
                    html.indexOf('Just a moment') !== -1 ||
                    html.indexOf('cf-challenge') !== -1) {
                    return 'CLOUDFLARE';
                }
                var loggedOut = document.querySelector(
                    'form[action*="login"], input[name="username"], input[name="password"]'
                );
                if (!document.getElementById('header-signout') &&
                    html.indexOf('header-signout') === -1 && loggedOut) {
                    // Solo declarar sesión caducada cuando hay señales reales
                    // del formulario de login, no durante una carga parcial.
                    return 'NO_SESSION';
                }
                var list = document.getElementById('embed-list');
                if (!list) return 'NO_LIST';
                var sels = list.querySelectorAll('.embed-selector[data-id]');
                if (sels.length === 0) {
                    // Fallback: buscar cualquier enlace /ext/ en toda la página
                    var extLinks = document.querySelectorAll('a[href*="/ext/"]');
                    if (extLinks.length === 0) return 'EMPTY';
                    return document.documentElement.outerHTML;
                }
                return list.innerHTML;
            })();
        """.trimIndent()

        try {
            wv.evaluateJavascript(js) { result ->
                handler.post {
                    try {
                        if (result == null || result == "null") {
                            handler.postDelayed({ pollForLinks(wv, handler, finish, attempts - 1) }, 750)
                            return@post
                        }
                        // evaluateJavascript devuelve el string entrecomillado y escapado
                        val unescaped = unescapeJsString(result)
                        when (unescaped) {
                            "NO_LIST", "EMPTY" -> handler.postDelayed(
                                { pollForLinks(wv, handler, finish, attempts - 1) }, 750
                            )
                            "CLOUDFLARE" -> finish(ExtractResult.CloudflareBlocked)
                            "NO_SESSION" -> {
                                // Sin sesión: no hay enlaces que extraer
                                finish(ExtractResult.NoSession)
                            }
                            else -> finish(ExtractResult.Success(parseEmbedListHtml(unescaped)))
                        }
                    } catch (e: Exception) {
                        handler.postDelayed({ pollForLinks(wv, handler, finish, attempts - 1) }, 750)
                    }
                }
            }
        } catch (e: Exception) {
            handler.postDelayed({ pollForLinks(wv, handler, finish, attempts - 1) }, 750)
        }
    }

    /** Desescapa el string que devuelve evaluateJavascript ("..." con escapes). */
    private fun unescapeJsString(s: String): String {
        var t = s.trim()
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length - 1)
        }
        val sb = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\\' && i + 1 < t.length) {
                when (val n = t[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\'' -> sb.append('\'')
                    '\\' -> sb.append('\\')
                    'u' -> {
                        if (i + 5 < t.length) {
                            val hex = t.substring(i + 2, i + 6)
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            i += 4
                        }
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Parsea el innerHTML de #embed-list a ServerLink. Acepta cualquier proveedor. */
    fun parseEmbedListHtml(html: String): List<HdfullClient.ServerLink> {
        val out = mutableListOf<HdfullClient.ServerLink>()
        try {
            val doc = Jsoup.parseBodyFragment(html)
            // 1. Selectores estándar de HDFull
            for (el in doc.select(".embed-selector[data-id]")) {
                try {
                    val dataId = el.attr("data-id")
                    val server = el.selectFirst(".provider")?.text()?.trim() ?: ""
                    var language = ""
                    var quality = ""
                    el.select("h5.left span").forEach { sp ->
                        val text = sp.text()
                        when {
                            text.contains("Idioma:", ignoreCase = true) ->
                                language = text.substringAfter("Idioma:", "").trim()
                            text.contains("Calidad:", ignoreCase = true) ->
                                quality = text.substringAfter("Calidad:", "").trim()
                        }
                    }
                    // Buscar href en <a>, data-* y onclick (punto 2 del análisis)
                    val href = extractHref(el) ?: continue
                    if (href.isBlank()) continue
                    out.add(HdfullClient.ServerLink(server, language, quality, href, dataId))
                } catch (e: Exception) { /* skip */ }
            }
            // 2. Fallback: cualquier <a href="/ext/..."> en el documento
            if (out.isEmpty()) {
                for (a in doc.select("a[href*=/ext/]")) {
                    try {
                        val href = a.attr("href")
                        if (href.isBlank()) continue
                        // Contexto cercano para servidor/idioma/calidad
                        var parent = a.parent()
                        var server = ""
                        var dataId = ""
                        var depth = 0
                        while (parent != null && depth < 4) {
                            server = parent.selectFirst(".provider")?.text()?.trim() ?: server
                            if (dataId.isBlank()) dataId = parent.attr("data-id")
                            parent = parent.parent()
                            depth++
                        }
                        out.add(HdfullClient.ServerLink(server, "", "", href, dataId))
                    } catch (e: Exception) { /* skip */ }
                }
            }
            // 3. Fallback: iframes con src de embed
            if (out.isEmpty()) {
                for (iframe in doc.select("iframe[src]")) {
                    try {
                        val src = iframe.attr("src")
                        if (src.isBlank() || src.contains("google") || src.contains("facebook")) continue
                        out.add(HdfullClient.ServerLink("", "", "", src, ""))
                    } catch (e: Exception) { /* skip */ }
                }
            }
        } catch (e: Exception) { /* ignore */ }
        return out.distinctBy { it.extUrl }
    }

    /**
     * Extrae la URL de un elemento buscando en href, data-* y onclick.
     * Resuelve relativas contra la URL base si se proporciona.
     */
    private fun extractHref(el: org.jsoup.nodes.Element, baseUrl: String = ""): String? {
        // 1. <a href>
        el.select("a[href]").forEach { a ->
            val h = a.attr("href").trim()
            if (h.isNotBlank() && !h.startsWith("#") && !h.startsWith("javascript:")) {
                return resolveUrl(h, baseUrl)
            }
        }
        // 2. Atributos data-* habituales
        for (attr in listOf("data-url", "data-href", "data-link", "data-embed", "data-src")) {
            val v = el.attr(attr).trim()
            if (v.isNotBlank()) return resolveUrl(v, baseUrl)
            // También en descendientes
            el.select("[$attr]").forEach { d ->
                val dv = d.attr(attr).trim()
                if (dv.isNotBlank()) return resolveUrl(dv, baseUrl)
            }
        }
        // 3. onclick="window.open('...')" u onclick="location='...'"
        el.select("[onclick]").forEach { o ->
            val oc = o.attr("onclick")
            val m = Regex("""['"](https?://[^'"]+|/[^'"]+)['"]""").find(oc)
            if (m != null) return resolveUrl(m.groupValues[1], baseUrl)
        }
        // 4. El propio elemento si tiene los atributos
        for (attr in listOf("data-url", "data-href", "data-link", "data-embed")) {
            val v = el.attr(attr).trim()
            if (v.isNotBlank()) return resolveUrl(v, baseUrl)
        }
        return null
    }

    private fun resolveUrl(url: String, base: String): String {
        if (url.startsWith("http") || url.startsWith("//")) {
            return if (url.startsWith("//")) "https:$url" else url
        }
        if (base.isNotBlank()) {
            return try {
                java.net.URL(java.net.URL(base), url).toString()
            } catch (e: Exception) { url }
        }
        return url
    }
}

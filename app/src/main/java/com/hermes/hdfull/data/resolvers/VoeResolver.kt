package com.hermes.hdfull.data.resolvers

import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Resolutor para Voe (voe.sx y sus múltiples dominios espejo).
 * Lógica portada del plugin de ResolveURL: extrae el bloque JSON
 * codificado y aplica la descodificación propia del sitio.
 */
class VoeResolver : VideoResolver {
    override val name = "Voe"

    // voe.sx más los dominios espejo conocidos
    private val domainRe = Pattern.compile(
        """(?://|\.)((?:voe\.sx|voeunblock\d*\.com|voe-unblock\.com|smoki\.cc|chuckle-tube\.com|goofy-banana\.com|[^/]*voe[^/]*)\.[a-z]{2,})""",
        Pattern.CASE_INSENSITIVE
    )

    override fun matches(url: String): Boolean {
        val h = hostOf(url)
        return h.contains("voe") || domainRe.matcher(url).find()
    }

    override suspend fun resolve(url: String): String? {
        var webUrl = url
        val initialReferer = runCatching {
            val parsed = url.toHttpUrl()
            "${parsed.scheme}://${parsed.host}/"
        }.getOrDefault(url)
        var html = httpGet(webUrl, referer = initialReferer) ?: return null

        // Seguir redirecciones JS del tipo window.location.href = '...'
        var guard = 0
        while (html.contains("const currentUrl") && guard++ < 5) {
            val m = Pattern.compile("""window\.location\.href\s*=\s*'([^']+)""").matcher(html)
            if (!m.find()) break
            webUrl = m.group(1)
            html = httpGet(webUrl) ?: return null
        }

        // Bloque principal: json">[ "DATA" ]</script> + <script src="LUT">
        val main = Pattern.compile(
            """json">\["([^"]+)"]</script>\s*<script\s*src="([^"]+)"""
        ).matcher(html)
        if (main.find()) {
            val ct = main.group(1)
            val lutUrl = main.group(2).let {
                if (it.startsWith("http")) it
                else "https://" + hostOf(webUrl) + (if (it.startsWith("/")) it else "/$it")
            }
            val lutHtml = httpGet(lutUrl, referer = webUrl)
            if (lutHtml != null) {
                val lutM = Pattern.compile("""(\[(?:'\W{2}'[,\]]){1,9})""").matcher(lutHtml)
                if (lutM.find()) {
                    val decoded = voeDecode(ct, lutM.group(1))
                    if (decoded != null) {
                        // Preferir m3u8, si no mp4 con mayor resolución
                        val file = decoded.optString("file")
                        val source = decoded.optString("source")
                        val direct = decoded.optString("direct_access_url")
                        val candidates = listOf(direct, source, file).filter { it.isNotBlank() }
                        val m3u8 = candidates.firstOrNull { it.contains("m3u8") }
                        return (m3u8 ?: candidates.firstOrNull())?.let { withHeaders(it, webUrl) }
                    }
                }
            }
        }

        // Fallback: patrones directos en el HTML
        val fallbacks = listOf(
            """mp4["']:\s*["']([^"']+)["'],\s*["']video_height["']""",
            """hls':\s*'([^']+)""",
            """hls":\s*"([^"]+)"""
        )
        for (p in fallbacks) {
            val m = Pattern.compile(p).matcher(html)
            if (m.find()) return withHeaders(m.group(1), webUrl)
        }
        return null
    }

    /**
     * Descodificación propia de Voe:
     * 1. desplazamiento de letras, 2. eliminar patrones LUT,
     * 3. base64, 4. restar 3 a cada char, 5. invertir + base64, 6. JSON.
     */
    private fun voeDecode(ct: String, luts: String): JSONObject? {
        return try {
            val lutParts = luts.substring(2, luts.length - 2).split("','")
            var txt = StringBuilder()
            for (c in ct) {
                var x = c.code
                if (x in 65..90) x = (x - 52) % 26 + 65
                else if (x in 97..122) x = (x - 84) % 26 + 97
                txt.append(x.toChar())
            }
            var s = txt.toString()
            for (part in lutParts) {
                val escaped = part.map {
                    if (it in ".*+?^\${}()|[]\\") "\\$it" else it.toString()
                }.joinToString("")
                s = s.replace(Regex(escaped), "")
            }
            var decoded = String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
            decoded = decoded.map { (it.code - 3).toChar() }.joinToString("")
            decoded = String(Base64.decode(decoded.reversed(), Base64.DEFAULT), Charsets.UTF_8)
            JSONObject(decoded)
        } catch (e: Exception) {
            null
        }
    }

    private fun withHeaders(url: String, referer: String): String {
        val normalized = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            else -> url
        }
        return normalized
    }
}

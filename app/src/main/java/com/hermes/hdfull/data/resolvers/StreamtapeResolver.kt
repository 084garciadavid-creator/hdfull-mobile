package com.hermes.hdfull.data.resolvers

import java.util.regex.Pattern

/**
 * Resolutor para Streamtape (streamtape.com y espejos).
 * Reconstruye la URL del vídeo a partir del fragmento ofuscado
 * que el sitio genera con getElementById + substring.
 */
class StreamtapeResolver : VideoResolver {
    override val name = "Streamtape"

    override fun matches(url: String): Boolean =
        hostOf(url).contains("streamtape")

    override suspend fun resolve(url: String): String? {
        val host = hostOf(url).ifBlank { return null }
        val html = httpGet(url, referer = "https://$host/") ?: return null

        // Patrón: document.getElementById('xxx').innerHTML = "//..." + ...
        val srcM = Pattern.compile("""ById\('.+?=\s*(["']//[^;<]+)""").matcher(html)
        if (srcM.find()) {
            var srcUrl = ""
            val expr = srcM.group(1).replace("'", "\"")
            for (part in expr.split("+")) {
                val p1 = Pattern.compile(""""([^"]*)""").matcher(part)
                if (!p1.find()) continue
                var chunk = p1.group(1)
                var skip = 0
                if ("substring" in part) {
                    val nums = Pattern.compile("""substring\((\d+)""").matcher(part)
                    while (nums.find()) skip += nums.group(1).toInt()
                }
                if (skip < chunk.length) chunk = chunk.substring(skip)
                srcUrl += chunk
            }
            srcUrl += "&stream=1"
            if (srcUrl.startsWith("//")) srcUrl = "https:$srcUrl"
            // Seguir la redirección final para obtener la URL directa
            return followRedirect(srcUrl, "https://$host/")
        }

        // Fallback genérico
        val direct = Pattern.compile("""(https?://[^"'\s]+\.(?:mp4|m3u8)[^"'\s]*)""").matcher(html)
        if (direct.find()) return direct.group(1)
        return null
    }

    private suspend fun followRedirect(url: String, referer: String): String? {
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", RESOLVER_UA)
                    .header("Referer", referer)
                    .build()
                resolverHttp.newCall(req).execute().use { resp ->
                    val final = resp.request.url.toString()
                    // Si hubo redirección, esa es la URL directa
                    if (final != url) final else url
                }
            }
        } catch (e: Exception) {
            url
        }
    }
}

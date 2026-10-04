package com.hermes.hdfull.data.resolvers

import java.util.regex.Pattern

/**
 * Resolutor para Uqload (uqload.vc / uqload.com …).
 * Extrae el bloque sources: [{file: '...'}] de la página del embed.
 */
class UqloadResolver : VideoResolver {
    override val name = "Uqload"

    override fun matches(url: String): Boolean =
        hostOf(url).contains("uqload")

    override suspend fun resolve(url: String): String? {
        // Normalizar a la URL del embed
        val embedUrl = if ("/embed-" in url) url
        else {
            val id = url.substringAfterLast("/").substringBefore(".html").ifBlank { return null }
            "https://" + hostOf(url) + "/embed-$id.html"
        }
        val html = httpGet(embedUrl, referer = embedUrl) ?: return null
        val m = Pattern.compile(
            """sources:\s*\[\{\s*file:\s*['"]([^'"]+)"""
        ).matcher(html)
        return if (m.find()) m.group(1) else null
    }
}

/**
 * Resolutor para Filemoon (filemoon.sx y espejos).
 * Patrón similar: extrae file: "..." del reproductor.
 */
class FilemoonResolver : VideoResolver {
    override val name = "Filemoon"

    override fun matches(url: String): Boolean =
        hostOf(url).contains("filemoon")

    override suspend fun resolve(url: String): String? {
        val html = httpGet(url, referer = url) ?: return null
        // iframe intermedio
        val iframe = Pattern.compile("""<iframe[^>]+src="([^"]+)"""").matcher(html)
        var page = html
        if (iframe.find()) {
            var src = iframe.group(1)
            if (src.startsWith("//")) src = "https:$src"
            httpGet(src, referer = url)?.let { page = it }
        }
        val patterns = listOf(
            """sources:\s*\[\{\s*file:\s*"([^"]+)""",
            """file:\s*"([^"]+\.(?:mp4|m3u8)[^"]*)""""
        )
        for (p in patterns) {
            val m = Pattern.compile(p).matcher(page)
            if (m.find()) return m.group(1)
        }
        return null
    }
}

/**
 * Resolutor genérico de último recurso: busca URLs directas de vídeo
 * (.mp4 / .m3u8 / .mpd) en el HTML de la página del reproductor.
 * Acepta cualquier URL para no dejar huecos.
 */
class GenericResolver : VideoResolver {
    override val name = "Genérico"

    private val videoRe = Pattern.compile(
        """(https?://[^"'\s<>]+\.(?:mp4|m3u8|mpd)(?:\?[^"'\s<>]*)?)""",
        Pattern.CASE_INSENSITIVE
    )

    override fun matches(url: String): Boolean = true

    override suspend fun resolve(url: String): String? {
        val html = httpGet(url, referer = url) ?: return null
        val m = videoRe.matcher(html)
        // Preferir m3u8 sobre mp4 si hay varios
        var first: String? = null
        while (m.find()) {
            val u = m.group(1)
            if (u.contains(".m3u8")) return u
            if (first == null) first = u
        }
        return first
    }
}

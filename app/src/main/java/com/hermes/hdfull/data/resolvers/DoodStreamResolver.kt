package com.hermes.hdfull.data.resolvers

import java.util.regex.Pattern

/**
 * Resolutor para DoodStream (dood.watch, doodstream.com y espejos).
 * Descarga la página del embed, sigue al iframe si lo hay y extrae
 * la URL del vídeo con el token de sesión.
 */
class DoodStreamResolver : VideoResolver {
    override val name = "DoodStream"

    private val domainRe = Pattern.compile(
        """(?://|\.)((?:do*0*o*0*ds?(?:tream|ter)?|ds[2v](?:play|video)|v*id(?:pla?y|e0)|all3do|do(?:7go|ply)|playmogo|doodcdn)\.[a-z]{2,})""",
        Pattern.CASE_INSENSITIVE
    )

    override fun matches(url: String): Boolean {
        val h = hostOf(url)
        return h.contains("dood") || h.contains("ds2play") || h.contains("ds2video") ||
                h.contains("vidply") || h.contains("doply") || domainRe.matcher(url).find()
    }

    override suspend fun resolve(url: String): String? {
        val host = hostOf(url).ifBlank { return null }
        val referer = "https://$host/"
        var html = httpGet(url, referer = referer) ?: return null

        // Seguir al iframe del reproductor si existe
        val iframe = Pattern.compile("""<iframe\s*src="([^"]+)""").matcher(html)
        if (iframe.find()) {
            var src = iframe.group(1)
            if (src.startsWith("//")) src = "https:$src"
            else if (src.startsWith("/")) src = "https://$host$src"
            html = httpGet(src, referer = url) ?: return null
        } else if ("/d/" in url) {
            // Probar la variante /e/ del embed
            val eUrl = url.replace("/d/", "/e/")
            httpGet(eUrl, referer = referer)?.let { html = it }
        }

        // Patrón principal: token + makePlay (ver plugin de referencia)
        val tokenM = Pattern.compile(
            """dsplayer\.hotkeys[^']+'([^']+).+?function\s*makePlay.+?return[^?]+([^"]+)""",
            Pattern.DOTALL
        ).matcher(html)
        if (tokenM.find()) {
            val token = tokenM.group(1)
            var passPath = tokenM.group(2).trim()
            if (passPath.startsWith("/")) passPath = "https://$host$passPath"
            else if (!passPath.startsWith("http")) passPath = "https://$host/$passPath"
            val passUrl = passPath + token
            val videoBase = httpGet(passUrl, referer = "https://$host/")?.trim()
            if (!videoBase.isNullOrBlank() && !videoBase.contains("cloudflarestorage.")) {
                // dood_decode: base + 10 chars aleatorios + token + timestamp
                val rand = (('a'..'z') + ('A'..'Z') + ('0'..'9')).shuffled().take(10).joinToString("")
                val ts = System.currentTimeMillis()
                return "$videoBase$rand$token$ts"
            } else if (!videoBase.isNullOrBlank()) {
                return videoBase
            }
        }

        // Fallback: buscar URL directa en el HTML
        val direct = Pattern.compile("""(https?://[^"'\s]+\.(?:mp4|m3u8)[^"'\s]*)""").matcher(html)
        if (direct.find()) return direct.group(1)

        return null
    }
}

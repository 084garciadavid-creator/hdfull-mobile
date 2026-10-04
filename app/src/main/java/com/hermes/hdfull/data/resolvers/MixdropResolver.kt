package com.hermes.hdfull.data.resolvers

import java.util.regex.Pattern

/**
 * Resolutor para Mixdrop (mixdrop.co y espejos).
 * Extrae la variable de fuente de vídeo (vsr/wurl/surl),
 * desempaquetando el JS si viene ofuscado con packer.
 */
class MixdropResolver : VideoResolver {
    override val name = "Mixdrop"

    override fun matches(url: String): Boolean {
        val h = hostOf(url)
        return h.contains("mixdrop") || h.contains("mixdr")
    }

    override suspend fun resolve(url: String): String? {
        val host = hostOf(url).ifBlank { return null }
        val rurl = "https://$host/"
        var html = httpGet(url, referer = rurl) ?: return null

        // Seguir redirección JS: location = "..."
        val loc = Pattern.compile("""location\s*=\s*["']([^'"]+)""").matcher(html)
        if (loc.find()) {
            var next = loc.group(1)
            if (next.startsWith("/")) next = "https://$host$next"
            else if (!next.startsWith("http")) next = "https://$host/$next"
            html = httpGet(next, referer = url) ?: return null
        }

        // Desempaquetar si viene con el packer (p,a,c,k,e,d)
        if ("(p,a,c,k,e,d)" in html) {
            unpacked(html)?.let { html = it }
        }

        val m = Pattern.compile("""(?:vsr|wurl|surl)[^=]*=\s*"([^"]+)""").matcher(html)
        if (m.find()) {
            var surl = m.group(1)
            if (surl.startsWith("//")) surl = "https:$surl"
            return surl
        }
        return null
    }

    /**
     * Desempaqueta código ofuscado con Dean Edwards packer: eval(function(p,a,c,k,e,d){...}).
     * Implementación mínima: extrae p,a,c,k y sustituye.
     */
    private fun unpacked(html: String): String? {
        return try {
            val m = Pattern.compile(
                """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""",
                Pattern.DOTALL
            ).matcher(html)
            if (!m.find()) return null
            var p = m.group(1)
            val a = m.group(2).toInt()
            val c = m.group(3).toInt()
            val k = m.group(4).split("|")
            fun baseN(n: Int, base: Int): String {
                if (n == 0) return "0"
                val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                var num = n
                var s = ""
                while (num > 0) { s = digits[num % base] + s; num /= base }
                return s
            }
            for (i in c - 1 downTo 0) {
                val word = if (i < k.size) k[i] else ""
                if (word.isNotEmpty()) {
                    p = p.replace(Regex("\\b${baseN(i, a)}\\b"), word)
                }
            }
            // Desescapar secuencias básicas
            p.replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\")
        } catch (e: Exception) {
            null
        }
    }
}

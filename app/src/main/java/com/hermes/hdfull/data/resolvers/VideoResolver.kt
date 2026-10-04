package com.hermes.hdfull.data.resolvers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Resolutor nativo de URLs de vídeo: convierte la URL de un reproductor
 * embebido (doodstream, voe, mixdrop…) en la URL directa del fichero
 * (.mp4 / .m3u8). Equivalente en Kotlin a los plugins de ResolveURL,
 * implementado con HTTP + análisis de texto, sin WebView.
 */
interface VideoResolver {
    /** Nombre del servidor que resuelve. */
    val name: String

    /** true si este resolutor puede manejar la URL dada. */
    fun matches(url: String): Boolean

    /**
     * Devuelve la URL directa del vídeo o null si no puede resolverla.
     * Se ejecuta en Dispatchers.IO.
     */
    suspend fun resolve(url: String): String?
}

/** Cliente HTTP compartido por los resolutores (sin cookies de sesión). */
internal val resolverHttp: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

internal const val RESOLVER_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

internal suspend fun httpGet(url: String, referer: String? = null): String? =
    withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", RESOLVER_UA)
                .apply { if (referer != null) header("Referer", referer) }
                .build()
            resolverHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

internal fun hostOf(url: String): String =
    try {
        url.toHttpUrl().host.lowercase()
    } catch (e: Exception) {
        ""
    }

/**
 * Registro de resolutores. Se prueban en orden; el primero que acepte
 * la URL intenta resolverla. Si falla, se pasa al siguiente.
 */
object ResolverRegistry {
    private val resolvers: List<VideoResolver> = listOf(
        DoodStreamResolver(),
        VoeResolver(),
        MixdropResolver(),
        StreamtapeResolver(),
        UqloadResolver(),
        FilemoonResolver(),
        GenericResolver(), // último: patrones genéricos .mp4/.m3u8
    )

    /** Devuelve la URL directa del vídeo o null si ningún resolutor pudo. */
    suspend fun resolve(url: String): String? {
        for (r in resolvers) {
            if (!r.matches(url)) continue
            try {
                val direct = r.resolve(url)
                if (!direct.isNullOrBlank()) return direct
            } catch (e: Exception) {
                // probar con el siguiente
            }
        }
        return null
    }

    /** Nombre del resolutor que aceptaría esta URL (para diagnóstico). */
    fun resolverNameFor(url: String): String? =
        resolvers.firstOrNull { it.matches(url) }?.name
}

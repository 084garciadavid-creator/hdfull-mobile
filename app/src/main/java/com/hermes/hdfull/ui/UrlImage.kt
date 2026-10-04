package com.hermes.hdfull.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.collection.LruCache
import com.hermes.hdfull.data.HdfullClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val imageCache = LruCache<String, Bitmap>(48)

private val imageHttp by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
}

@Composable
fun UrlImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    var bitmap by remember(url) { mutableStateOf(imageCache.get(url)) }

    LaunchedEffect(url) {
        if (bitmap != null || url.isBlank()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", HdfullClient.UA)
                    .header("Referer", "https://hdfull.zip/")
                    .build()
                imageHttp.newCall(req).execute().use { resp ->
                    val body = resp.body ?: return@withContext
                    if (!resp.isSuccessful) return@withContext
                    val bytes = body.bytes()
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        imageCache.put(url, bmp)
                        bitmap = bmp
                    }
                }
            } catch (e: Exception) { /* leave placeholder */ }
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale
        )
    } else {
        Box(modifier = modifier.background(BgCard))
    }
}

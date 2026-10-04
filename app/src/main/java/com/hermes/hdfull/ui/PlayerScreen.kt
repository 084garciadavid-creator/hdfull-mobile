package com.hermes.hdfull.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.hermes.hdfull.data.HdfullClient
import com.hermes.hdfull.data.resolvers.ResolverRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.regex.Pattern

private val VIDEO_RE = Pattern.compile("""\.(mp4|m3u8|mpd)(\?|#|$)""", Pattern.CASE_INSENSITIVE)

@SuppressLint("SetJavaScriptEnabled")
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(embedUrl: String, title: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var videoUrl by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Resolviendo vídeo…") }
    var resolving by remember { mutableStateOf(true) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    val player = remember {
        val dsFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(HdfullClient.UA)
            .setDefaultRequestProperties(
                mapOf("Referer" to embedUrl)
            )
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
            .build()
    }

    fun onResolved(url: String) {
        if (videoUrl != null) return
        videoUrl = url
        resolving = false
        mainHandler.post {
            try { webViewRef.value?.stopLoading(); webViewRef.value?.destroy() } catch (e: Exception) { }
            webViewRef.value = null
        }
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
    }

    DisposableEffect(Unit) {
        onDispose {
            try { player.release() } catch (e: Exception) { }
            try { webViewRef.value?.destroy() } catch (e: Exception) { }
        }
    }

    // Timeout guard
    LaunchedEffect(embedUrl) {
        scope.launch {
            delay(45000)
            if (videoUrl == null && resolving) {
                resolving = false
                status = "No se pudo resolver el vídeo"
            }
        }
    }

    // Fase 1: intentar resolutores nativos (HTTP + análisis, sin WebView).
    // Si ninguno resuelve, se usa el WebView oculto como respaldo.
    var nativeFailed by remember { mutableStateOf(false) }
    LaunchedEffect(embedUrl) {
        val rname = ResolverRegistry.resolverNameFor(embedUrl)
        if (rname != null) status = "Resolviendo con $rname…"
        val direct = ResolverRegistry.resolve(embedUrl)
        if (direct != null) {
            onResolved(direct)
        } else {
            nativeFailed = true
            if (rname == null) status = "Resolviendo vídeo…"
            else status = "Probando método alternativo…"
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Atrás", tint = Gold)
            }
            Text(
                title.ifBlank { "Reproduciendo" },
                color = TextWhite,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
        }

        // Respaldo: WebView oculto solo si los resolutores nativos no pudieron
        if (resolving && nativeFailed) {
            Box(Modifier.size(1.dp)) {
                AndroidView(factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString = HdfullClient.UA
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView, request: WebResourceRequest
                            ): WebResourceResponse? {
                                val u = request.url.toString()
                                if (VIDEO_RE.matcher(u).find() && !u.contains("subtitle") && !u.contains(".vtt")) {
                                    mainHandler.post { onResolved(u) }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageFinished(view: WebView, u: String) {
                                super.onPageFinished(view, u)
                                // nudge common players to start so the stream URL is requested
                                view.evaluateJavascript(
                                    "(function(){var v=document.querySelector('video');if(v){v.muted=true;v.play().catch(function(){});} return 'ok';})()",
                                    null
                                )
                            }
                        }
                        webViewRef.value = this
                        HdfullClient.exportCookiesToWebView(embedUrl)
                        loadUrl(embedUrl)
                    }
                })
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (videoUrl != null) {
                AndroidView(
                    factory = { c ->
                        PlayerView(c).apply {
                            this.player = player
                            useController = true
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (resolving) CircularProgressIndicator(color = Gold)
                    Spacer(Modifier.height(8.dp))
                    Text(status, color = TextGrey)
                    if (!resolving && videoUrl == null) {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onBack,
                            colors = ButtonDefaults.buttonColors(containerColor = Gold)
                        ) { Text("Volver", color = androidx.compose.ui.graphics.Color.Black) }
                    }
                }
            }
        }
    }
}

// alias to avoid clash with material3 Color import style used above
private typealias Color = androidx.compose.ui.graphics.Color

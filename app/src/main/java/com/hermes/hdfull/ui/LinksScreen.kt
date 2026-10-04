package com.hermes.hdfull.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hermes.hdfull.data.HdfullClient
import com.hermes.hdfull.data.VideoLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray

private val EXTRACT_JS = """
(function(){
  // Buscar enlaces de servidores de vídeo aunque el sitio los entregue como
  // href, data-url, iframe, source o dentro de onclick.
  var els=document.querySelectorAll('a, iframe, video, source, [data-url], [data-link], [data-src]');
  var r=[];
  var bad=/imdb\.com|youtube\.com|youtu\.be|facebook\.com|twitter\.com|instagram\.com/i;
  var badText=/IMDb|Trailer|Género|Director|Elenco|Enviar Enlaces|Lights Off|Descargas/i;
  var server=/powvideo|streamplay|vidmoly|voe|dood|mixdrop|streamtape|uqload|filemoon|vidoza|uptostream|vidcloud|filelions|streamwish|luluvdo/i;
  function add(raw, txt, el){
    if(!raw) return;
    raw=String(raw).replace(/&amp;/g,'&').trim();
    var m=raw.match(/(?:https?:)?\/\/[^'"\s)]+/i);
    if(m) raw=m[0];
    try { raw=new URL(raw, document.baseURI).href; } catch(e) { return; }
    txt=(txt||'').trim().replace(/\s+/g,' ');
    var parent=el && el.closest && el.closest('div.show-details, div.links, div.servers, table, [class*="server"], [class*="link"]');
    if(!/^https?:/i.test(raw) || bad.test(raw) || badText.test(txt)) return;
    if(parent || server.test(raw) || /\.(?:mp4|m3u8|mpd)(?:[?#]|$)/i.test(raw)) r.push({u:raw,t:txt});
  }
  for(var i=0;i<els.length;i++){
    var a=els[i];
    var txt=(a.innerText||a.textContent||a.getAttribute('title')||'').trim().replace(/\s+/g,' ');
    ['href','src','data-url','data-link','data-src'].forEach(function(k){ add(a.getAttribute(k),txt,a); });
    var click=a.getAttribute('onclick')||'';
    add(click,txt,a);
  }
  var seen={};
  return JSON.stringify(r.filter(function(x){ if(seen[x.u]) return false; seen[x.u]=true; return true; }));
})()
""".trimIndent()

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LinksScreen(url: String, title: String, onPlay: (String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var links by remember { mutableStateOf<List<VideoLink>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Buscando enlaces…") }
    var pageHtml by remember { mutableStateOf<String?>(null) }

    // Descargar el HTML con la sesión nativa (OkHttp), que sí atraviesa Cloudflare
    LaunchedEffect(url) {
        try {
            pageHtml = HdfullClient.getHtml(url)
            if (HdfullClient.isCloudflareChallenge(pageHtml!!)) {
                loading = false
                status = "HDFull solicita una verificación de seguridad"
                pageHtml = null
            }
        } catch (e: Exception) {
            loading = false
            status = "Error al cargar la página"
        }
    }

    fun parseLinks(json: String): List<VideoLink> {
        val out = mutableListOf<VideoLink>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val u = o.optString("u")
                val t = o.optString("t")
                if (u.isBlank()) continue
                val up = t.uppercase()
                val lang = when {
                    up.contains("CAST") || up.contains("ESP") || up.contains("CASTELLANO") -> "CAST"
                    up.contains("LAT") -> "LAT"
                    up.contains("VOSE") -> "VOSE"
                    up.contains("VOS") -> "VOS"
                    up.contains("VO") -> "VO"
                    else -> ""
                }
                val quality = when {
                    up.contains("1080") -> "1080p"
                    up.contains("720") -> "720p"
                    up.contains("4K") || up.contains("2160") -> "4K"
                    up.contains("HDCAM") || up.contains("CAM") -> "CAM"
                    up.contains("HD") -> "HD"
                    else -> ""
                }
                val label = buildString {
                    if (lang.isNotBlank()) append(lang)
                    if (quality.isNotBlank()) { if (isNotEmpty()) append(" · "); append(quality) }
                    if (isEmpty()) append(t.take(40))
                }
                out.add(VideoLink(u, label, lang, quality))
            }
        } catch (e: Exception) { /* ignore */ }
        return out.distinctBy { it.url }
    }

    Column(Modifier.fillMaxSize().background(BgBlack)) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Atrás", tint = Gold)
            }
            Text(
                title.ifBlank { "Enlaces" },
                color = TextWhite,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
        }

        // Hidden WebView: se le inyecta el HTML descargado con la sesión nativa
        // para que el JS del sitio descodifique los enlaces sin depender de su red
        val html = pageHtml
        if (html != null) {
            Box(Modifier.size(1.dp)) {
                AndroidView(factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = HdfullClient.UA
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, u: String) {
                                super.onPageFinished(view, u)
                                scope.launch {
                                    // poll for decoded links
                                    repeat(30) {
                                        delay(900)
                                        view.evaluateJavascript(EXTRACT_JS) { res ->
                                            val list = parseLinks(res?.trim('"')?.replace("\\\"", "\"") ?: "[]")
                                            if (list.isNotEmpty()) {
                                                links = list
                                                loading = false
                                            }
                                        }
                                        delay(150)
                                        if (links.isNotEmpty()) return@launch
                                    }
                                    if (links.isEmpty()) {
                                        loading = false
                                        status = "No se encontraron enlaces"
                                    }
                                }
                            }
                        }
                        // La sesión nativa vive en OkHttp; copiarla antes de
                        // ejecutar el JavaScript evita que la página se vea
                        // como visitante anónimo.
                        HdfullClient.exportCookiesToWebView(url)
                        loadDataWithBaseURL(url, html, "text/html", "UTF-8", url)
                    }
                })
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = Gold)
                    Spacer(Modifier.height(8.dp))
                    Text(status, color = TextGrey)
                }
                links.isEmpty() -> Text(
                    status, color = TextGrey,
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(links) { link ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(BgCard)
                                .clickable { onPlay(link.url) }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Gold,
                                modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(link.label, color = TextWhite, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    link.url.substringAfter("://").substringBefore("/"),
                                    color = TextGrey, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

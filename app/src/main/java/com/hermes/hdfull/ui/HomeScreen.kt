package com.hermes.hdfull.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.hdfull.data.HdfullClient
import com.hermes.hdfull.data.MediaItem
import com.hermes.hdfull.data.SessionManager
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {
    var items by mutableStateOf<List<MediaItem>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var page by mutableStateOf(1)
    var tab by mutableStateOf(0)
    var query by mutableStateOf("")

    fun load(host: String, tabIndex: Int, reset: Boolean = true) {
        if (loading) return
        if (reset) {
            page = 1
            items = emptyList()
        }
        tab = tabIndex
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val url = when (tabIndex) {
                    0 -> "$host/peliculas/date/$page"
                    else -> "$host/series/date/$page"
                }
                val list = HdfullClient.catalog(url)
                items = if (reset) list else items + list
                if (list.isEmpty() && reset) error = "Sin resultados"
            } catch (e: Exception) {
                error = "Error de conexión: ${e.message}"
            }
            loading = false
        }
    }

    fun search(host: String, q: String) {
        if (loading || q.isBlank()) return
        query = q
        loading = true
        error = null
        items = emptyList()
        viewModelScope.launch {
            try {
                val list = HdfullClient.search(host, q)
                items = list
                if (list.isEmpty()) error = "Sin resultados"
            } catch (e: Exception) {
                error = "Error de conexión: ${e.message}"
            }
            loading = false
        }
    }
}

@Composable
fun HomeScreen(
    session: SessionManager,
    onOpenMovie: (String, String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onLogout: () -> Unit
) {
    val vm: HomeViewModel = viewModel()
    val host = session.host
    var showLogout by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (vm.items.isEmpty()) vm.load(host, 0)
    }

    Column(Modifier.fillMaxSize().background(BgBlack)) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("HDFull", style = MaterialTheme.typography.headlineSmall, color = Gold,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { showLogout = true }) {
                Icon(Icons.Default.ExitToApp, contentDescription = "Cerrar sesión", tint = TextGrey)
            }
        }
        // Tabs
        TabRow(selectedTabIndex = vm.tab, containerColor = BgBlack, contentColor = Gold) {
            listOf("Películas", "Series", "Buscar").forEachIndexed { i, t ->
                Tab(
                    selected = vm.tab == i,
                    onClick = { if (i < 2) vm.load(host, i) else { vm.tab = 2 } },
                    text = { Text(t, color = if (vm.tab == i) Gold else TextGrey) }
                )
            }
        }

        if (vm.tab == 2) {
            // Search
            var q by remember { mutableStateOf(vm.query) }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = q, onValueChange = { q = it },
                    placeholder = { Text("Buscar…", color = TextGrey) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Gold, unfocusedBorderColor = GoldDim,
                        focusedTextColor = TextWhite, unfocusedTextColor = TextWhite
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.search(host, q) },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = BgBlack)
                ) { Icon(Icons.Default.Search, contentDescription = "Buscar") }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (vm.loading && vm.items.isEmpty()) {
                CircularProgressIndicator(color = Gold, modifier = Modifier.align(Alignment.Center))
            } else if (vm.error != null && vm.items.isEmpty()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(vm.error!!, color = TextGrey, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { if (vm.tab == 2) vm.search(host, vm.query) else vm.load(host, vm.tab) },
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = BgBlack)
                    ) { Text("Reintentar") }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(vm.items) { item ->
                        MediaCard(item = item, onClick = {
                            if (item.mediatype == "tvshow") onOpenSeries(item.url)
                            else onOpenMovie(item.url, item.title)
                        })
                    }
                    if (vm.tab < 2) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.7f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BgCard)
                                    .clickable {
                                        vm.page++
                                        vm.load(host, vm.tab, reset = false)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (vm.loading) CircularProgressIndicator(color = Gold, modifier = Modifier.size(28.dp))
                                else Text("Cargar\nmás", color = Gold, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            title = { Text("Cerrar sesión", color = TextWhite) },
            text = { Text("Se cerrará tu sesión de HDFull en este dispositivo.", color = TextGrey) },
            confirmButton = {
                TextButton(onClick = { showLogout = false; onLogout() }) {
                    Text("Cerrar sesión", color = Gold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogout = false }) { Text("Cancelar", color = TextGrey) }
            },
            containerColor = BgDialog
        )
    }
}

@Composable
fun MediaCard(item: MediaItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BgCard)
            .clickable(onClick = onClick)
    ) {
        UrlImage(
            url = item.thumb,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(0.7f)
        )
        Text(
            item.title,
            color = TextWhite,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(6.dp)
        )
    }
}

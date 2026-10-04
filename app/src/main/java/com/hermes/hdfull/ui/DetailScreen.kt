package com.hermes.hdfull.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.hdfull.data.Episode
import com.hermes.hdfull.data.HdfullClient
import com.hermes.hdfull.data.Season
import com.hermes.hdfull.data.SessionManager
import kotlinx.coroutines.launch

class DetailViewModel : ViewModel() {
    var title by mutableStateOf("")
    var seasons by mutableStateOf<List<Season>>(emptyList())
    var showId by mutableStateOf("")
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var expandedSeason by mutableStateOf<Int?>(null)
    var episodes by mutableStateOf<List<Episode>>(emptyList())
    var loadingEp by mutableStateOf(false)

    fun load(host: String, url: String) {
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val info = HdfullClient.seriesDetail(host, url)
                title = info.title
                seasons = info.seasons
                showId = info.showId
                if (seasons.isNotEmpty()) {
                    expandedSeason = seasons.first().number
                    loadEpisodes(host)
                }
            } catch (e: Exception) {
                error = "Error: ${e.message}"
            }
            loading = false
        }
    }

    fun loadEpisodes(host: String) {
        val s = expandedSeason ?: return
        if (showId.isBlank()) return
        loadingEp = true
        viewModelScope.launch {
            try {
                episodes = HdfullClient.episodes(host, showId, s)
            } catch (e: Exception) {
                // keep previous
            }
            loadingEp = false
        }
    }
}

@Composable
fun DetailScreen(
    session: SessionManager,
    url: String,
    onOpenEpisode: (String, String) -> Unit,
    onBack: () -> Unit
) {
    val vm: DetailViewModel = viewModel()
    val host = session.host

    LaunchedEffect(url) { vm.load(host, url) }

    Column(Modifier.fillMaxSize().background(BgBlack)) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Atrás", tint = Gold)
            }
            Text(
                vm.title.ifBlank { "Serie" },
                color = TextWhite,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        when {
            vm.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Gold)
            }
            vm.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(vm.error!!, color = TextGrey)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(vm.seasons) { season ->
                    val expanded = vm.expandedSeason == season.number
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(BgCard)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.expandedSeason = if (expanded) null else season.number
                                    if (!expanded) vm.loadEpisodes(host)
                                }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Temporada ${season.number}",
                                color = Gold,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null, tint = Gold
                            )
                        }
                        if (expanded) {
                            if (vm.loadingEp) {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = Gold, modifier = Modifier.size(28.dp))
                                }
                            } else {
                                vm.episodes.forEach { ep ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onOpenEpisode(ep.url, "T${ep.season} E${ep.episode} · ${ep.title}") }
                                            .padding(10.dp, 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        UrlImage(
                                            url = ep.thumb,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(72.dp, 48.dp)
                                                .clip(RoundedCornerShape(6.dp)))
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "E${ep.episode} · ${ep.title}",
                                                color = TextWhite,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (ep.lang.isNotBlank()) {
                                                Text(ep.lang, color = TextGrey, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.hermes.hdfull.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.hdfull.data.HdfullClient
import kotlinx.coroutines.launch

class MovieDetailViewModel : ViewModel() {
    var info by mutableStateOf<HdfullClient.MovieInfo?>(null)
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)

    fun load(host: String, url: String) {
        loading = true
        error = null
        viewModelScope.launch {
            try {
                info = HdfullClient.movieDetail(host, url)
            } catch (e: Exception) {
                error = "Error: ${e.message}"
            }
            loading = false
        }
    }
}

@Composable
fun MovieDetailScreen(
    host: String,
    url: String,
    onOpenLinks: (String, String) -> Unit,
    onBack: () -> Unit
) {
    val vm: MovieDetailViewModel = viewModel()

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
                vm.info?.title?.ifBlank { "Película" } ?: "Película",
                color = TextWhite,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
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
            else -> {
                val info = vm.info!!
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        UrlImage(
                            url = info.poster,
                            contentDescription = info.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(120.dp, 180.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                info.title,
                                color = TextWhite,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            if (info.year.isNotBlank()) {
                                Text("Año: ${info.year}", color = Gold, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (info.genre.isNotBlank()) {
                                Text("Género: ${info.genre}", color = TextGrey, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    if (info.synopsis.isNotBlank()) {
                        Text(info.synopsis, color = TextWhite, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (info.cast.isNotBlank()) {
                        Text(
                            "Elenco: ${info.cast}",
                            color = TextGrey,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onOpenLinks(HdfullClient.resolveUrl(host, url), info.title) },
                        colors = ButtonDefaults.buttonColors(containerColor = Gold),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Ver enlaces", color = androidx.compose.ui.graphics.Color.Black)
                    }
                }
            }
        }
    }
}

package com.hermes.hdfull.data

data class MediaItem(
    val url: String,
    val title: String,
    val thumb: String,
    val lang: String,
    val mediatype: String, // "movie" | "tvshow"
    val infoId: String
)

data class Season(val number: Int)

data class Episode(
    val season: Int,
    val episode: Int,
    val title: String,
    val thumb: String,
    val url: String,
    val lang: String,
    val showId: String
)

data class VideoLink(
    val url: String,
    val label: String,
    val lang: String,
    val quality: String
)

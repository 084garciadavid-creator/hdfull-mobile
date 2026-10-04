package com.hermes.hdfull.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Gold = Color(0xFFC9A227)
val GoldLight = Color(0xFFD4AF37)
val GoldDim = Color(0xFF8A6D1B)
val BgBlack = Color(0xFF0D0D0D)
val BgCard = Color(0xFF171106)
val BgDialog = Color(0xFF1B150C)
val TextWhite = Color(0xFFF0F0F0)
val TextGrey = Color(0xFFA0A0A0)

private val DarkGoldScheme = darkColorScheme(
    primary = Gold,
    onPrimary = Color.Black,
    secondary = GoldLight,
    onSecondary = Color.Black,
    tertiary = GoldDim,
    background = BgBlack,
    onBackground = TextWhite,
    surface = BgCard,
    onSurface = TextWhite,
    surfaceVariant = BgDialog,
    onSurfaceVariant = TextGrey,
    outline = GoldDim
)

@Composable
fun HDFullTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkGoldScheme,
        content = content
    )
}

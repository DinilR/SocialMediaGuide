package com.socialmediaguide.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Night = Color(0xFF14121F)
val NightCard = Color(0xFF221E36)
val NightCardHigh = Color(0xFF2E2849)
val Violet = Color(0xFF6C4BFF)
val VioletDeep = Color(0xFF2B1F66)
val Signal = Color(0xFFFFD23F)
val Danger = Color(0xFFFF5470)
val Calm = Color(0xFF3DDC97)

private val colors = darkColorScheme(
    primary = Signal,
    onPrimary = Night,
    secondary = Violet,
    onSecondary = Color.White,
    background = Night,
    onBackground = Color.White,
    surface = NightCard,
    onSurface = Color.White,
    surfaceVariant = NightCardHigh,
    onSurfaceVariant = Color(0xFFB9B3D6),
    surfaceContainer = NightCard,
    surfaceContainerHigh = NightCardHigh,
    surfaceContainerHighest = NightCardHigh,
    error = Danger,
)

@Composable
fun GuideTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}

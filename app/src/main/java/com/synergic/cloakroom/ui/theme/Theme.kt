package com.synergic.cloakroom.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Navy = Color(0xFF1F3864)
private val SoftBlue = Color(0xFFD9E2F3)
private val Amber = Color(0xFFB7791F)

private val LightColors = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = SoftBlue,
    onPrimaryContainer = Navy,
    secondary = Amber,
    onSecondary = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAFC6FF),
    onPrimary = Color(0xFF0B2350),
    primaryContainer = Navy,
    onPrimaryContainer = SoftBlue,
    secondary = Color(0xFFF2C46D),
)

@Composable
fun CloakRoomTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

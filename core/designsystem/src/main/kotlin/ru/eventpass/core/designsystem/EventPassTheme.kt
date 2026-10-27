package ru.eventpass.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Initial tokens from block 06; full components and Inter assets belong to T03.
private val EventPassColorScheme = darkColorScheme(
    primary = Color(0xFF8083FF),
    onPrimary = Color(0xFF0D0096),
    secondary = Color(0xFFC0C1FF),
    background = Color(0xFF0F131C),
    onBackground = Color(0xFFDFE2EF),
    surface = Color(0xFF181B25),
    onSurface = Color(0xFFDFE2EF),
    surfaceVariant = Color(0xFF1C1F29),
    onSurfaceVariant = Color(0xFFC7C4D7),
    error = Color(0xFFF87171),
)

@Composable
fun EventPassTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = EventPassColorScheme, content = content)
}

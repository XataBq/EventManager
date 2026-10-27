package ru.eventpass.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val EventPassColorScheme = darkColorScheme(
    primary = EventPassColors.accent,
    onPrimary = EventPassColors.onAccent,
    secondary = EventPassColors.accentLight,
    background = EventPassColors.background,
    onBackground = EventPassColors.textPrimary,
    surface = EventPassColors.card,
    onSurface = EventPassColors.textPrimary,
    surfaceVariant = EventPassColors.surface,
    onSurfaceVariant = EventPassColors.textSecondary,
    outline = EventPassColors.raised,
    error = EventPassColors.danger,
)

@Composable
fun EventPassTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EventPassColorScheme,
        typography = EventPassTypography.material,
        shapes = Shapes(EventPassShapes.badge, EventPassShapes.control, EventPassShapes.section, EventPassShapes.large, EventPassShapes.large),
        content = content,
    )
}

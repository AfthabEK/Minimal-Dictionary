package io.github.afthabek.minimaldictionary.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = Paper,
    onPrimary = Ink,
    secondary = Muted,
    background = Ink,
    onBackground = Paper,
    surface = Ink,
    onSurface = Paper,
    surfaceVariant = Faint,
    onSurfaceVariant = Muted,
    outline = Muted,
)

private val LightColors = lightColorScheme(
    primary = InkLight,
    onPrimary = PaperLight,
    secondary = MutedLight,
    background = PaperLight,
    onBackground = InkLight,
    surface = PaperLight,
    onSurface = InkLight,
    surfaceVariant = FaintLight,
    onSurfaceVariant = MutedLight,
    outline = MutedLight,
)

@Composable
fun MinimalDictionaryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}

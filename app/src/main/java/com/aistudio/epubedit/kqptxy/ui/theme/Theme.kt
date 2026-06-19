package com.aistudio.epubedit.kqptxy.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.graphics.Color

private val CosmicDarkColorScheme = darkColorScheme(
    primary = CosmicPrimary,
    onPrimary = CosmicOnPrimary,
    primaryContainer = CosmicPrimaryContainer,
    onPrimaryContainer = CosmicOnPrimaryContainer,
    secondary = CosmicSecondary,
    onSecondary = CosmicOnSecondary,
    secondaryContainer = CosmicSecondaryContainer,
    tertiary = CosmicTertiary,
    background = CosmicBackground,
    surface = CosmicSurface,
    surfaceVariant = CosmicSurfaceVariant,
    onBackground = Color80Patch.White,
    onSurface = Color80Patch.White,
    onSurfaceVariant = CosmicOutline,
    outline = CosmicOutline,
    outlineVariant = CosmicOutlineVariant,
    error = CosmicError,
    onError = CosmicOnError
)

private val CosmicLightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFF7D5260),
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    surfaceVariant = Color(0xFFE7E0EC),
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

private val CosmicAmoledColorScheme = darkColorScheme(
    primary = CosmicPrimary,
    onPrimary = CosmicOnPrimary,
    primaryContainer = CosmicPrimaryContainer,
    onPrimaryContainer = CosmicOnPrimaryContainer,
    secondary = CosmicSecondary,
    onSecondary = CosmicOnSecondary,
    secondaryContainer = CosmicSecondaryContainer,
    tertiary = CosmicTertiary,
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF121212),
    onBackground = Color80Patch.White,
    onSurface = Color80Patch.White,
    onSurfaceVariant = CosmicOutline,
    outline = CosmicOutline,
    outlineVariant = CosmicOutlineVariant,
    error = CosmicError,
    onError = CosmicOnError
)

object Color80Patch {
    val White = Color(0xFFE8E0EA)
}

@Composable
fun MyApplicationTheme(
    themeName: String = "dark",
    content: @Composable () -> Unit,
) {
    val colorScheme = when (themeName) {
        "light" -> CosmicLightColorScheme
        "amoled" -> CosmicAmoledColorScheme
        else -> CosmicDarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

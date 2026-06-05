package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

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

object Color80Patch {
    val White = androidx.compose.ui.graphics.Color(0xFFE8E0EA)
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // We enforce a gorgeous dark theme for creative book editing UI
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = CosmicDarkColorScheme,
        typography = Typography,
        content = content
    )
}

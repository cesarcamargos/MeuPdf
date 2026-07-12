package com.example.meupdf.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

@Composable
fun MeuPDFTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val palette = when (themeMode) {
        ThemeMode.PASTEL -> pastelGlassPalette()
        ThemeMode.DARK -> darkGlassPalette()
    }

    val colorScheme = if (palette.isDark) {
        darkColorScheme(
            primary = palette.accentLavender,
            secondary = palette.accentPink,
            tertiary = palette.accentMint,
            background = DarkBackground,
            surface = DarkSurface,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
        )
    } else {
        lightColorScheme(
            primary = palette.accentLavender,
            secondary = palette.accentPink,
            tertiary = palette.accentMint,
            background = PastelBackground,
            surface = PastelSurface,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
        )
    }

    CompositionLocalProvider(LocalGlassPalette provides palette) {
        MaterialTheme(colorScheme = colorScheme, typography = Typography) {
            Box(Modifier.fillMaxSize().background(palette.screenBackground)) {
                content()
            }
        }
    }
}

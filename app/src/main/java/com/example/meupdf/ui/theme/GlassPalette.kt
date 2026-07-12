package com.example.meupdf.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

enum class ThemeMode { PASTEL, DARK }

/**
 * Colors/brushes for the Windows-7-"Aero glass" look: soft gradient backdrops,
 * translucent rounded panels with a light top sheen and a thin bright border.
 */
data class GlassPalette(
    val isDark: Boolean,
    val screenBackground: Brush,
    val panelBackground: Brush,
    val panelBorder: Color,
    val panelSheen: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accentPink: Color,
    val accentLavender: Color,
    val accentMint: Color,
)

fun pastelGlassPalette(): GlassPalette = GlassPalette(
    isDark = false,
    screenBackground = Brush.verticalGradient(
        colors = listOf(Color(0xFFFDF6F0), PastelBackground, Color(0xFFF3EAFB)),
    ),
    panelBackground = Brush.linearGradient(
        colors = listOf(Color.White, Color(0xFFF7F1FA)),
        start = Offset(0f, 0f),
        end = Offset(0f, 400f),
    ),
    panelBorder = Color.White.copy(alpha = 0.9f),
    panelSheen = Color.White.copy(alpha = 0.7f),
    textPrimary = PastelTextPrimary,
    textSecondary = PastelTextSecondary,
    accentPink = PastelPink,
    accentLavender = PastelLavender,
    accentMint = PastelMint,
)

fun darkGlassPalette(): GlassPalette = GlassPalette(
    isDark = true,
    screenBackground = Brush.verticalGradient(
        colors = listOf(Color(0xFF0F0D15), DarkBackground, Color(0xFF1B1526)),
    ),
    panelBackground = Brush.linearGradient(
        colors = listOf(Color(0xFF2A2536), Color(0xFF221E2D)),
        start = Offset(0f, 0f),
        end = Offset(0f, 400f),
    ),
    panelBorder = Color.White.copy(alpha = 0.16f),
    panelSheen = Color.White.copy(alpha = 0.12f),
    textPrimary = DarkTextPrimary,
    textSecondary = DarkTextSecondary,
    accentPink = DarkAccentPink,
    accentLavender = DarkAccentLavender,
    accentMint = DarkAccentMint,
)

val LocalGlassPalette = staticCompositionLocalOf { pastelGlassPalette() }

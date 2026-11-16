package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import com.example.data.AppThemeMode

@Immutable
data class KagojpotroGlassPalette(
    val isDark: Boolean,
    val accent: Color,
    val onAccent: Color,
    val accentHighlight: Color,
    val accentSoft: Color,
    val glassCardFill: Color,
    val glassHeaderFill: Color,
    val glassSubfolderFrameFill: Color,
    val glassStickyDateFill: Color,
    val inactiveButtonFill: Color,
    val inactiveButtonBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val dividerColor: Color,
    val danger: Color,
    val dangerOnDark: Color,
    val accentGlassBorderBrush: Brush,
    val subtleGlassBorderBrush: Brush,
    val glassSurfaceGradient: Brush
)

val LocalGlassPalette = staticCompositionLocalOf {
    val defaultAccent = DeepTeal
    KagojpotroGlassPalette(
        isDark = false,
        accent = defaultAccent,
        onAccent = Color.White,
        accentHighlight = MintHighlight,
        accentSoft = defaultAccent.copy(alpha = 0.12f),
        glassCardFill = Color.White,
        glassHeaderFill = PaperWhite,
        glassSubfolderFrameFill = PaperSurfaceVariant,
        glassStickyDateFill = PaperWhite,
        inactiveButtonFill = Color(0xFFE7EDE9),
        inactiveButtonBorder = Color(0xFFBCC9C2),
        textPrimary = InkCharcoal,
        textSecondary = InkSlate,
        dividerColor = Color(0xFFDCE3DF),
        danger = DangerRed,
        dangerOnDark = Color(0xFFFF8A80),
        accentGlassBorderBrush = SolidColor(defaultAccent.copy(alpha = 0.28f)),
        subtleGlassBorderBrush = SolidColor(Color(0xFFD6DFDA)),
        glassSurfaceGradient = SolidColor(Color.White)
    )
}

@Composable
fun KagojpotroTheme(
    themeMode: AppThemeMode = AppThemeMode.LIGHT,
    accentColorHex: String = "#0F8B6D",
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
    }

    val rawAccent = parseHexColor(accentColorHex, DeepTeal)
    val accent = when {
        isDark && rawAccent.luminance() < 0.18f -> Color(
            red = (rawAccent.red * 0.7f + 0.3f).coerceIn(0f, 1f),
            green = (rawAccent.green * 0.7f + 0.3f).coerceIn(0f, 1f),
            blue = (rawAccent.blue * 0.7f + 0.3f).coerceIn(0f, 1f),
            alpha = 1f
        )
        !isDark && rawAccent.luminance() > 0.62f -> Color(
            red = (rawAccent.red * 0.72f).coerceIn(0f, 1f),
            green = (rawAccent.green * 0.72f).coerceIn(0f, 1f),
            blue = (rawAccent.blue * 0.72f).coerceIn(0f, 1f),
            alpha = 1f
        )
        else -> rawAccent
    }
    val highlightMix = if (rawAccent.luminance() < 0.45f) 0.38f else 0.16f
    val accentHighlight = Color(
        red = (rawAccent.red + (1f - rawAccent.red) * highlightMix).coerceIn(0f, 1f),
        green = (rawAccent.green + (1f - rawAccent.green) * highlightMix).coerceIn(0f, 1f),
        blue = (rawAccent.blue + (1f - rawAccent.blue) * highlightMix).coerceIn(0f, 1f),
        alpha = 1f
    )

    val onAccent = if (accent.luminance() > 0.52f) InkCharcoal else Color.White

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accent.copy(alpha = 0.24f),
            onPrimaryContainer = Color.White,
            secondary = accent,
            onSecondary = onAccent,
            secondaryContainer = Color(0xFF2E3438),
            onSecondaryContainer = Color(0xFFF2F5F4),
            tertiary = accentHighlight,
            background = ObsidianDark,
            onBackground = Color(0xFFF2F5F4),
            surface = ObsidianSurface,
            onSurface = Color(0xFFF2F5F4),
            surfaceVariant = Color(0xFF2E3438),
            onSurfaceVariant = Color(0xFFD2DCD8),
            outline = Color(0xFF485258),
            error = Color(0xFFFF6B6B),
            onError = Color.White,
            errorContainer = DangerRed.copy(alpha = 0.32f),
            onErrorContainer = Color(0xFFFFDAD6)
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accent.copy(alpha = 0.15f),
            onPrimaryContainer = InkCharcoal,
            secondary = accent,
            onSecondary = onAccent,
            secondaryContainer = Color(0xFFE7EDE9),
            onSecondaryContainer = InkCharcoal,
            tertiary = accentHighlight,
            background = PaperWhite,
            onBackground = InkCharcoal,
            surface = Color.White,
            onSurface = InkCharcoal,
            surfaceVariant = Color(0xFFE7EDE9),
            onSurfaceVariant = InkCharcoal,
            outline = Color(0xFFBCC9C2),
            error = DangerRed,
            onError = Color.White,
            errorContainer = DangerRedContainer,
            onErrorContainer = DangerRed
        )
    }

    val solidModernPalette = if (isDark) {
        KagojpotroGlassPalette(
            isDark = true,
            accent = accent,
            onAccent = onAccent,
            accentHighlight = accentHighlight,
            accentSoft = accent.copy(alpha = 0.18f),
            glassCardFill = ObsidianCard,
            glassHeaderFill = ObsidianDark,
            glassSubfolderFrameFill = ObsidianSurface,
            glassStickyDateFill = ObsidianDark,
            inactiveButtonFill = Color(0xFF30363B),
            inactiveButtonBorder = Color(0xFF4A545B),
            textPrimary = Color(0xFFF2F5F4),
            textSecondary = Color(0xFFB4C2BE),
            dividerColor = Color(0xFF333B3F),
            danger = Color(0xFFFF6B6B),
            dangerOnDark = Color(0xFFFF8A80),
            accentGlassBorderBrush = SolidColor(accent.copy(alpha = 0.36f)),
            subtleGlassBorderBrush = SolidColor(Color(0xFF353E42)),
            glassSurfaceGradient = SolidColor(ObsidianCard)
        )
    } else {
        KagojpotroGlassPalette(
            isDark = false,
            accent = accent,
            onAccent = onAccent,
            accentHighlight = accentHighlight,
            accentSoft = accent.copy(alpha = 0.13f),
            glassCardFill = Color.White,
            glassHeaderFill = PaperWhite,
            glassSubfolderFrameFill = PaperSurfaceVariant,
            glassStickyDateFill = PaperWhite,
            inactiveButtonFill = Color(0xFFE7EDE9),
            inactiveButtonBorder = Color(0xFFBCC9C2),
            textPrimary = InkCharcoal,
            textSecondary = InkSlate,
            dividerColor = Color(0xFFDCE3DF),
            danger = DangerRed,
            dangerOnDark = Color(0xFFFF8A80),
            accentGlassBorderBrush = SolidColor(accent.copy(alpha = 0.28f)),
            subtleGlassBorderBrush = SolidColor(Color(0xFFD6DFDA)),
            glassSurfaceGradient = SolidColor(Color.White)
        )
    }

    CompositionLocalProvider(LocalGlassPalette provides solidModernPalette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

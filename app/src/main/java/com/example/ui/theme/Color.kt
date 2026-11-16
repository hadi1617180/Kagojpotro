package com.example.ui.theme

import androidx.compose.ui.graphics.Color

val PaperWhite = Color(0xFFFAF7F2)
val PaperSurfaceVariant = Color(0xFFF2EDE4)
val PaperCardElevated = Color(0xFFFFFCF7)
val PaperBorder = Color(0xFFE2D9C8)

val DeepTeal = Color(0xFF0F8B6D)
val DeepTealDark = Color(0xFF09423E)
val SoftTealContainer = Color(0xFFD4ECE9)
val OnSoftTealContainer = Color(0xFF073532)
val MintHighlight = Color(0xFF4FD8CC)

val InkCharcoal = Color(0xFF1C201E)
val InkSlate = Color(0xFF5A625E)
val InkMuted = Color(0xFF7D8581)

val ObsidianDark = Color(0xFF131314)
val ObsidianSurface = Color(0xFF1B1C1E)
val ObsidianCard = Color(0xFF232528)

val DangerRed = Color(0xFFB3261E)
val DangerRedContainer = Color(0xFFF9DEDC)
val WarningAmber = Color(0xFFB26A00)
val StatusGreen = Color(0xFF10B981)
val StatusRed = Color(0xFFEF4444)

/**
 * The 5 curated preset accent color circles in Settings:
 * 1. Emerald Teal (#0F8B6D)
 * 2. Royal Indigo (#3B5BDB)
 * 3. Crimson Rose (#E03131)
 * 4. Amber Gold (#E67700)
 * 5. Violet Purple (#7950F2)
 */
data class AccentPreset(
    val hex: String,
    val color: Color,
    val nameEn: String,
    val nameBn: String
)

val DefaultAccentPresets = listOf(
    AccentPreset("#0F8B6D", Color(0xFF0F8B6D), "Emerald", "পান্না"),
    AccentPreset("#3B5BDB", Color(0xFF3B5BDB), "Indigo", "নীল"),
    AccentPreset("#E03131", Color(0xFFE03131), "Crimson", "লাল"),
    AccentPreset("#E67700", Color(0xFFE67700), "Amber", "সোনালী"),
    AccentPreset("#7950F2", Color(0xFF7950F2), "Violet", "বেগুনি")
)

fun parseHexColor(hex: String, fallback: Color = DeepTeal): Color {
    return runCatching {
        val cleaned = hex.trim().removePrefix("#")
        when (cleaned.length) {
            6 -> Color(("FF$cleaned").toLong(16))
            8 -> Color(cleaned.toLong(16))
            else -> fallback
        }
    }.getOrDefault(fallback)
}

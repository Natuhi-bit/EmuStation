package com.emustation.emusync.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** PS3 の XMB のように、背景の色を選べる */
data class XmbColors(val name: String, val label: String, val top: Color, val bottom: Color, val accent: Color)

object XmbThemes {
    val all = listOf(
        XmbColors("blue", "青", Color(0xFF0B2A5B), Color(0xFF041227), Color(0xFF6FB6FF)),
        XmbColors("red", "赤", Color(0xFF5B0B14), Color(0xFF22040A), Color(0xFFFF8A8A)),
        XmbColors("green", "緑", Color(0xFF0D4A26), Color(0xFF04190D), Color(0xFF7DE3A2)),
        XmbColors("purple", "紫", Color(0xFF3A1460), Color(0xFF140626), Color(0xFFC79BFF)),
        XmbColors("orange", "橙", Color(0xFF6A3A08), Color(0xFF241302), Color(0xFFFFC27A)),
        XmbColors("black", "黒", Color(0xFF26282D), Color(0xFF07080A), Color(0xFFB9C2D0)),
    )

    fun byName(name: String) = all.firstOrNull { it.name == name } ?: all.first()
}

val LocalXmbColors = staticCompositionLocalOf { XmbThemes.all.first() }

object XmbText {
    val primary = Color(0xFFF4F7FB)
    val secondary = Color(0xFFC9D3E2)
    val dim = Color(0x99DCE4F0)
    val error = Color(0xFFFF9C9C)
    val ok = Color(0xFF9CF0B8)
    val warn = Color(0xFFFFD58A)
}

@Composable
fun EmusyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF9CCBFF),
            onPrimary = Color(0xFF00213F),
            surface = Color(0xE6101722),
            onSurface = XmbText.primary,
            surfaceVariant = Color(0x33FFFFFF),
            onSurfaceVariant = XmbText.secondary,
            outline = Color(0x66FFFFFF),
        ),
        content = content,
    )
}

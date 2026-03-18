package emu.x360.mobile.dev.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RebuildColors = darkColorScheme(
    primary = Color(0xFFF5B942),
    onPrimary = Color(0xFF1D1300),
    secondary = Color(0xFF47C0FF),
    onSecondary = Color(0xFF04131C),
    background = Color(0xFF07121C),
    surface = Color(0xFF102433),
    onSurface = Color(0xFFEAF1F7),
)

@Composable
fun X360RebuildTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = RebuildColors,
        content = content,
    )
}

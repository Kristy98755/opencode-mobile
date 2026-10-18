package app.opencode.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0D1117)
val Surface1 = Color(0xFF161B22)
val Surface2 = Color(0xFF1C2129)
val Surface3 = Color(0xFF262C36)
val Border = Color(0xFF30363D)
val Accent = Color(0xFFE8842A)
val AccentDim = Color(0xFF9E5B1E)
val TextPrimary = Color(0xFFE6EDF3)
val TextSecondary = Color(0xFF8B949E)
val TextMuted = Color(0xFF6E7681)
val Green = Color(0xFF3FB950)
val Red = Color(0xFFF85149)
val Blue = Color(0xFF58A6FF)
val Purple = Color(0xFFBC8CFF)
val Yellow = Color(0xFFD29922)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    primaryContainer = AccentDim,
    onPrimaryContainer = Color.White,
    secondary = Blue,
    onSecondary = Color.Black,
    secondaryContainer = Surface3,
    onSecondaryContainer = TextPrimary,
    tertiary = Purple,
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextSecondary,
    outline = Border,
    error = Red,
    onError = Color.Black,
    errorContainer = Red,
    onErrorContainer = Color.White,
)

@Composable
fun OpenCodeTheme(content: @Composable () -> Unit) {
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = DarkScheme,
        content = content,
    )
}

package io.nekobox.next.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Teal = Color(0xFF0F6E56)
private val TealDark = Color(0xFF8EDEC4)
private val Leaf = Color(0xFF1B7F4E)
private val Ink = Color(0xFF10211C)
private val Paper = Color(0xFFF3F7F4)
private val Mist = Color(0xFFD7E6DF)

private val Light = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F0DC),
    onPrimaryContainer = Ink,
    secondary = Leaf,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC9F2D4),
    background = Paper,
    surface = Paper,
    surfaceContainer = Color.White,
    onBackground = Ink,
    onSurface = Ink,
    outline = Mist,
)

private val Dark = darkColorScheme(
    primary = TealDark,
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF0B5344),
    onPrimaryContainer = Color(0xFFB7F0DC),
    secondary = Color(0xFF8FD9A8),
    background = Color(0xFF0E1613),
    surface = Color(0xFF0E1613),
    surfaceContainer = Color(0xFF17211D),
    onBackground = Color(0xFFE4F3EC),
    onSurface = Color(0xFFE4F3EC),
)

@Composable
fun NekoTheme(dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = if (dynamic && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) Dark else Light
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

package com.agentshell.core.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.agentshell.R

// Shared with the mobile canvas design system (assets/ui/mobile.css).
val Primary = Color(0xFF5151D8)
val Secondary = Color(0xFF5151D8)
val Accent = Color(0xFF147760)
val ErrorColor = Color(0xFFB4233D)
val WarningColor = Color(0xFFB46B15)
val SuccessColor = Color(0xFF147760)
val DarkBackground = Color(0xFF171C27)
val DarkSurface = Color(0xFF171C27)
val DarkCard = Color(0xFF222A39)
val DarkOnBackground = Color(0xFFEFF3FA)
val DarkOnSurface = Color(0xFFEFF3FA)
val LightBackground = Color(0xFFFCFCFD)
val LightSurface = Color(0xFFFCFCFD)
val LightCard = Color(0xFFF1F4F8)
val LightOnBackground = Color(0xFF172133)
val LightOnSurface = Color(0xFF172133)

// Terminal colors
val TerminalBackground = Color(0xFF1E1E1E)
val TerminalForeground = Color(0xFFD4D4D4)
val TerminalCursor = Color(0xFFFFFFFF)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    Font(R.font.jetbrains_mono_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.jetbrains_mono_bold_italic, FontWeight.Bold, FontStyle.Italic),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFB0A9FF), onPrimary = DarkBackground,
    primaryContainer = Color(0xFF353050), onPrimaryContainer = Color(0xFFB0A9FF),
    secondary = Color(0xFFB1BED0), onSecondary = DarkBackground,
    secondaryContainer = DarkCard, onSecondaryContainer = DarkOnSurface,
    tertiary = Color(0xFF69D9B7), onTertiary = DarkBackground,
    tertiaryContainer = Color(0xFF213B36), onTertiaryContainer = Color(0xFF69D9B7),
    error = Color(0xFFFF9DAA), onError = DarkBackground,
    errorContainer = Color(0xFF482A33), onErrorContainer = Color(0xFFFF9DAA),
    background = DarkBackground, onBackground = DarkOnBackground,
    surface = DarkSurface, onSurface = DarkOnSurface,
    surfaceVariant = DarkCard, onSurfaceVariant = Color(0xFFB1BED0),
    surfaceDim = DarkBackground, surfaceBright = DarkCard,
    surfaceContainerLowest = DarkBackground, surfaceContainerLow = DarkCard,
    surfaceContainer = DarkCard, surfaceContainerHigh = DarkCard, surfaceContainerHighest = DarkCard,
    inverseSurface = LightSurface, inverseOnSurface = LightOnSurface, inversePrimary = Primary,
    outline = Color(0xFF748198), outlineVariant = Color(0xFF333E51),
    surfaceTint = Color.Transparent,
)

private val LightColorScheme = lightColorScheme(
    primary = Primary, onPrimary = Color.White,
    primaryContainer = Color(0xFFEAEAFF), onPrimaryContainer = Primary,
    secondary = Color(0xFF57657B), onSecondary = Color.White,
    secondaryContainer = LightCard, onSecondaryContainer = LightOnSurface,
    tertiary = Accent, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE5F3EE), onTertiaryContainer = Accent,
    error = ErrorColor, onError = Color.White,
    errorContainer = Color(0xFFFBE9ED), onErrorContainer = ErrorColor,
    background = LightBackground, onBackground = LightOnBackground,
    surface = LightSurface, onSurface = LightOnSurface,
    surfaceVariant = LightCard, onSurfaceVariant = Color(0xFF57657B),
    surfaceDim = LightCard, surfaceBright = LightBackground,
    surfaceContainerLowest = LightBackground, surfaceContainerLow = LightCard,
    surfaceContainer = LightCard, surfaceContainerHigh = LightCard, surfaceContainerHighest = LightCard,
    inverseSurface = DarkSurface, inverseOnSurface = DarkOnSurface, inversePrimary = Color(0xFFB0A9FF),
    outline = Color(0xFF8490A3), outlineVariant = Color(0xFFE3E8F0),
    surfaceTint = Color.Transparent,
)

private val AppTypography = Typography(
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun AgentShellTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}

package com.example.llmapp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = ChatGPTGreen,
    secondary = PurpleAccent,
    tertiary = LegacyGreen,
    background = DarkBackground,
    surface = DarkBackground,
    surfaceVariant = DarkCardPanel,
    surfaceContainer = DarkInputBox, // We use this for input boxes
    primaryContainer = DarkHoverGray, // User bubble background
    onPrimaryContainer = DarkTextPrimary,
    secondaryContainer = DarkBackground, // Assistant bubble background
    onSecondaryContainer = DarkTextPrimary,
    onPrimary = DarkTextPrimary,
    onSecondary = DarkTextPrimary,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary,
    onSurfaceVariant = DarkTextPrimary,
    outline = DarkBorderGray,
    error = ErrorRed,
    errorContainer = ErrorRed.copy(alpha = 0.2f),
    onErrorContainer = ErrorRed
)

private val LightColorScheme = lightColorScheme(
    primary = ChatGPTGreen,
    secondary = PurpleAccent,
    tertiary = LegacyGreen,
    background = LightBackground,
    surface = LightBackground,
    surfaceVariant = LightCardPanel,
    surfaceContainer = LightInputBox,
    primaryContainer = LightSecondaryBg, // User bubble background
    onPrimaryContainer = LightTextPrimary,
    secondaryContainer = LightBackground, // Assistant bubble background
    onSecondaryContainer = LightTextPrimary,
    onPrimary = LightBackground,
    onSecondary = LightBackground,
    onBackground = LightTextPrimary,
    onSurface = LightTextPrimary,
    onSurfaceVariant = LightTextPrimary,
    outline = LightBorderGray,
    error = ErrorRed,
    errorContainer = ErrorRed.copy(alpha = 0.1f),
    onErrorContainer = ErrorRed
)

@Composable
fun LLMAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Turned off to strictly match ChatGPT
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
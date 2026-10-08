package com.familyrecipebox.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = WarmAmber,
    onPrimary = SurfaceLight,
    primaryContainer = WarmAmberLight,
    onPrimaryContainer = OnSurfaceLight,
    secondary = WarmAmberDark,
    onSecondary = SurfaceLight,
    background = BackgroundLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = MutedLight,
    onSurfaceVariant = MutedForegroundLight,
    outline = BorderLight
)

private val DarkColorScheme = darkColorScheme(
    primary = WarmAmberLight,
    onPrimary = OnSurfaceLight,
    primaryContainer = WarmAmberDark,
    onPrimaryContainer = OnSurfaceDark,
    secondary = WarmAmber,
    onSecondary = OnSurfaceLight,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = MutedDark,
    onSurfaceVariant = MutedForegroundDark,
    outline = BorderDark
)

@Composable
fun RecipeCardsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
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
        typography = AppTypography,
        content = content
    )
}

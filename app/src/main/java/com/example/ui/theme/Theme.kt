package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val OmniDarkColorScheme = darkColorScheme(
    primary = SkyPrimaryDark,
    onPrimary = OnSkyDark,
    primaryContainer = SkyContainerDark,
    onPrimaryContainer = OnSkyContainerDark,
    secondary = SlateSecondaryDark,
    secondaryContainer = SlateContainerDark,
    onSecondaryContainer = OnSlateContainerDark,
    tertiary = MintSignalDark,
    onTertiary = OnMintDark,
    tertiaryContainer = MintContainerDark,
    onTertiaryContainer = OnMintContainerDark,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    error = CoralErrorDark,
    errorContainer = CoralContainerDark,
    onErrorContainer = OnCoralContainerDark
)

private val OmniLightColorScheme = lightColorScheme(
    primary = CobaltPrimaryLight,
    onPrimary = OnCobaltLight,
    primaryContainer = CobaltContainerLight,
    onPrimaryContainer = OnCobaltContainerLight,
    secondary = SlateSecondaryLight,
    secondaryContainer = SlateContainerLight,
    onSecondaryContainer = OnSlateContainerLight,
    tertiary = EmeraldSignalLight,
    onTertiary = OnEmeraldLight,
    tertiaryContainer = EmeraldContainerLight,
    onTertiaryContainer = OnEmeraldContainerLight,
    background = BackgroundLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    error = CrimsonErrorLight,
    errorContainer = CrimsonContainerLight,
    onErrorContainer = OnCrimsonContainerLight
)

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK
}

@Composable
fun OmniCallTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> OmniDarkColorScheme
        else -> OmniLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = OmniCallTypography,
        content = content
    )
}

package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK
}

enum class AppColorPalette(
    val label: String,
    val swatchHex: Color
) {
    SAPPHIRE("Sapphire", CobaltPrimaryLight),
    EMERALD("Emerald", ForestPrimaryLight),
    AMETHYST("Amethyst", AmethystPrimaryLight),
    AMBER("Amber", AmberPrimaryLight),
    RUBY("Ruby", RubyPrimaryLight),
    TEAL("Teal", TealPrimaryLight)
}

private fun buildDarkPaletteScheme(palette: AppColorPalette): ColorScheme {
    return when (palette) {
        AppColorPalette.SAPPHIRE -> darkColorScheme(
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
        AppColorPalette.EMERALD -> darkColorScheme(
            primary = ForestPrimaryDark,
            onPrimary = OnForestDark,
            primaryContainer = ForestContainerDark,
            onPrimaryContainer = OnForestContainerDark,
            secondary = Color(0xFF6EE7B7),
            secondaryContainer = Color(0xFF132A23),
            onSecondaryContainer = Color(0xFFD1FAE5),
            tertiary = MintSignalDark,
            onTertiary = OnMintDark,
            tertiaryContainer = MintContainerDark,
            onTertiaryContainer = OnMintContainerDark,
            background = Color(0xFF071310),
            onBackground = Color(0xFFECFDF5),
            surface = Color(0xFF0C1C18),
            onSurface = Color(0xFFECFDF5),
            surfaceVariant = Color(0xFF142B25),
            onSurfaceVariant = Color(0xFF9CA3AF),
            outline = Color(0xFF28463D),
            error = CoralErrorDark,
            errorContainer = CoralContainerDark,
            onErrorContainer = OnCoralContainerDark
        )
        AppColorPalette.AMETHYST -> darkColorScheme(
            primary = AmethystPrimaryDark,
            onPrimary = OnAmethystDark,
            primaryContainer = AmethystContainerDark,
            onPrimaryContainer = OnAmethystContainerDark,
            secondary = Color(0xFFC4B5FD),
            secondaryContainer = Color(0xFF251B3A),
            onSecondaryContainer = Color(0xFFEDE9FE),
            tertiary = MintSignalDark,
            onTertiary = OnMintDark,
            tertiaryContainer = MintContainerDark,
            onTertiaryContainer = OnMintContainerDark,
            background = Color(0xFF0E0B1A),
            onBackground = Color(0xFFF5F3FF),
            surface = Color(0xFF151126),
            onSurface = Color(0xFFF5F3FF),
            surfaceVariant = Color(0xFF211C38),
            onSurfaceVariant = Color(0xFFA5A0C2),
            outline = Color(0xFF39325C),
            error = CoralErrorDark,
            errorContainer = CoralContainerDark,
            onErrorContainer = OnCoralContainerDark
        )
        AppColorPalette.AMBER -> darkColorScheme(
            primary = AmberPrimaryDark,
            onPrimary = OnAmberDark,
            primaryContainer = AmberContainerDark,
            onPrimaryContainer = OnAmberContainerDark,
            secondary = Color(0xFFFDBA74),
            secondaryContainer = Color(0xFF2E1E14),
            onSecondaryContainer = Color(0xFFFFEDD5),
            tertiary = MintSignalDark,
            onTertiary = OnMintDark,
            tertiaryContainer = MintContainerDark,
            onTertiaryContainer = OnMintContainerDark,
            background = Color(0xFF140D09),
            onBackground = Color(0xFFFFF7ED),
            surface = Color(0xFF1D140E),
            onSurface = Color(0xFFFFF7ED),
            surfaceVariant = Color(0xFF2C1F17),
            onSurfaceVariant = Color(0xFFC2A998),
            outline = Color(0xFF4A3427),
            error = CoralErrorDark,
            errorContainer = CoralContainerDark,
            onErrorContainer = OnCoralContainerDark
        )
        AppColorPalette.RUBY -> darkColorScheme(
            primary = RubyPrimaryDark,
            onPrimary = OnRubyDark,
            primaryContainer = RubyContainerDark,
            onPrimaryContainer = OnRubyContainerDark,
            secondary = Color(0xFFFDA4AF),
            secondaryContainer = Color(0xFF32161E),
            onSecondaryContainer = Color(0xFFFFE4E6),
            tertiary = MintSignalDark,
            onTertiary = OnMintDark,
            tertiaryContainer = MintContainerDark,
            onTertiaryContainer = OnMintContainerDark,
            background = Color(0xFF150A0E),
            onBackground = Color(0xFFFFF1F2),
            surface = Color(0xFF1F1016),
            onSurface = Color(0xFFFFF1F2),
            surfaceVariant = Color(0xFF2F1922),
            onSurfaceVariant = Color(0xFFC7A3AC),
            outline = Color(0xFF4D2936),
            error = CoralErrorDark,
            errorContainer = CoralContainerDark,
            onErrorContainer = OnCoralContainerDark
        )
        AppColorPalette.TEAL -> darkColorScheme(
            primary = TealPrimaryDark,
            onPrimary = OnTealDark,
            primaryContainer = TealContainerDark,
            onPrimaryContainer = OnTealContainerDark,
            secondary = Color(0xFF5EEAD4),
            secondaryContainer = Color(0xFF122B2B),
            onSecondaryContainer = Color(0xFFCCFBF1),
            tertiary = MintSignalDark,
            onTertiary = OnMintDark,
            tertiaryContainer = MintContainerDark,
            onTertiaryContainer = OnMintContainerDark,
            background = Color(0xFF071314),
            onBackground = Color(0xFFF0FDFA),
            surface = Color(0xFF0D1D1F),
            onSurface = Color(0xFFF0FDFA),
            surfaceVariant = Color(0xFF152C2F),
            onSurfaceVariant = Color(0xFF94B5B8),
            outline = Color(0xFF25484C),
            error = CoralErrorDark,
            errorContainer = CoralContainerDark,
            onErrorContainer = OnCoralContainerDark
        )
    }
}

private fun buildLightPaletteScheme(palette: AppColorPalette): ColorScheme {
    return when (palette) {
        AppColorPalette.SAPPHIRE -> lightColorScheme(
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
        AppColorPalette.EMERALD -> lightColorScheme(
            primary = ForestPrimaryLight,
            onPrimary = Color.White,
            primaryContainer = ForestContainerLight,
            onPrimaryContainer = OnForestContainerLight,
            secondary = Color(0xFF335C4F),
            secondaryContainer = Color(0xFFE0F2EC),
            onSecondaryContainer = Color(0xFF0F2E24),
            tertiary = EmeraldSignalLight,
            onTertiary = Color.White,
            tertiaryContainer = EmeraldContainerLight,
            onTertiaryContainer = OnEmeraldContainerLight,
            background = Color(0xFFF6FBF9),
            onBackground = OnSurfaceLight,
            surface = Color.White,
            onSurface = OnSurfaceLight,
            surfaceVariant = Color(0xFFE9F3EF),
            onSurfaceVariant = Color(0xFF415A52),
            outline = Color(0xFFBBD3CA),
            error = CrimsonErrorLight,
            errorContainer = CrimsonContainerLight,
            onErrorContainer = OnCrimsonContainerLight
        )
        AppColorPalette.AMETHYST -> lightColorScheme(
            primary = AmethystPrimaryLight,
            onPrimary = Color.White,
            primaryContainer = AmethystContainerLight,
            onPrimaryContainer = OnAmethystContainerLight,
            secondary = Color(0xFF564A7A),
            secondaryContainer = Color(0xFFEBE6FA),
            onSecondaryContainer = Color(0xFF231942),
            tertiary = EmeraldSignalLight,
            onTertiary = Color.White,
            tertiaryContainer = EmeraldContainerLight,
            onTertiaryContainer = OnEmeraldContainerLight,
            background = Color(0xFFFAF8FF),
            onBackground = OnSurfaceLight,
            surface = Color.White,
            onSurface = OnSurfaceLight,
            surfaceVariant = Color(0xFFF0ECFA),
            onSurfaceVariant = Color(0xFF52496B),
            outline = Color(0xFFD2C9EB),
            error = CrimsonErrorLight,
            errorContainer = CrimsonContainerLight,
            onErrorContainer = OnCrimsonContainerLight
        )
        AppColorPalette.AMBER -> lightColorScheme(
            primary = AmberPrimaryLight,
            onPrimary = Color.White,
            primaryContainer = AmberContainerLight,
            onPrimaryContainer = OnAmberContainerLight,
            secondary = Color(0xFF6B4C3B),
            secondaryContainer = Color(0xFFF9EAE0),
            onSecondaryContainer = Color(0xFF331D12),
            tertiary = EmeraldSignalLight,
            onTertiary = Color.White,
            tertiaryContainer = EmeraldContainerLight,
            onTertiaryContainer = OnEmeraldContainerLight,
            background = Color(0xFFFFFBF7),
            onBackground = OnSurfaceLight,
            surface = Color.White,
            onSurface = OnSurfaceLight,
            surfaceVariant = Color(0xFFF7ECE3),
            onSurfaceVariant = Color(0xFF634C3E),
            outline = Color(0xFFE3C8B6),
            error = CrimsonErrorLight,
            errorContainer = CrimsonContainerLight,
            onErrorContainer = OnCrimsonContainerLight
        )
        AppColorPalette.RUBY -> lightColorScheme(
            primary = RubyPrimaryLight,
            onPrimary = Color.White,
            primaryContainer = RubyContainerLight,
            onPrimaryContainer = OnRubyContainerLight,
            secondary = Color(0xFF6B444E),
            secondaryContainer = Color(0xFFFAE6EA),
            onSecondaryContainer = Color(0xFF38161E),
            tertiary = EmeraldSignalLight,
            onTertiary = Color.White,
            tertiaryContainer = EmeraldContainerLight,
            onTertiaryContainer = OnEmeraldContainerLight,
            background = Color(0xFFFFF9FA),
            onBackground = OnSurfaceLight,
            surface = Color.White,
            onSurface = OnSurfaceLight,
            surfaceVariant = Color(0xFFF8EBF0),
            onSurfaceVariant = Color(0xFF66454E),
            outline = Color(0xFFE5C4CD),
            error = CrimsonErrorLight,
            errorContainer = CrimsonContainerLight,
            onErrorContainer = OnCrimsonContainerLight
        )
        AppColorPalette.TEAL -> lightColorScheme(
            primary = TealPrimaryLight,
            onPrimary = Color.White,
            primaryContainer = TealContainerLight,
            onPrimaryContainer = OnTealContainerLight,
            secondary = Color(0xFF3B5A5E),
            secondaryContainer = Color(0xFFE1F2F4),
            onSecondaryContainer = Color(0xFF132E31),
            tertiary = EmeraldSignalLight,
            onTertiary = Color.White,
            tertiaryContainer = EmeraldContainerLight,
            onTertiaryContainer = OnEmeraldContainerLight,
            background = Color(0xFFF6FCFC),
            onBackground = OnSurfaceLight,
            surface = Color.White,
            onSurface = OnSurfaceLight,
            surfaceVariant = Color(0xFFE8F4F5),
            onSurfaceVariant = Color(0xFF3F595C),
            outline = Color(0xFFB9D5D8),
            error = CrimsonErrorLight,
            errorContainer = CrimsonContainerLight,
            onErrorContainer = OnCrimsonContainerLight
        )
    }
}

@Composable
fun OmniCallTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    colorPalette: AppColorPalette = AppColorPalette.SAPPHIRE,
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
        darkTheme -> buildDarkPaletteScheme(colorPalette)
        else -> buildLightPaletteScheme(colorPalette)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = OmniCallTypography,
        content = content
    )
}

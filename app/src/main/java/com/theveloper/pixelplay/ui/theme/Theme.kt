package com.theveloper.pixelplay.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.theveloper.pixelplay.presentation.viewmodel.ColorSchemePair
import com.theveloper.pixelplay.data.preferences.SavedThemePalette
import androidx.core.graphics.ColorUtils

val LocalPixelPlayDarkTheme = staticCompositionLocalOf { false }
val LocalShowScrollbar = staticCompositionLocalOf { true }
// Exposed so individual screens can react to TUI mode later (bracket-style
// icons, dashed borders, ASCII art, etc.) without every screen needing its
// own DataStore read — this is the single source of truth for "are we
// currently in TUI mode" anywhere in the composition tree.
val LocalIsTuiTheme = staticCompositionLocalOf { false }

// Pure black OLED scheme — true #000000 background/surface (not a dark gray),
// high-contrast white/green text, minimal accent color. No dynamic color, no
// album-art-derived color: TUI mode is deliberately uniform regardless of
// what's playing, matching the monochrome terminal aesthetic.
val TuiColorScheme = darkColorScheme(
    primary = Color(0xFF00FF41),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFF00FF41),
    onSecondary = Color(0xFF000000),
    tertiary = Color(0xFFFF3B30),
    onTertiary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF0A0A0A),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF2A2A2A),
    surfaceTint = Color(0xFF000000),
    error = Color(0xFFFF3B30),
    onError = Color(0xFF000000)
)

// Sharp, zero-radius corners everywhere — no rounded cards, no rounded
// buttons. This is what gives TUI mode its bracket/terminal-window look even
// before any screen gets bespoke ASCII-style components.
val TuiShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
)

private val TuiTypography = Typography.copy(
    displayLarge = Typography.displayLarge.copy(fontFamily = FontFamily.Monospace),
    displayMedium = Typography.displayMedium.copy(fontFamily = FontFamily.Monospace),
    displaySmall = Typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
    headlineLarge = Typography.headlineLarge.copy(fontFamily = FontFamily.Monospace),
    headlineMedium = Typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
    headlineSmall = Typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
    titleLarge = Typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
    titleMedium = Typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
    titleSmall = Typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
    bodyLarge = Typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
    bodyMedium = Typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    bodySmall = Typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
    labelLarge = Typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
    labelMedium = Typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
    labelSmall = Typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Suppress("DEPRECATION")
@Composable
fun PixelPlayStatusBarStyle(
    color: Color,
    useDarkIcons: Boolean = ColorUtils.calculateLuminance(color.toArgb()) > 0.55,
    navigationColor: Color? = null,
    useDarkNavigationIcons: Boolean = navigationColor
        ?.let { ColorUtils.calculateLuminance(it.toArgb()) > 0.55 }
        ?: useDarkIcons
) {
    val view = LocalView.current
    if (view.isInEditMode) return

    val updateNavigationBar = navigationColor != null
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
        }

        WindowCompat.getInsetsController(window, view).run {
            isAppearanceLightStatusBars = useDarkIcons

            if (updateNavigationBar) {
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                isAppearanceLightNavigationBars = useDarkNavigationIcons
            }
        }
    }
}

val DarkColorScheme = darkColorScheme(
    primary = PixelPlayPurplePrimary,
    secondary = PixelPlayPink,
    tertiary = PixelPlayOrange,
    background = PixelPlayPurpleDark,
    surface = PixelPlaySurface,
    onPrimary = PixelPlayWhite,
    onSecondary = PixelPlayWhite,
    onTertiary = PixelPlayWhite,
    onBackground = PixelPlayWhite,
    onSurface = PixelPlayLightPurple, // Texto sobre superficies
    error = Color(0xFFFF5252),
    onError = PixelPlayWhite
)

val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = PixelPlayWhite,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = PixelPlayPink,
    onSecondary = PixelPlayWhite,
    secondaryContainer = PixelPlayPink.copy(alpha = 0.15f),
    onSecondaryContainer = PixelPlayPink.copy(alpha = 0.85f),
    tertiary = PixelPlayOrange,
    onTertiary = PixelPlayBlack,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutline.copy(alpha = 0.6f),
    surfaceTint = LightPrimary,
    error = Color(0xFFD32F2F),
    onError = PixelPlayWhite
)

@Composable
fun PixelPlayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colorSchemePairOverride: ColorSchemePair? = null,
    isTuiTheme: Boolean = false,
    activePalette: SavedThemePalette? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val baseColorScheme = when {
        isTuiTheme -> TuiColorScheme
        colorSchemePairOverride == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            // Tema dinámico del sistema como prioridad si no hay override
            try {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } catch (e: Exception) {
                // Fallback a los defaults si dynamic colors falla (raro, pero posible en algunos dispositivos)
                if (darkTheme) DarkColorScheme else LightColorScheme
            }
        }
        colorSchemePairOverride != null -> {
            // Usar el esquema del álbum si se proporciona
            if (darkTheme) colorSchemePairOverride.dark else colorSchemePairOverride.light
        }
        // Fallback final a los defaults si no hay override ni dynamic colors aplicables
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    // A saved palette only applies on top of the Default theme, never over TUI
    // (TUI is deliberately fixed/monochrome) and never over the album-art
    // dynamic scheme (that's a different, existing customization axis).
    val finalColorScheme = if (activePalette != null && !isTuiTheme && colorSchemePairOverride == null) {
        val accent = Color(activePalette.primaryColorArgb.toInt())
        baseColorScheme.copy(
            primary = accent,
            secondary = accent,
            surfaceTint = accent,
            background = if (activePalette.oledBlack) Color(0xFF000000) else baseColorScheme.background,
            surface = if (activePalette.oledBlack) Color(0xFF000000) else baseColorScheme.surface
        )
    } else {
        baseColorScheme
    }

    PixelPlayStatusBarStyle(
        color = finalColorScheme.background,
        navigationColor = finalColorScheme.background
    )

    CompositionLocalProvider(
        LocalPixelPlayDarkTheme provides darkTheme,
        LocalIsTuiTheme provides isTuiTheme
    ) {
        MaterialTheme(
            colorScheme = finalColorScheme,
            typography = if (isTuiTheme) TuiTypography else Typography,
            shapes = if (isTuiTheme) TuiShapes else Shapes,
            content = content
        )
    }
}

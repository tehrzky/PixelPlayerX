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
import androidx.compose.ui.graphics.luminance
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
// Replaces the old LocalIsTuiTheme boolean. TUI is no longer a special case —
// it's just BuiltInThemes.TUI, one instance of ThemeDefinition — so any
// screen that wants to react to the active theme (icon style, border style,
// corner radius, font) reads this instead of asking "is this TUI or not".
val LocalThemeDefinition = staticCompositionLocalOf { BuiltInThemes.DEFAULT }

private fun monospaceTypography() = Typography.copy(
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

/** Builds a Material3 Shapes object from a theme's single cornerRadius value
 * — themes describe "how rounded", not five separate radii, since no theme
 * so far has needed per-size-tier control. */
private fun ThemeShape.toMaterialShapes() = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius),
    small = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius),
    large = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius)
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
    themeDefinition: ThemeDefinition = BuiltInThemes.DEFAULT,
    activePalette: SavedThemePalette? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val baseColorScheme = when {
        themeDefinition.useFixedColors -> darkColorScheme(
            primary = themeDefinition.colors.accent,
            onPrimary = if (themeDefinition.colors.accent.luminance() > 0.5f) Color(0xFF000000) else Color(0xFFFFFFFF),
            secondary = themeDefinition.colors.accent,
            onSecondary = if (themeDefinition.colors.accent.luminance() > 0.5f) Color(0xFF000000) else Color(0xFFFFFFFF),
            tertiary = themeDefinition.colors.button,
            onTertiary = if (themeDefinition.colors.button.luminance() > 0.5f) Color(0xFF000000) else Color(0xFFFFFFFF),
            // These 3 pairs were missing entirely before — the Mini Player and
            // full Player screen's background is driven by primaryContainer,
            // not primary, so without this the whole player area silently
            // never responded to TUI or a saved palette at all.
            primaryContainer = themeDefinition.colors.surface,
            onPrimaryContainer = themeDefinition.colors.text,
            secondaryContainer = themeDefinition.colors.surface,
            onSecondaryContainer = themeDefinition.colors.text,
            tertiaryContainer = themeDefinition.colors.surface,
            onTertiaryContainer = themeDefinition.colors.text,
            background = themeDefinition.colors.background,
            onBackground = themeDefinition.colors.text,
            surface = themeDefinition.colors.surface,
            onSurface = themeDefinition.colors.text,
            surfaceVariant = themeDefinition.colors.surface,
            onSurfaceVariant = themeDefinition.colors.text.copy(alpha = 0.75f),
            outline = themeDefinition.colors.text.copy(alpha = 0.3f),
            outlineVariant = themeDefinition.colors.text.copy(alpha = 0.2f),
            surfaceTint = themeDefinition.colors.accent,
            error = Color(0xFFFF3B30),
            onError = Color(0xFF000000)
        )
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
    // A saved palette only applies on top of a theme that isn't using fixed
    // colors (TUI is deliberately fixed/monochrome) and never over the
    // album-art dynamic scheme (that's a different, existing customization axis).
    val finalColorScheme = if (activePalette != null && !themeDefinition.useFixedColors && colorSchemePairOverride == null) {
        val accent = Color(activePalette.accentColorArgb.toInt())
        val background = Color(activePalette.backgroundColorArgb.toInt())
        val surface = Color(activePalette.surfaceColorArgb.toInt())
        val button = Color(activePalette.buttonColorArgb.toInt())
        val text = Color(activePalette.textColorArgb.toInt())
        // "onX" colors need to stay readable against the color they sit on top
        // of — rather than trusting the user picked a readable pair, derive
        // on-colors by luminance so text/icons never disappear against a
        // custom background.
        fun onColorFor(bg: Color): Color =
            if (bg.luminance() > 0.5f) Color(0xFF000000) else Color(0xFFFFFFFF)

        baseColorScheme.copy(
            primary = accent,
            secondary = accent,
            onPrimary = onColorFor(accent),
            onSecondary = onColorFor(accent),
            surfaceTint = accent,
            background = background,
            onBackground = text,
            surface = surface,
            onSurface = text,
            surfaceVariant = surface,
            onSurfaceVariant = text.copy(alpha = 0.75f),
            surfaceContainer = surface,
            surfaceContainerLow = surface,
            surfaceContainerHigh = surface,
            surfaceContainerHighest = surface,
            surfaceContainerLowest = surface,
            tertiary = button,
            onTertiary = onColorFor(button),
            // Same missing-role fix as the TUI branch above — the saved
            // custom palette on Default was hitting the same silent gap.
            primaryContainer = surface,
            onPrimaryContainer = text,
            secondaryContainer = surface,
            onSecondaryContainer = text,
            tertiaryContainer = surface,
            onTertiaryContainer = text
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
        LocalThemeDefinition provides themeDefinition
    ) {
        MaterialTheme(
            colorScheme = finalColorScheme,
            typography = if (themeDefinition.fontStyle == ThemeFontStyle.MONOSPACE) monospaceTypography() else Typography,
            shapes = themeDefinition.shape.toMaterialShapes(),
            content = content
        )
    }
}

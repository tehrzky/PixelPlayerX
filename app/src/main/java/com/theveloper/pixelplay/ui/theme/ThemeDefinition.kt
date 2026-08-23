package com.theveloper.pixelplay.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How text should render. String-identifier-shaped on purpose (not a plain
 * enum) so a future JSON-uploaded theme can specify "monospace" without the
 * app needing a new build for every possible font choice — MATERIAL/MONOSPACE
 * map to real FontFamily objects the app ships with; CUSTOM is reserved for
 * a later phase where a theme package can bundle its own font file. */
enum class ThemeFontStyle { DEFAULT, MONOSPACE }

/** How container borders render. NONE/SOLID behave like normal Material
 * components. DASHED is what gives TUI its terminal-window look and is the
 * concrete reason ThemedCard exists — Material's Card has no concept of a
 * dashed stroke, so a theme that wants one needs a real drawing path for it,
 * not just a color swap. */
enum class ThemeBorderStyle { NONE, SOLID, DASHED }

/** How semantic icon slots (play, pause, shuffle, favorite, etc.) render.
 * MATERIAL uses the app's existing Material icon set. BRACKET_TEXT renders a
 * short bracketed label instead — "[PLAY]", "[>]" — which is what the
 * screenshot-driven TUI look actually needs; swapping a color or font can't
 * produce that, the icon itself has to be a different kind of thing. */
enum class ThemeIconStyle { MATERIAL, BRACKET_TEXT }

/** The 5 user-customizable color roles, same set the Custom Themes screen's
 * color pickers already edit — this is just their home in the schema now,
 * not a new concept. */
data class ThemeColors(
    val accent: Color,
    val background: Color,
    val surface: Color,
    val button: Color,
    val text: Color
)

data class ThemeShape(
    val cornerRadius: Dp,
    val borderStyle: ThemeBorderStyle,
    val borderWidth: Dp = 1.dp
)

/** A complete, self-contained visual style. This is the unit both built-in
 * presets AND future user-uploaded themes are expressed as — TUI is not
 * special-cased Kotlin anymore, it's just one instance of this same data
 * shape (see BuiltInThemes.TUI below). A JSON-uploaded theme will eventually
 * decode into exactly this structure. */
data class ThemeDefinition(
    val id: String,
    val name: String,
    val colors: ThemeColors,
    val fontStyle: ThemeFontStyle,
    val shape: ThemeShape,
    val iconStyle: ThemeIconStyle,
    // When false, `colors` above is a fallback only — PixelPlayTheme still
    // prefers dynamic system color / album-art color / a saved custom
    // palette, same as today's Default theme behavior. TUI sets this true
    // because it's deliberately fixed and monochrome regardless of context.
    val useFixedColors: Boolean
)

object BuiltInThemes {
    val DEFAULT = ThemeDefinition(
        id = "default",
        name = "Default",
        colors = ThemeColors(
            accent = Color(0xFFBB86FC),
            background = Color(0xFF1B1B1B),
            surface = Color(0xFF2A2A2A),
            button = Color(0xFFBB86FC),
            text = Color(0xFFEEEEEE)
        ),
        fontStyle = ThemeFontStyle.DEFAULT,
        shape = ThemeShape(cornerRadius = 16.dp, borderStyle = ThemeBorderStyle.NONE),
        iconStyle = ThemeIconStyle.MATERIAL,
        useFixedColors = false
    )

    val TUI = ThemeDefinition(
        id = "tui_oled",
        name = "TUI / ASCII (OLED Black)",
        colors = ThemeColors(
            accent = Color(0xFF00FF41),
            background = Color(0xFF000000),
            surface = Color(0xFF000000),
            button = Color(0xFF00FF41),
            text = Color(0xFFE0E0E0)
        ),
        fontStyle = ThemeFontStyle.MONOSPACE,
        shape = ThemeShape(cornerRadius = 0.dp, borderStyle = ThemeBorderStyle.DASHED, borderWidth = 1.dp),
        iconStyle = ThemeIconStyle.BRACKET_TEXT,
        useFixedColors = true
    )

    val ALL = listOf(DEFAULT, TUI)
}

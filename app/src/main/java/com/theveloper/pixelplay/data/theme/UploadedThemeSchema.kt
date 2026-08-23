package com.theveloper.pixelplay.data.theme

import com.theveloper.pixelplay.ui.theme.ThemeBorderStyle
import com.theveloper.pixelplay.ui.theme.ThemeColors
import com.theveloper.pixelplay.ui.theme.ThemeDefinition
import com.theveloper.pixelplay.ui.theme.ThemeFontStyle
import com.theveloper.pixelplay.ui.theme.ThemeIconStyle
import com.theveloper.pixelplay.ui.theme.ThemeShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable

/** The literal JSON shape a user (or theme author) writes and uploads.
 * Deliberately plain — Long ARGB colors and string enum values, not Compose
 * types — so it's hand-writable in a text editor, exactly like this app's
 * existing plugin JSON schema. This is the ONE file that changes if the
 * uploadable schema ever needs new fields; everything downstream converts
 * through toThemeDefinition() into the same ThemeDefinition every built-in
 * preset already uses, so nothing else needs to know an uploaded theme is
 * any different from a built-in one. */
@Serializable
data class UploadedThemeSchema(
    val id: String,
    val name: String,
    val accentColorArgb: Long,
    val backgroundColorArgb: Long,
    val surfaceColorArgb: Long,
    val buttonColorArgb: Long,
    val textColorArgb: Long,
    // "default" | "monospace"
    val fontStyle: String = "default",
    val cornerRadiusDp: Float = 16f,
    // "none" | "solid" | "dashed"
    val borderStyle: String = "none",
    val borderWidthDp: Float = 1f,
    // "material" | "bracket_text"
    val iconStyle: String = "material",
    // Uploaded themes are fixed-color by definition — they wouldn't make
    // sense blending with dynamic system color or album-art color, so this
    // isn't exposed as a field the author controls; it's always true.
) {
    fun toThemeDefinition(): ThemeDefinition = ThemeDefinition(
        id = id,
        name = name,
        colors = ThemeColors(
            accent = Color(accentColorArgb.toInt()),
            background = Color(backgroundColorArgb.toInt()),
            surface = Color(surfaceColorArgb.toInt()),
            button = Color(buttonColorArgb.toInt()),
            text = Color(textColorArgb.toInt())
        ),
        fontStyle = if (fontStyle.equals("monospace", ignoreCase = true)) ThemeFontStyle.MONOSPACE else ThemeFontStyle.DEFAULT,
        shape = ThemeShape(
            cornerRadius = cornerRadiusDp.dp,
            borderStyle = when (borderStyle.lowercase()) {
                "solid" -> ThemeBorderStyle.SOLID
                "dashed" -> ThemeBorderStyle.DASHED
                else -> ThemeBorderStyle.NONE
            },
            borderWidth = borderWidthDp.dp
        ),
        iconStyle = if (iconStyle.equals("bracket_text", ignoreCase = true)) ThemeIconStyle.BRACKET_TEXT else ThemeIconStyle.MATERIAL,
        useFixedColors = true
    )
}

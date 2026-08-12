package com.theveloper.pixelplay.data.preferences

import kotlinx.serialization.Serializable

/** A user-saved full color scheme for the Default theme. Five independently
 * customizable roles, matching what a Material3 app actually needs to look
 * meaningfully different: accent (interactive controls), background (app
 * backdrop), surface (cards/modals/containers), button fill (distinct third
 * accent), and text. Pure black OLED is no longer a separate toggle — it's
 * just the user picking #000000 for backgroundColorArgb directly, which is
 * simpler and more flexible than a special-cased boolean.
 *
 * NOTE: this replaces the earlier 2-field version (primaryColorArgb +
 * oledBlack). Any previously-saved test palettes won't deserialize under this
 * schema and will need to be re-saved. */
@Serializable
data class SavedThemePalette(
    val id: String,
    val name: String,
    val accentColorArgb: Long,
    val backgroundColorArgb: Long,
    val surfaceColorArgb: Long,
    val buttonColorArgb: Long,
    val textColorArgb: Long
)
/** JSON schema for user-imported / exported themes. Supports both Default
 * 5-color palettes and TUI phosphor presets. */
@Serializable
data class UserThemeImportSchema(
    val schemaVersion: Int = 1,
    val name: String,
    val mode: String = CustomThemeMode.DEFAULT,
    val accentColorArgb: Long? = null,
    val backgroundColorArgb: Long? = null,
    val surfaceColorArgb: Long? = null,
    val buttonColorArgb: Long? = null,
    val textColorArgb: Long? = null
) {
    fun toSavedThemePalette(): SavedThemePalette? {
        if (CustomThemeMode.isTui(mode)) return null
        return SavedThemePalette(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            accentColorArgb = accentColorArgb ?: 0xFFBB86FC,
            backgroundColorArgb = backgroundColorArgb ?: 0xFF1B1B1B,
            surfaceColorArgb = surfaceColorArgb ?: 0xFF2A2A2A,
            buttonColorArgb = buttonColorArgb ?: 0xFFBB86FC,
            textColorArgb = textColorArgb ?: 0xFFFFFFFF
        )
    }
}

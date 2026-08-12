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

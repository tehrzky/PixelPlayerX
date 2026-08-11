package com.theveloper.pixelplay.data.preferences

import kotlinx.serialization.Serializable

/** A user-saved accent color + OLED-black combination for the Default theme.
 * Deliberately minimal for v1 (color + one toggle) — this is the seed of the
 * larger theme-definition schema described in the roadmap discussion, but
 * kept small and shippable now rather than guessing at the full shape of
 * that system ahead of building it. */
@Serializable
data class SavedThemePalette(
    val id: String,
    val name: String,
    val primaryColorArgb: Long,
    val oledBlack: Boolean
)

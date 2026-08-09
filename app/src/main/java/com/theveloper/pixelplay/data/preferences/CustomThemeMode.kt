package com.theveloper.pixelplay.data.preferences

/** Custom Theme presets. Separate from AppThemeMode (light/dark/system) — this
 * is a full visual-style override layered on top. DEFAULT means "use the
 * normal PixelPlay look" (dynamic color / light / dark as already governed by
 * AppThemeMode). Future presets and eventually user-uploaded themes extend
 * this same list — v1 ships built-in presets only. */
object CustomThemeMode {
    const val DEFAULT = "default"
    const val TUI_OLED = "tui_oled"
}

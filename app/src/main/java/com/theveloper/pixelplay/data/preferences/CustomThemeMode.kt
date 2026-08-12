package com.theveloper.pixelplay.data.preferences

/** Custom Theme presets. Separate from AppThemeMode (light/dark/system) — this
 * is a full visual-style override layered on top. DEFAULT means "use the
 * normal PixelPlay look" (dynamic color / light / dark as already governed by
 * AppThemeMode). Future presets and eventually user-uploaded themes extend
 * this same list — v1 ships built-in presets only. */
object CustomThemeMode {
    const val DEFAULT = "default"
    const val TUI_OLED = "tui_oled"   // legacy alias, maps to green
    const val TUI_GREEN = "tui_green"
    const val TUI_AMBER = "tui_amber"
    const val TUI_WHITE = "tui_white"
    const val TUI_BLUE = "tui_blue"
    const val TUI_CYAN = "tui_cyan"

    fun isTui(mode: String): Boolean = mode != DEFAULT
}

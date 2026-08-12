package com.theveloper.pixelplay.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemePreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private object Keys {
        val PLAYER_THEME_PREFERENCE = stringPreferencesKey("player_theme_preference_v2")
        val ALBUM_ART_PALETTE_STYLE = stringPreferencesKey("album_art_palette_style_v1")
        val ALBUM_ART_COLOR_ACCURACY = intPreferencesKey("album_art_color_accuracy_v1")
        val APP_THEME_MODE = stringPreferencesKey("app_theme_mode")
        val CUSTOM_THEME_MODE = stringPreferencesKey("custom_theme_mode")
        val SAVED_PALETTES = stringPreferencesKey("saved_theme_palettes")
        val ACTIVE_PALETTE_ID = stringPreferencesKey("active_theme_palette_id")
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun parsePalettes(raw: String?): List<SavedThemePalette> =
        try { json.decodeFromString<List<SavedThemePalette>>(raw ?: "[]") } catch (e: Exception) { emptyList() }

    val appThemeModeFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[Keys.APP_THEME_MODE] ?: AppThemeMode.FOLLOW_SYSTEM
    }

    val customThemeModeFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[Keys.CUSTOM_THEME_MODE] ?: CustomThemeMode.DEFAULT
    }

    val savedPalettesFlow: Flow<List<SavedThemePalette>> = dataStore.data.map { preferences ->
        parsePalettes(preferences[Keys.SAVED_PALETTES])
    }

    // Null = plain Default theme, no accent override applied.
    val activePaletteIdFlow: Flow<String?> = dataStore.data.map { preferences ->
        preferences[Keys.ACTIVE_PALETTE_ID]
    }

    val playerThemePreferenceFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[Keys.PLAYER_THEME_PREFERENCE] ?: ThemePreference.ALBUM_ART
    }

    val albumArtPaletteStyleFlow: Flow<AlbumArtPaletteStyle> = dataStore.data.map { preferences ->
        AlbumArtPaletteStyle.fromStorageKey(preferences[Keys.ALBUM_ART_PALETTE_STYLE])
    }

    val albumArtColorAccuracyFlow: Flow<Int> = dataStore.data.map { preferences ->
        AlbumArtColorAccuracy.clamp(preferences[Keys.ALBUM_ART_COLOR_ACCURACY] ?: AlbumArtColorAccuracy.DEFAULT)
    }

    suspend fun setPlayerThemePreference(themeMode: String) =
        dataStore.edit { preferences ->
            preferences[Keys.PLAYER_THEME_PREFERENCE] = themeMode
        }

    suspend fun setAppThemeMode(themeMode: String) =
        dataStore.edit { preferences ->
            preferences[Keys.APP_THEME_MODE] = themeMode
        }

    suspend fun setCustomThemeMode(mode: String) =
        dataStore.edit { preferences ->
            preferences[Keys.CUSTOM_THEME_MODE] = mode
        }

    /** Saves a new named 5-color palette and makes it active in one atomic write. */
    suspend fun saveAndActivatePalette(
        name: String,
        accentColorArgb: Long,
        backgroundColorArgb: Long,
        surfaceColorArgb: Long,
        buttonColorArgb: Long,
        textColorArgb: Long
    ): SavedThemePalette {
        val palette = SavedThemePalette(
            id = UUID.randomUUID().toString(),
            name = name,
            accentColorArgb = accentColorArgb,
            backgroundColorArgb = backgroundColorArgb,
            surfaceColorArgb = surfaceColorArgb,
            buttonColorArgb = buttonColorArgb,
            textColorArgb = textColorArgb
        )
        dataStore.edit { preferences ->
            val current = parsePalettes(preferences[Keys.SAVED_PALETTES])
            preferences[Keys.SAVED_PALETTES] = json.encodeToString(current + palette)
            preferences[Keys.ACTIVE_PALETTE_ID] = palette.id
        }
        return palette
    }

    suspend fun deletePalette(id: String) {
        dataStore.edit { preferences ->
            val current = parsePalettes(preferences[Keys.SAVED_PALETTES])
            preferences[Keys.SAVED_PALETTES] = json.encodeToString(current.filterNot { it.id == id })
            if (preferences[Keys.ACTIVE_PALETTE_ID] == id) preferences.remove(Keys.ACTIVE_PALETTE_ID)
        }
    }

    suspend fun setActivePaletteId(id: String?) {
        dataStore.edit { preferences ->
            if (id == null) preferences.remove(Keys.ACTIVE_PALETTE_ID) else preferences[Keys.ACTIVE_PALETTE_ID] = id
        }
    }

    suspend fun initializeAppThemeMode(themeMode: String) =
        dataStore.edit { preferences ->
            if (preferences[Keys.APP_THEME_MODE] == null) {
                preferences[Keys.APP_THEME_MODE] = themeMode
            }
        }

    suspend fun setAlbumArtPaletteStyle(style: AlbumArtPaletteStyle) =
        dataStore.edit { preferences ->
            preferences[Keys.ALBUM_ART_PALETTE_STYLE] = style.storageKey
        }

    suspend fun setAlbumArtColorAccuracy(level: Int) =
        dataStore.edit { preferences ->
            preferences[Keys.ALBUM_ART_COLOR_ACCURACY] = AlbumArtColorAccuracy.clamp(level)
        }

    suspend fun setAlbumArtPaletteSettings(
        style: AlbumArtPaletteStyle,
        accuracyLevel: Int
    ) = dataStore.edit { preferences ->
        preferences[Keys.ALBUM_ART_PALETTE_STYLE] = style.storageKey
        preferences[Keys.ALBUM_ART_COLOR_ACCURACY] = AlbumArtColorAccuracy.clamp(accuracyLevel)
    }
}

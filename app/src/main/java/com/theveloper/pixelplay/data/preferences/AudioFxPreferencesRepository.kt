package com.theveloper.pixelplay.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioFxPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private object Keys {
        val LOFI_ENABLED = booleanPreferencesKey("audio_fx_lofi_enabled")
        val LOFI_INTENSITY = intPreferencesKey("audio_fx_lofi_intensity")
        val RADIO_ENABLED = booleanPreferencesKey("audio_fx_radio_enabled")
        val RADIO_INTENSITY = intPreferencesKey("audio_fx_radio_intensity")
        val WOW_FLUTTER_ENABLED = booleanPreferencesKey("audio_fx_wow_flutter_enabled")
        val WOW_FLUTTER_INTENSITY = intPreferencesKey("audio_fx_wow_flutter_intensity")
        val REVERB_ENABLED = booleanPreferencesKey("audio_fx_reverb_enabled")
        val REVERB_INTENSITY = intPreferencesKey("audio_fx_reverb_intensity")
    }

    val lofiEnabledFlow: Flow<Boolean> = dataStore.data.map { it[Keys.LOFI_ENABLED] ?: false }
    val lofiIntensityFlow: Flow<Int> = dataStore.data.map { (it[Keys.LOFI_INTENSITY] ?: 0).coerceIn(0, 100) }

    val radioEnabledFlow: Flow<Boolean> = dataStore.data.map { it[Keys.RADIO_ENABLED] ?: false }
    val radioIntensityFlow: Flow<Int> = dataStore.data.map { (it[Keys.RADIO_INTENSITY] ?: 0).coerceIn(0, 100) }

    val wowFlutterEnabledFlow: Flow<Boolean> = dataStore.data.map { it[Keys.WOW_FLUTTER_ENABLED] ?: false }
    val wowFlutterIntensityFlow: Flow<Int> = dataStore.data.map { (it[Keys.WOW_FLUTTER_INTENSITY] ?: 0).coerceIn(0, 100) }

    val reverbEnabledFlow: Flow<Boolean> = dataStore.data.map { it[Keys.REVERB_ENABLED] ?: false }
    val reverbIntensityFlow: Flow<Int> = dataStore.data.map { (it[Keys.REVERB_INTENSITY] ?: 0).coerceIn(0, 100) }

    suspend fun setLofiEnabled(enabled: Boolean) = dataStore.edit { it[Keys.LOFI_ENABLED] = enabled }
    suspend fun setLofiIntensity(value: Int) = dataStore.edit { it[Keys.LOFI_INTENSITY] = value.coerceIn(0, 100) }

    suspend fun setRadioEnabled(enabled: Boolean) = dataStore.edit { it[Keys.RADIO_ENABLED] = enabled }
    suspend fun setRadioIntensity(value: Int) = dataStore.edit { it[Keys.RADIO_INTENSITY] = value.coerceIn(0, 100) }

    suspend fun setWowFlutterEnabled(enabled: Boolean) = dataStore.edit { it[Keys.WOW_FLUTTER_ENABLED] = enabled }
    suspend fun setWowFlutterIntensity(value: Int) = dataStore.edit { it[Keys.WOW_FLUTTER_INTENSITY] = value.coerceIn(0, 100) }

    suspend fun setReverbEnabled(enabled: Boolean) = dataStore.edit { it[Keys.REVERB_ENABLED] = enabled }
    suspend fun setReverbIntensity(value: Int) = dataStore.edit { it[Keys.REVERB_INTENSITY] = value.coerceIn(0, 100) }
}

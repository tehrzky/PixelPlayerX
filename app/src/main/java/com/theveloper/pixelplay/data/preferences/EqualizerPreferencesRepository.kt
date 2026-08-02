package com.theveloper.pixelplay.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.theveloper.pixelplay.data.equalizer.EqualizerPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EqualizerPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json
) {
    private object Keys {
        val EQUALIZER_ENABLED = booleanPreferencesKey("equalizer_enabled")
        val EQUALIZER_PRESET = stringPreferencesKey("equalizer_preset")
        val EQUALIZER_CUSTOM_BANDS = stringPreferencesKey("equalizer_custom_bands")
        val BASS_BOOST_STRENGTH = intPreferencesKey("bass_boost_strength")
        val VIRTUALIZER_STRENGTH = intPreferencesKey("virtualizer_strength")
        val BASS_BOOST_ENABLED = booleanPreferencesKey("bass_boost_enabled")
        val VIRTUALIZER_ENABLED = booleanPreferencesKey("virtualizer_enabled")
        val LOUDNESS_ENHANCER_ENABLED = booleanPreferencesKey("loudness_enhancer_enabled")
        val LOUDNESS_ENHANCER_STRENGTH = intPreferencesKey("loudness_enhancer_strength")
        val BASS_BOOST_DISMISSED = booleanPreferencesKey("bass_boost_dismissed")
        val VIRTUALIZER_DISMISSED = booleanPreferencesKey("virtualizer_dismissed")
        val LOUDNESS_DISMISSED = booleanPreferencesKey("loudness_dismissed")
        val VIEW_MODE = stringPreferencesKey("equalizer_view_mode")
        val REVERB_ENABLED = booleanPreferencesKey("reverb_enabled")
        val REVERB_STRENGTH = intPreferencesKey("reverb_strength")
        val REVERB_DECAY = intPreferencesKey("reverb_decay")
        val REVERB_DISMISSED = booleanPreferencesKey("reverb_dismissed")
        val CUSTOM_PRESETS = stringPreferencesKey("custom_presets_json")
        val RADIO_ENABLED = booleanPreferencesKey("radio_effect_enabled")
        val RADIO_NOISE = intPreferencesKey("radio_noise_level")
        val RADIO_DISTORTION = intPreferencesKey("radio_distortion_amount")
        val RADIO_BANDPASS = booleanPreferencesKey("radio_bandpass")
        val RADIO_CRACKLE = booleanPreferencesKey("radio_crackle")
        val RADIO_TAPE_WOW_ENABLED = booleanPreferencesKey("radio_tape_wow_enabled")
        val RADIO_TAPE_WOW_DEPTH = intPreferencesKey("radio_tape_wow_depth")
        val RADIO_PHASER_ENABLED = booleanPreferencesKey("radio_phaser_enabled")
        val RADIO_PHASER_DEPTH = intPreferencesKey("radio_phaser_depth")
        val RADIO_PHASER_RATE = intPreferencesKey("radio_phaser_rate")
        val RADIO_BATHROOM_REVERB_ENABLED = booleanPreferencesKey("radio_bathroom_reverb_enabled")
        val RADIO_BATHROOM_REVERB_AMOUNT = intPreferencesKey("radio_bathroom_reverb_amount")
        val PINNED_PRESETS = stringPreferencesKey("pinned_presets_json")
    }

    val equalizerViewModeFlow: Flow<EqualizerViewMode> = dataStore.data.map { preferences ->
        val modeString = preferences[Keys.VIEW_MODE]
        if (modeString != null) {
            try {
                EqualizerViewMode.valueOf(modeString)
            } catch (_: Exception) {
                EqualizerViewMode.SLIDERS
            }
        } else {
            val isGraph = preferences[booleanPreferencesKey("is_graph_view")] ?: false
            if (isGraph) EqualizerViewMode.GRAPH else EqualizerViewMode.SLIDERS
        }
    }

    val equalizerEnabledFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.EQUALIZER_ENABLED] ?: false
    }

    val equalizerPresetFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[Keys.EQUALIZER_PRESET] ?: "flat"
    }

    val equalizerCustomBandsFlow: Flow<List<Int>> = dataStore.data.map { preferences ->
        val stored = preferences[Keys.EQUALIZER_CUSTOM_BANDS]
        if (stored != null) {
            try {
                val decoded = json.decodeFromString<List<Int>>(stored)
                when {
                    decoded.size >= 10 -> decoded.take(10)
                    decoded.isEmpty() -> List(10) { 0 }
                    else -> decoded + List(10 - decoded.size) { 0 }
                }
            } catch (_: Exception) {
                List(10) { 0 }
            }
        } else {
            List(10) { 0 }
        }
    }

    val bassBoostStrengthFlow: Flow<Int> = dataStore.data.map { preferences ->
        preferences[Keys.BASS_BOOST_STRENGTH] ?: 0
    }

    val virtualizerStrengthFlow: Flow<Int> = dataStore.data.map { preferences ->
        preferences[Keys.VIRTUALIZER_STRENGTH] ?: 0
    }

    val bassBoostEnabledFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.BASS_BOOST_ENABLED] ?: false
    }

    val virtualizerEnabledFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.VIRTUALIZER_ENABLED] ?: false
    }

    val loudnessEnhancerEnabledFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.LOUDNESS_ENHANCER_ENABLED] ?: false
    }

    val loudnessEnhancerStrengthFlow: Flow<Int> = dataStore.data.map { preferences ->
        (preferences[Keys.LOUDNESS_ENHANCER_STRENGTH] ?: 0).coerceIn(0, 1000)
    }

    val bassBoostDismissedFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.BASS_BOOST_DISMISSED] ?: false
    }

    val virtualizerDismissedFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.VIRTUALIZER_DISMISSED] ?: false
    }

    val loudnessDismissedFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.LOUDNESS_DISMISSED] ?: false
    }

    val reverbEnabledFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.REVERB_ENABLED] ?: false
    }

    val reverbStrengthFlow: Flow<Int> = dataStore.data.map { preferences ->
        (preferences[Keys.REVERB_STRENGTH] ?: 0).coerceIn(0, 1000)
    }

    val reverbDecayFlow: Flow<Int> = dataStore.data.map { preferences ->
        (preferences[Keys.REVERB_DECAY] ?: 500).coerceIn(0, 1000)
    }

    val reverbDismissedFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.REVERB_DISMISSED] ?: false
    }

    val radioEnabledFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.RADIO_ENABLED] ?: false
    }

    val radioNoiseFlow: Flow<Int> = dataStore.data.map { preferences ->
        (preferences[Keys.RADIO_NOISE] ?: 150).coerceIn(0, 1000)
    }

    val radioDistortionFlow: Flow<Int> = dataStore.data.map { preferences ->
        (preferences[Keys.RADIO_DISTORTION] ?: 250).coerceIn(0, 1000)
    }

    val radioBandpassFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.RADIO_BANDPASS] ?: true
    }

    val radioCrackleFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.RADIO_CRACKLE] ?: true
    }

    val radioTapeWowEnabledFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.RADIO_TAPE_WOW_ENABLED] ?: false
    }

    val radioTapeWowDepthFlow: Flow<Int> = dataStore.data.map { preferences ->
        (preferences[Keys.RADIO_TAPE_WOW_DEPTH] ?: 300).coerceIn(0, 1000)
    }

    val radioPhaserEnabledFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.RADIO_PHASER_ENABLED] ?: false
    }

    val radioPhaserDepthFlow: Flow<Int> = dataStore.data.map { preferences ->
        (preferences[Keys.RADIO_PHASER_DEPTH] ?: 500).coerceIn(0, 1000)
    }

    val radioPhaserRateFlow: Flow<Int> = dataStore.data.map { preferences ->
        (preferences[Keys.RADIO_PHASER_RATE] ?: 300).coerceIn(0, 1000)
    }

    val radioBathroomReverbEnabledFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.RADIO_BATHROOM_REVERB_ENABLED] ?: false
    }

    val radioBathroomReverbAmountFlow: Flow<Int> = dataStore.data.map { preferences ->
        (preferences[Keys.RADIO_BATHROOM_REVERB_AMOUNT] ?: 300).coerceIn(0, 1000)
    }

    val customPresetsFlow: Flow<List<EqualizerPreset>> = dataStore.data.map { preferences ->
        val jsonString = preferences[Keys.CUSTOM_PRESETS]
        if (jsonString != null) {
            try {
                json.decodeFromString<List<EqualizerPreset>>(jsonString)
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    val pinnedPresetsFlow: Flow<List<String>> = dataStore.data.map { preferences ->
        val jsonString = preferences[Keys.PINNED_PRESETS]
        if (jsonString != null) {
            try {
                json.decodeFromString<List<String>>(jsonString)
            } catch (_: Exception) {
                EqualizerPreset.ALL_PRESETS.map { it.name }
            }
        } else {
            EqualizerPreset.ALL_PRESETS.map { it.name }
        }
    }

    suspend fun setEqualizerViewMode(mode: EqualizerViewMode) =
        dataStore.edit { preferences ->
            preferences[Keys.VIEW_MODE] = mode.name
        }

    suspend fun setEqualizerEnabled(enabled: Boolean) =
        dataStore.edit { preferences ->
            preferences[Keys.EQUALIZER_ENABLED] = enabled
        }

    suspend fun setEqualizerPreset(preset: String) =
        dataStore.edit { preferences ->
            preferences[Keys.EQUALIZER_PRESET] = preset
        }

    suspend fun setEqualizerCustomBands(bands: List<Int>) =
        dataStore.edit { preferences ->
            val normalized = when {
                bands.size >= 10 -> bands.take(10)
                bands.isEmpty() -> List(10) { 0 }
                else -> bands + List(10 - bands.size) { 0 }
            }
            preferences[Keys.EQUALIZER_CUSTOM_BANDS] = json.encodeToString(normalized)
        }

    suspend fun setBassBoostStrength(strength: Int) =
        dataStore.edit { preferences ->
            preferences[Keys.BASS_BOOST_STRENGTH] = strength.coerceIn(0, 1000)
        }

    suspend fun setVirtualizerStrength(strength: Int) =
        dataStore.edit { preferences ->
            preferences[Keys.VIRTUALIZER_STRENGTH] = strength.coerceIn(0, 1000)
        }

    suspend fun setBassBoostEnabled(enabled: Boolean) =
        dataStore.edit { preferences ->
            preferences[Keys.BASS_BOOST_ENABLED] = enabled
        }

    suspend fun setVirtualizerEnabled(enabled: Boolean) =
        dataStore.edit { preferences ->
            preferences[Keys.VIRTUALIZER_ENABLED] = enabled
        }

    suspend fun setLoudnessEnhancerEnabled(enabled: Boolean) =
        dataStore.edit { preferences ->
            preferences[Keys.LOUDNESS_ENHANCER_ENABLED] = enabled
        }

    suspend fun setLoudnessEnhancerStrength(strength: Int) =
        dataStore.edit { preferences ->
            preferences[Keys.LOUDNESS_ENHANCER_STRENGTH] = strength.coerceIn(0, 1000)
        }

    suspend fun setBassBoostDismissed(dismissed: Boolean) =
        dataStore.edit { preferences ->
            preferences[Keys.BASS_BOOST_DISMISSED] = dismissed
        }

    suspend fun setVirtualizerDismissed(dismissed: Boolean) =
        dataStore.edit { preferences ->
            preferences[Keys.VIRTUALIZER_DISMISSED] = dismissed
        }

    suspend fun setLoudnessDismissed(dismissed: Boolean) =
        dataStore.edit { preferences ->
            preferences[Keys.LOUDNESS_DISMISSED] = dismissed
        }

    suspend fun setReverbEnabled(enabled: Boolean) =
        dataStore.edit { preferences ->
            preferences[Keys.REVERB_ENABLED] = enabled
        }

    suspend fun setReverbStrength(strength: Int) =
        dataStore.edit { preferences ->
            preferences[Keys.REVERB_STRENGTH] = strength.coerceIn(0, 1000)
        }

    suspend fun setReverbDecay(decay: Int) =
        dataStore.edit { preferences ->
            preferences[Keys.REVERB_DECAY] = decay.coerceIn(0, 1000)
        }

    suspend fun setReverbDismissed(dismissed: Boolean) =
        dataStore.edit { preferences ->
            preferences[Keys.REVERB_DISMISSED] = dismissed
        }

    suspend fun setRadioEnabled(enabled: Boolean) =
        dataStore.edit { preferences ->
            preferences[Keys.RADIO_ENABLED] = enabled
        }

    suspend fun setRadioNoise(noise: Int) =
        dataStore.edit { preferences ->
            preferences[Keys.RADIO_NOISE] = noise.coerceIn(0, 1000)
        }

    suspend fun setRadioDistortion(distortion: Int) =
        dataStore.edit { preferences ->
            preferences[Keys.RADIO_DISTORTION] = distortion.coerceIn(0, 1000)
        }

    suspend fun setRadioBandpass(enabled: Boolean) =
        dataStore.edit { preferences ->
            preferences[Keys.RADIO_BANDPASS] = enabled
        }

    suspend fun setRadioCrackle(enabled: Boolean) =
        dataStore.edit { preferences ->
            preferences[Keys.RADIO_CRACKLE] = enabled
        }

    suspend fun setRadioTapeWowEnabled(enabled: Boolean) =
        dataStore.edit { preferences ->
            preferences[Keys.RADIO_TAPE_WOW_ENABLED] = enabled
        }

    suspend fun setRadioTapeWowDepth(depth: Int) =
        dataStore.edit { preferences ->
            preferences[Keys.RADIO_TAPE_WOW_DEPTH] = depth.coerceIn(0, 1000)
        }

    suspend fun setRadioPhaserEnabled(enabled: Boolean) =
        dataStore.edit { preferences ->
            preferences[Keys.RADIO_PHASER_ENABLED] = enabled
        }

    suspend fun setRadioPhaserDepth(depth: Int) =
        dataStore.edit { preferences ->
            preferences[Keys.RADIO_PHASER_DEPTH] = depth.coerceIn(0, 1000)
        }

    suspend fun setRadioPhaserRate(rate: Int) =
        dataStore.edit { preferences ->
            preferences[Keys.RADIO_PHASER_RATE] = rate.coerceIn(0, 1000)
        }

    suspend fun setRadioBathroomReverbEnabled(enabled: Boolean) =
        dataStore.edit { preferences ->
            preferences[Keys.RADIO_BATHROOM_REVERB_ENABLED] = enabled
        }

    suspend fun setRadioBathroomReverbAmount(amount: Int) =
        dataStore.edit { preferences ->
            preferences[Keys.RADIO_BATHROOM_REVERB_AMOUNT] = amount.coerceIn(0, 1000)
        }

    suspend fun setPinnedPresets(presetNames: List<String>) =
        dataStore.edit { preferences ->
            preferences[Keys.PINNED_PRESETS] = json.encodeToString(presetNames)
        }

    suspend fun saveCustomPreset(preset: EqualizerPreset) {
        val current = customPresetsFlow.first().toMutableList()
        current.removeAll { it.name == preset.name }
        current.add(preset)
        dataStore.edit { preferences ->
            preferences[Keys.CUSTOM_PRESETS] = json.encodeToString(current)
        }
    }

    suspend fun deleteCustomPreset(presetName: String) {
        val current = customPresetsFlow.first().toMutableList()
        current.removeAll { it.name == presetName }
        dataStore.edit { preferences ->
            preferences[Keys.CUSTOM_PRESETS] = json.encodeToString(current)
        }

        val pinned = pinnedPresetsFlow.first().toMutableList()
        if (pinned.remove(presetName)) {
            setPinnedPresets(pinned)
        }
    }

    suspend fun renameCustomPreset(oldName: String, newName: String) {
        val current = customPresetsFlow.first().toMutableList()
        val index = current.indexOfFirst { it.name == oldName }
        if (index == -1) return

        current[index] = current[index].copy(name = newName, displayName = newName)
        dataStore.edit { preferences ->
            preferences[Keys.CUSTOM_PRESETS] = json.encodeToString(current)
        }

        val pinned = pinnedPresetsFlow.first().toMutableList()
        val pinnedIndex = pinned.indexOf(oldName)
        if (pinnedIndex != -1) {
            pinned[pinnedIndex] = newName
            setPinnedPresets(pinned)
        }

        val activePreset = dataStore.data.first()[Keys.EQUALIZER_PRESET]
        if (activePreset == oldName) {
            dataStore.edit { preferences ->
                preferences[Keys.EQUALIZER_PRESET] = newName
            }
        }
    }

    suspend fun updateCustomPresetBands(presetName: String, bandLevels: List<Int>) {
        val current = customPresetsFlow.first().toMutableList()
        val index = current.indexOfFirst { it.name == presetName }
        if (index == -1) return

        current[index] = current[index].copy(bandLevels = bandLevels)
        dataStore.edit { preferences ->
            preferences[Keys.CUSTOM_PRESETS] = json.encodeToString(current)
        }
    }
}

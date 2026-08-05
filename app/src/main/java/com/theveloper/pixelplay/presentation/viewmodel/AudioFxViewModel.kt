package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.preferences.AudioFxPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AudioFxUiState(
    val lofiEnabled: Boolean = false,
    val lofiIntensity: Int = 40,
    val radioEnabled: Boolean = false,
    val radioIntensity: Int = 50,
    val wowFlutterEnabled: Boolean = false,
    val wowFlutterIntensity: Int = 30,
    val reverbEnabled: Boolean = false,
    val reverbIntensity: Int = 35
)

@HiltViewModel
class AudioFxViewModel @Inject constructor(
    private val audioFxPreferencesRepository: AudioFxPreferencesRepository,
    private val audioFxStateHolder: com.theveloper.pixelplay.data.service.player.AudioFxStateHolder
) : ViewModel() {

    private val _uiState = MutableStateFlow(AudioFxUiState())
    val uiState: StateFlow<AudioFxUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine<Any, AudioFxUiState>(
                audioFxPreferencesRepository.lofiEnabledFlow,
                audioFxPreferencesRepository.lofiIntensityFlow,
                audioFxPreferencesRepository.radioEnabledFlow,
                audioFxPreferencesRepository.radioIntensityFlow,
                audioFxPreferencesRepository.wowFlutterEnabledFlow,
                audioFxPreferencesRepository.wowFlutterIntensityFlow,
                audioFxPreferencesRepository.reverbEnabledFlow,
                audioFxPreferencesRepository.reverbIntensityFlow
            ) { values ->
                AudioFxUiState(
                    lofiEnabled = values[0] as Boolean,
                    lofiIntensity = values[1] as Int,
                    radioEnabled = values[2] as Boolean,
                    radioIntensity = values[3] as Int,
                    wowFlutterEnabled = values[4] as Boolean,
                    wowFlutterIntensity = values[5] as Int,
                    reverbEnabled = values[6] as Boolean,
                    reverbIntensity = values[7] as Int
                )
            }.collect { state ->
                _uiState.value = state
                // Prime the live audio-thread state from persisted prefs, so a cold
                // app launch (screen never opened this session) still reflects the
                // last saved settings once the screen IS opened.
                audioFxStateHolder.lofiEnabled = state.lofiEnabled
                audioFxStateHolder.lofiIntensity = state.lofiIntensity
                audioFxStateHolder.radioEnabled = state.radioEnabled
                audioFxStateHolder.radioIntensity = state.radioIntensity
                audioFxStateHolder.wowFlutterEnabled = state.wowFlutterEnabled
                audioFxStateHolder.wowFlutterIntensity = state.wowFlutterIntensity
                audioFxStateHolder.reverbEnabled = state.reverbEnabled
                audioFxStateHolder.reverbIntensity = state.reverbIntensity
            }
        }
    }

    fun setLofiEnabled(enabled: Boolean) {
        audioFxStateHolder.lofiEnabled = enabled
        viewModelScope.launch { audioFxPreferencesRepository.setLofiEnabled(enabled) }
    }
    fun setLofiIntensityLive(value: Int) { audioFxStateHolder.lofiIntensity = value }
    fun setRadioIntensityLive(value: Int) { audioFxStateHolder.radioIntensity = value }
    fun setWowFlutterIntensityLive(value: Int) { audioFxStateHolder.wowFlutterIntensity = value }
    fun setReverbIntensityLive(value: Int) { audioFxStateHolder.reverbIntensity = value }

    fun setLofiIntensity(value: Int) {
        audioFxStateHolder.lofiIntensity = value
        viewModelScope.launch { audioFxPreferencesRepository.setLofiIntensity(value) }
    }

    fun setRadioEnabled(enabled: Boolean) {
        audioFxStateHolder.radioEnabled = enabled
        viewModelScope.launch { audioFxPreferencesRepository.setRadioEnabled(enabled) }
    }
    fun setRadioIntensity(value: Int) {
        audioFxStateHolder.radioIntensity = value
        viewModelScope.launch { audioFxPreferencesRepository.setRadioIntensity(value) }
    }

    fun setWowFlutterEnabled(enabled: Boolean) {
        audioFxStateHolder.wowFlutterEnabled = enabled
        viewModelScope.launch { audioFxPreferencesRepository.setWowFlutterEnabled(enabled) }
    }
    fun setWowFlutterIntensity(value: Int) {
        audioFxStateHolder.wowFlutterIntensity = value
        viewModelScope.launch { audioFxPreferencesRepository.setWowFlutterIntensity(value) }
    }

    fun setReverbEnabled(enabled: Boolean) {
        audioFxStateHolder.reverbEnabled = enabled
        viewModelScope.launch { audioFxPreferencesRepository.setReverbEnabled(enabled) }
    }
    fun setReverbIntensity(value: Int) {
        audioFxStateHolder.reverbIntensity = value
        viewModelScope.launch { audioFxPreferencesRepository.setReverbIntensity(value) }
    }
}

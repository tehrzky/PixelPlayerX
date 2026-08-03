package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.equalizer.EqualizerManager
import com.theveloper.pixelplay.data.equalizer.EqualizerPreset
import com.theveloper.pixelplay.data.preferences.EqualizerPreferencesRepository
import com.theveloper.pixelplay.data.preferences.EqualizerViewMode
import com.theveloper.pixelplay.data.service.player.DualPlayerEngine
import com.theveloper.pixelplay.data.service.player.RadioEffectAudioProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.roundToInt

data class EqualizerUiState(
    val isEnabled: Boolean = false,
    val currentPreset: EqualizerPreset = EqualizerPreset.FLAT,
    val bandLevels: List<Int> = List(10) { 0 },
    val editingPresetName: String? = null,
    val bassBoostEnabled: Boolean = false,
    val bassBoostStrength: Float = 0f,
    val virtualizerEnabled: Boolean = false,
    val virtualizerStrength: Float = 0f,
    val loudnessEnhancerEnabled: Boolean = false,
    val loudnessEnhancerStrength: Float = 0f,
    val isBassBoostSupported: Boolean = true,
    val isVirtualizerSupported: Boolean = true,
    val isLoudnessEnhancerSupported: Boolean = true,
    val viewMode: EqualizerViewMode = EqualizerViewMode.SLIDERS,
    val isBassBoostDismissed: Boolean = false,
    val isVirtualizerDismissed: Boolean = false,
    val isLoudnessDismissed: Boolean = false,
    val customPresets: List<EqualizerPreset> = emptyList(),
    val pinnedPresetsNames: List<String> = emptyList(),
    val customBands: List<Int> = List(10) { 0 },

    // Reverb
    val reverbEnabled: Boolean = false,
    val reverbStrength: Float = 0f,
    val reverbDecay: Float = 500f,
    val isReverbSupported: Boolean = true,
    val isReverbDismissed: Boolean = false,

    // Radio Effect
    val radioEffectEnabled: Boolean = false,
    val radioNoise: Float = 150f,
    val radioDistortion: Float = 250f,
    val radioBandpass: Boolean = true,
    val radioCrackle: Boolean = true,
    val radioTapeWowEnabled: Boolean = false,
    val radioTapeWowDepth: Float = 300f,
    val radioPhaserEnabled: Boolean = false,
    val radioPhaserDepth: Float = 500f,
    val radioPhaserRate: Float = 300f,
    val radioBathroomReverbEnabled: Boolean = false,
    val radioBathroomReverbAmount: Float = 300f
) {
    val accessiblePresets: List<EqualizerPreset>
        get() = pinnedPresetsNames.mapNotNull { name ->
            customPresets.find { it.name == name } ?: EqualizerPreset.fromName(name)
        }

    val allAvailablePresets: List<EqualizerPreset>
        get() = EqualizerPreset.ALL_PRESETS + customPresets
}

// Internal structures to group flows safely and bypass combine() N-arity limits
private data class CoreEqSettings(
    val enabled: Boolean,
    val presetName: String,
    val customBands: List<Int>,
    val viewMode: EqualizerViewMode,
    val customPresets: List<EqualizerPreset>,
    val pinnedPresets: List<String>
)

private data class EffectSettings(
    val bbEnabled: Boolean, val bbStrength: Int, val bbDismissed: Boolean,
    val vEnabled: Boolean, val vStrength: Int, val vDismissed: Boolean,
    val lEnabled: Boolean, val lStrength: Int, val lDismissed: Boolean,
    val rEnabled: Boolean, val rStrength: Int, val rDecay: Int, val rDismissed: Boolean
)

private data class RadioSettings(
    val enabled: Boolean, val noise: Int, val distortion: Int,
    val bandpass: Boolean, val crackle: Boolean,
    val tapeWowEnabled: Boolean, val tapeWowDepth: Int,
    val phaserEnabled: Boolean, val phaserDepth: Int, val phaserRate: Int,
    val bathroomReverbEnabled: Boolean, val bathroomReverbAmount: Int
)

@HiltViewModel
class EqualizerViewModel @Inject constructor(
    private val equalizerManager: EqualizerManager,
    private val equalizerPreferencesRepository: EqualizerPreferencesRepository,
    private val dualPlayerEngine: DualPlayerEngine,
    private val radioEffectProcessor: RadioEffectAudioProcessor,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {

    companion object {
        private const val TAG = "EqualizerViewModel"
        private const val SLIDER_PERSIST_DEBOUNCE_MS = 150L
    }

    private val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager

    private val _uiState = MutableStateFlow(EqualizerUiState())
    val uiState: StateFlow<EqualizerUiState> = _uiState.asStateFlow()

    private val _systemVolume = MutableStateFlow(0f)
    val systemVolume: StateFlow<Float> = _systemVolume.asStateFlow()

    private var persistBandLevelsJob: Job? = null
    private var persistBassBoostJob: Job? = null
    private var persistVirtualizerJob: Job? = null
    private var persistLoudnessJob: Job? = null
    private var persistReverbJob: Job? = null
    private var persistRadioJob: Job? = null

    init {
        initializeEqualizer()
        observeEqualizerState()
        loadSystemVolume()
    }

    private fun loadSystemVolume() {
        try {
            val current = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            _systemVolume.value = if (max > 0) current.toFloat() / max.toFloat() else 0.5f
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to load system volume")
        }
    }

    fun setSystemVolume(percent: Float) {
        viewModelScope.launch {
            try {
                val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                val target = (percent * max).roundToInt().coerceIn(0, max)
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
                _systemVolume.value = percent
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to set system volume")
            }
        }
    }

    private fun initializeEqualizer() {
        viewModelScope.launch {
            Timber.tag(TAG).d("Initializing equalizer...")

            if (!equalizerManager.isAttached) {
                val enabled = equalizerPreferencesRepository.equalizerEnabledFlow.first()
                val presetName = equalizerPreferencesRepository.equalizerPresetFlow.first()
                val customBands = equalizerPreferencesRepository.equalizerCustomBandsFlow.first()
                val bassBoostEnabled = equalizerPreferencesRepository.bassBoostEnabledFlow.first()
                val bassBoost = equalizerPreferencesRepository.bassBoostStrengthFlow.first()
                val virtualizerEnabled = equalizerPreferencesRepository.virtualizerEnabledFlow.first()
                val virtualizer = equalizerPreferencesRepository.virtualizerStrengthFlow.first()
                val loudnessEnabled = equalizerPreferencesRepository.loudnessEnhancerEnabledFlow.first()
                val loudnessStrength = equalizerPreferencesRepository.loudnessEnhancerStrengthFlow.first()

                equalizerManager.restoreState(
                    enabled, presetName, customBands,
                    bassBoostEnabled, bassBoost,
                    virtualizerEnabled, virtualizer,
                    loudnessEnabled, loudnessStrength
                )

                val initialSessionId = dualPlayerEngine.getAudioSessionId()
                if (initialSessionId != 0) {
                    equalizerManager.attachToAudioSessionIfNeeded(initialSessionId, source = "init")
                }
            } else {
                Timber.tag(TAG).d("Equalizer already attached by service, skipping restore.")
            }

            _uiState.update { current ->
                current.copy(
                    isBassBoostSupported = equalizerManager.isBassBoostSupported(),
                    isVirtualizerSupported = equalizerManager.isVirtualizerSupported(),
                    isLoudnessEnhancerSupported = equalizerManager.isLoudnessEnhancerSupported(),
                    isReverbSupported = true
                )
            }

            applyReverbState()
            applyRadioProcessorState()

            dualPlayerEngine.activeAudioSessionId.collect { sessionId ->
                if (sessionId != 0) {
                    Timber.tag(TAG).d("Audio Session ID changed to $sessionId.")
                    _uiState.update { current ->
                        current.copy(
                            isBassBoostSupported = equalizerManager.isBassBoostSupported(),
                            isVirtualizerSupported = equalizerManager.isVirtualizerSupported(),
                            isLoudnessEnhancerSupported = equalizerManager.isLoudnessEnhancerSupported(),
                            isReverbSupported = true
                        )
                    }
                }
            }
        }
    }

    private fun observeEqualizerState() {
        val coreFlow = combine(
            equalizerPreferencesRepository.equalizerEnabledFlow,
            equalizerPreferencesRepository.equalizerPresetFlow,
            equalizerPreferencesRepository.equalizerCustomBandsFlow,
            equalizerPreferencesRepository.equalizerViewModeFlow,
            equalizerPreferencesRepository.customPresetsFlow,
            equalizerPreferencesRepository.pinnedPresetsFlow
        ) { arr ->
            @Suppress("UNCHECKED_CAST")
            CoreEqSettings(
                enabled = arr[0] as Boolean,
                presetName = arr[1] as String,
                customBands = arr[2] as List<Int>,
                viewMode = arr[3] as EqualizerViewMode,
                customPresets = arr[4] as List<EqualizerPreset>,
                pinnedPresets = arr[5] as List<String>
            )
        }

        val effectFlow = combine(
            equalizerPreferencesRepository.bassBoostEnabledFlow,
            equalizerPreferencesRepository.bassBoostStrengthFlow,
            equalizerPreferencesRepository.bassBoostDismissedFlow,
            equalizerPreferencesRepository.virtualizerEnabledFlow,
            equalizerPreferencesRepository.virtualizerStrengthFlow,
            equalizerPreferencesRepository.virtualizerDismissedFlow,
            equalizerPreferencesRepository.loudnessEnhancerEnabledFlow,
            equalizerPreferencesRepository.loudnessEnhancerStrengthFlow,
            equalizerPreferencesRepository.loudnessDismissedFlow,
            equalizerPreferencesRepository.reverbEnabledFlow,
            equalizerPreferencesRepository.reverbStrengthFlow,
            equalizerPreferencesRepository.reverbDecayFlow,
            equalizerPreferencesRepository.reverbDismissedFlow
        ) { arr ->
            @Suppress("UNCHECKED_CAST")
            EffectSettings(
                bbEnabled = arr[0] as Boolean, bbStrength = arr[1] as Int, bbDismissed = arr[2] as Boolean,
                vEnabled = arr[3] as Boolean, vStrength = arr[4] as Int, vDismissed = arr[5] as Boolean,
                lEnabled = arr[6] as Boolean, lStrength = arr[7] as Int, lDismissed = arr[8] as Boolean,
                rEnabled = arr[9] as Boolean, rStrength = arr[10] as Int, rDecay = arr[11] as Int, rDismissed = arr[12] as Boolean
            )
        }

                val radioFlow = combine(
            equalizerPreferencesRepository.radioEnabledFlow,
            equalizerPreferencesRepository.radioNoiseFlow,
            equalizerPreferencesRepository.radioDistortionFlow,
            equalizerPreferencesRepository.radioBandpassFlow,
            equalizerPreferencesRepository.radioCrackleFlow,
            equalizerPreferencesRepository.radioTapeWowEnabledFlow,
            equalizerPreferencesRepository.radioTapeWowDepthFlow,
            equalizerPreferencesRepository.radioPhaserEnabledFlow,
            equalizerPreferencesRepository.radioPhaserDepthFlow,
            equalizerPreferencesRepository.radioPhaserRateFlow,
            equalizerPreferencesRepository.radioBathroomReverbEnabledFlow,
            equalizerPreferencesRepository.radioBathroomReverbAmountFlow
        ) { arr ->
            @Suppress("UNCHECKED_CAST")
            RadioSettings(
                enabled = arr[0] as Boolean, noise = arr[1] as Int, distortion = arr[2] as Int,
                bandpass = arr[3] as Boolean, crackle = arr[4] as Boolean,
                tapeWowEnabled = arr[5] as Boolean, tapeWowDepth = arr[6] as Int,
                phaserEnabled = arr[7] as Boolean, phaserDepth = arr[8] as Int, phaserRate = arr[9] as Int,
                bathroomReverbEnabled = arr[10] as Boolean, bathroomReverbAmount = arr[11] as Int
            )
        }

        viewModelScope.launch {
            combine(coreFlow, effectFlow, radioFlow) { core, effects, radio ->
                val currentPreset = if (core.presetName == "custom") {
                    EqualizerPreset.custom(core.customBands)
                } else {
                    core.customPresets.find { it.name == core.presetName }
                        ?: EqualizerPreset.fromName(core.presetName)
                }

                EqualizerUiState(
                    isEnabled = core.enabled,
                    currentPreset = currentPreset,
                    bandLevels = if (currentPreset.name == "custom") core.customBands else currentPreset.bandLevels,
                    customBands = core.customBands,
                    editingPresetName = _uiState.value.editingPresetName,
                    bassBoostEnabled = effects.bbEnabled,
                    bassBoostStrength = effects.bbStrength.toFloat(),
                    virtualizerEnabled = effects.vEnabled,
                    virtualizerStrength = effects.vStrength.toFloat(),
                    loudnessEnhancerEnabled = effects.lEnabled,
                    loudnessEnhancerStrength = effects.lStrength.toFloat(),
                    reverbEnabled = effects.rEnabled,
                    reverbStrength = effects.rStrength.toFloat(),
                    reverbDecay = effects.rDecay.toFloat(),
                    radioEffectEnabled = radio.enabled,
                    radioNoise = radio.noise.toFloat(),
                    radioDistortion = radio.distortion.toFloat(),
                    radioBandpass = radio.bandpass,
                    radioCrackle = radio.crackle,
                    radioTapeWowEnabled = radio.tapeWowEnabled,
                    radioTapeWowDepth = radio.tapeWowDepth.toFloat(),
                    radioPhaserEnabled = radio.phaserEnabled,
                    radioPhaserDepth = radio.phaserDepth.toFloat(),
                    radioPhaserRate = radio.phaserRate.toFloat(),
                    radioBathroomReverbEnabled = radio.bathroomReverbEnabled,
                    radioBathroomReverbAmount = radio.bathroomReverbAmount.toFloat(),
                    isBassBoostDismissed = effects.bbDismissed,
                    isVirtualizerDismissed = effects.vDismissed,
                    isLoudnessDismissed = effects.lDismissed,
                    isReverbDismissed = effects.rDismissed,
                    viewMode = core.viewMode,
                    customPresets = core.customPresets,
                    pinnedPresetsNames = core.pinnedPresets,
                    isBassBoostSupported = _uiState.value.isBassBoostSupported,
                    isVirtualizerSupported = _uiState.value.isVirtualizerSupported,
                    isLoudnessEnhancerSupported = _uiState.value.isLoudnessEnhancerSupported,
                    isReverbSupported = _uiState.value.isReverbSupported
                )
            }.collect { newState ->
                _uiState.value = newState
                applyReverbState()
                applyRadioProcessorState()
            }
        }
    }

    fun cycleViewMode() {
        viewModelScope.launch {
            val currentMode = _uiState.value.viewMode
            val nextMode = when (currentMode) {
                EqualizerViewMode.SLIDERS -> EqualizerViewMode.GRAPH
                EqualizerViewMode.GRAPH -> EqualizerViewMode.HYBRID
                EqualizerViewMode.HYBRID -> EqualizerViewMode.SLIDERS
            }
            equalizerPreferencesRepository.setEqualizerViewMode(nextMode)
        }
    }

    fun setEnabled(enabled: Boolean) {
        equalizerManager.setEnabled(enabled)
        _uiState.update { current -> current.copy(isEnabled = enabled) }
        viewModelScope.launch {
            equalizerManager.attachToAudioSessionIfNeeded(dualPlayerEngine.getAudioSessionId(), source = "toggle_enabled")
            equalizerPreferencesRepository.setEqualizerEnabled(enabled)
            applyReverbState()
            applyRadioProcessorState()
            if (enabled) {
                val player = dualPlayerEngine.masterPlayer
                if (player.isPlaying) {
                    player.pause()
                    player.play()
                }
            }
        }
    }

    fun toggleEqualizer() {
        setEnabled(!_uiState.value.isEnabled)
    }

    fun selectPreset(preset: EqualizerPreset) {
        persistBandLevelsJob?.cancel()
        equalizerManager.applyPreset(preset)
        _uiState.update { current ->
            current.copy(
                currentPreset = preset,
                bandLevels = preset.bandLevels,
                editingPresetName = null
            )
        }
        viewModelScope.launch {
            equalizerPreferencesRepository.setEqualizerPreset(preset.name)
            if (!preset.isCustom) {
                equalizerPreferencesRepository.setEqualizerCustomBands(preset.bandLevels)
            }
        }
    }

    fun setBandLevel(bandIndex: Int, level: Int) {
        if (bandIndex !in _uiState.value.bandLevels.indices) return
        val clampedLevel = level.coerceIn(-15, 15)

        equalizerManager.setBandLevel(bandIndex, clampedLevel)
        val updatedBands = equalizerManager.bandLevels.value
        _uiState.update { current ->
            val editingName = current.editingPresetName
                ?: current.currentPreset.name.takeIf { current.currentPreset.isCustom && it != "custom" }
            current.copy(
                currentPreset = EqualizerPreset.custom(updatedBands),
                bandLevels = updatedBands,
                customBands = updatedBands,
                editingPresetName = editingName
            )
        }

        persistBandLevelsJob?.cancel()
        persistBandLevelsJob = viewModelScope.launch {
            delay(SLIDER_PERSIST_DEBOUNCE_MS)
            equalizerPreferencesRepository.setEqualizerCustomBands(updatedBands)
            equalizerPreferencesRepository.setEqualizerPreset("custom")
        }
    }

    fun saveCurrentAsCustomPreset(name: String) {
        viewModelScope.launch {
            val bands = equalizerManager.bandLevels.value
            val preset = EqualizerPreset(name, name, bands, true)
            equalizerPreferencesRepository.saveCustomPreset(preset)

            togglePinPreset(name)
            selectPreset(preset)
        }
    }

    fun deleteCustomPreset(preset: EqualizerPreset) {
        viewModelScope.launch {
            equalizerPreferencesRepository.deleteCustomPreset(preset.name)
            if (_uiState.value.currentPreset.name == preset.name) {
                selectPreset(EqualizerPreset.FLAT)
            }
        }
    }

    fun renameCustomPreset(oldName: String, newName: String) {
        if (newName.isBlank() || oldName == newName) return
        viewModelScope.launch {
            equalizerPreferencesRepository.renameCustomPreset(oldName, newName)
        }
    }

    fun updateCustomPresetBands(presetName: String) {
        viewModelScope.launch {
            val bands = equalizerManager.bandLevels.value
            equalizerPreferencesRepository.updateCustomPresetBands(presetName, bands)
            selectPreset(EqualizerPreset(presetName, presetName, bands, true))
        }
    }

    fun setBassBoostEnabled(enabled: Boolean) {
        equalizerManager.setBassBoostEnabled(enabled)
        _uiState.update { current -> current.copy(bassBoostEnabled = enabled) }
        viewModelScope.launch {
            equalizerManager.attachToAudioSessionIfNeeded(dualPlayerEngine.getAudioSessionId(), source = "bass_boost_toggle")
            equalizerPreferencesRepository.setBassBoostEnabled(enabled)
        }
    }

    fun setBassBoostStrength(strength: Int) {
        val clampedStrength = strength.coerceIn(0, 1000)
        equalizerManager.setBassBoostStrength(clampedStrength)
        _uiState.update { current -> current.copy(bassBoostStrength = clampedStrength.toFloat()) }

        persistBassBoostJob?.cancel()
        persistBassBoostJob = viewModelScope.launch {
            delay(SLIDER_PERSIST_DEBOUNCE_MS)
            equalizerPreferencesRepository.setBassBoostStrength(clampedStrength)
        }
    }

    fun setVirtualizerEnabled(enabled: Boolean) {
        equalizerManager.setVirtualizerEnabled(enabled)
        _uiState.update { current -> current.copy(virtualizerEnabled = enabled) }
        viewModelScope.launch {
            equalizerManager.attachToAudioSessionIfNeeded(dualPlayerEngine.getAudioSessionId(), source = "virtualizer_toggle")
            equalizerPreferencesRepository.setVirtualizerEnabled(enabled)
        }
    }

    fun setVirtualizerStrength(strength: Int) {
        val clampedStrength = strength.coerceIn(0, 1000)
        equalizerManager.setVirtualizerStrength(clampedStrength)
        _uiState.update { current -> current.copy(virtualizerStrength = clampedStrength.toFloat()) }

        persistVirtualizerJob?.cancel()
        persistVirtualizerJob = viewModelScope.launch {
            delay(SLIDER_PERSIST_DEBOUNCE_MS)
            equalizerPreferencesRepository.setVirtualizerStrength(clampedStrength)
        }
    }

    fun setLoudnessEnhancerEnabled(enabled: Boolean) {
        equalizerManager.setLoudnessEnhancerEnabled(enabled)
        _uiState.update { current -> current.copy(loudnessEnhancerEnabled = enabled) }
        viewModelScope.launch {
            equalizerManager.attachToAudioSessionIfNeeded(dualPlayerEngine.getAudioSessionId(), source = "loudness_toggle")
            equalizerPreferencesRepository.setLoudnessEnhancerEnabled(enabled)
        }
    }

    fun setLoudnessEnhancerStrength(strength: Int) {
        val clampedStrength = strength.coerceIn(0, 1000)
        equalizerManager.setLoudnessEnhancerStrength(clampedStrength)
        _uiState.update { current -> current.copy(loudnessEnhancerStrength = clampedStrength.toFloat()) }

        persistLoudnessJob?.cancel()
        persistLoudnessJob = viewModelScope.launch {
            delay(SLIDER_PERSIST_DEBOUNCE_MS)
            equalizerPreferencesRepository.setLoudnessEnhancerStrength(clampedStrength)
        }
    }

    fun setReverbEnabled(enabled: Boolean) {
        _uiState.update { it.copy(reverbEnabled = enabled) }
        applyReverbState()
        viewModelScope.launch {
            equalizerPreferencesRepository.setReverbEnabled(enabled)
        }
    }

    fun setReverbStrength(strength: Float) {
        val clamped = strength.coerceIn(0f, 1000f)
        _uiState.update { it.copy(reverbStrength = clamped) }
        applyReverbState()

        persistReverbJob?.cancel()
        persistReverbJob = viewModelScope.launch {
            delay(SLIDER_PERSIST_DEBOUNCE_MS)
            equalizerPreferencesRepository.setReverbStrength(clamped.toInt())
        }
    }

    fun setReverbDecay(decay: Float) {
        val clamped = decay.coerceIn(100f, 20000f)
        _uiState.update { it.copy(reverbDecay = clamped) }
        applyReverbState()

        persistReverbJob?.cancel()
        persistReverbJob = viewModelScope.launch {
            delay(SLIDER_PERSIST_DEBOUNCE_MS)
            equalizerPreferencesRepository.setReverbDecay(clamped.toInt())
        }
    }

    private fun applyReverbState() {
        // TODO: Reverb not yet wired in DualPlayerEngine; re-enable once setReverbParameters exists.
        // val state = _uiState.value
        // val isEffectActive = state.isEnabled && state.reverbEnabled
        // if (isEffectActive) {
        //     val wetMix = state.reverbStrength / 1000f
        //     val decayTimeMs = state.reverbDecay.toInt()
        //     dualPlayerEngine.setReverbParameters(true, wetMix, decayTimeMs)
        // } else {
        //     dualPlayerEngine.setReverbParameters(false, 0f, 500)
        // }
    }

    fun setRadioEffectEnabled(enabled: Boolean) {
        _uiState.update { it.copy(radioEffectEnabled = enabled) }
        applyRadioProcessorState()
        viewModelScope.launch {
            equalizerPreferencesRepository.setRadioEnabled(enabled)
        }
    }

    fun setRadioNoise(value: Float) {
        val clamped = value.coerceIn(0f, 1000f)
        _uiState.update { it.copy(radioNoise = clamped) }
        applyRadioProcessorState()

        persistRadioJob?.cancel()
        persistRadioJob = viewModelScope.launch {
            delay(SLIDER_PERSIST_DEBOUNCE_MS)
            equalizerPreferencesRepository.setRadioNoise(clamped.toInt())
        }
    }

    fun setRadioDistortion(value: Float) {
        val clamped = value.coerceIn(0f, 1000f)
        _uiState.update { it.copy(radioDistortion = clamped) }
        applyRadioProcessorState()

        persistRadioJob?.cancel()
        persistRadioJob = viewModelScope.launch {
            delay(SLIDER_PERSIST_DEBOUNCE_MS)
            equalizerPreferencesRepository.setRadioDistortion(clamped.toInt())
        }
    }

    fun setRadioBandpass(enabled: Boolean) {
        _uiState.update { it.copy(radioBandpass = enabled) }
        applyRadioProcessorState()
        viewModelScope.launch {
            equalizerPreferencesRepository.setRadioBandpass(enabled)
        }
    }

    fun setRadioCrackle(enabled: Boolean) {
        _uiState.update { it.copy(radioCrackle = enabled) }
        applyRadioProcessorState()
        viewModelScope.launch {
            equalizerPreferencesRepository.setRadioCrackle(enabled)
        }
    }

    fun setRadioTapeWowEnabled(enabled: Boolean) {
        _uiState.update { it.copy(radioTapeWowEnabled = enabled) }
        applyRadioProcessorState()
        viewModelScope.launch {
            equalizerPreferencesRepository.setRadioTapeWowEnabled(enabled)
        }
    }

    fun setRadioTapeWowDepth(value: Float) {
        val clamped = value.coerceIn(0f, 1000f)
        _uiState.update { it.copy(radioTapeWowDepth = clamped) }
        applyRadioProcessorState()

        persistRadioJob?.cancel()
        persistRadioJob = viewModelScope.launch {
            delay(SLIDER_PERSIST_DEBOUNCE_MS)
            equalizerPreferencesRepository.setRadioTapeWowDepth(clamped.toInt())
        }
    }

    fun setRadioPhaserEnabled(enabled: Boolean) {
        _uiState.update { it.copy(radioPhaserEnabled = enabled) }
        applyRadioProcessorState()
        viewModelScope.launch {
            equalizerPreferencesRepository.setRadioPhaserEnabled(enabled)
        }
    }

    fun setRadioPhaserDepth(value: Float) {
        val clamped = value.coerceIn(0f, 1000f)
        _uiState.update { it.copy(radioPhaserDepth = clamped) }
        applyRadioProcessorState()

        persistRadioJob?.cancel()
        persistRadioJob = viewModelScope.launch {
            delay(SLIDER_PERSIST_DEBOUNCE_MS)
            equalizerPreferencesRepository.setRadioPhaserDepth(clamped.toInt())
        }
    }

    fun setRadioPhaserRate(value: Float) {
        val clamped = value.coerceIn(0f, 1000f)
        _uiState.update { it.copy(radioPhaserRate = clamped) }
        applyRadioProcessorState()

        persistRadioJob?.cancel()
        persistRadioJob = viewModelScope.launch {
            delay(SLIDER_PERSIST_DEBOUNCE_MS)
            equalizerPreferencesRepository.setRadioPhaserRate(clamped.toInt())
        }
    }

    fun setRadioBathroomReverbEnabled(enabled: Boolean) {
        _uiState.update { it.copy(radioBathroomReverbEnabled = enabled) }
        applyRadioProcessorState()
        viewModelScope.launch {
            equalizerPreferencesRepository.setRadioBathroomReverbEnabled(enabled)
        }
    }

    fun setRadioBathroomReverbAmount(value: Float) {
        val clamped = value.coerceIn(0f, 1000f)
        _uiState.update { it.copy(radioBathroomReverbAmount = clamped) }
        applyRadioProcessorState()

        persistRadioJob?.cancel()
        persistRadioJob = viewModelScope.launch {
            delay(SLIDER_PERSIST_DEBOUNCE_MS)
            equalizerPreferencesRepository.setRadioBathroomReverbAmount(clamped.toInt())
        }
    }

        private fun applyRadioProcessorState() {
        val state = _uiState.value
        val active = state.isEnabled && state.radioEffectEnabled
        radioEffectProcessor.enabled = active

        if (active) {
            radioEffectProcessor.setParameters(
                enabled = active,
                noiseLevel = state.radioNoise / 1000f,
                distortionAmount = state.radioDistortion / 1000f,
                radioBand = state.radioBandpass,
                crackleEnabled = state.radioCrackle,
                tapeWowEnabled = state.radioTapeWowEnabled,
                tapeWowDepth = state.radioTapeWowDepth / 1000f,
                phaserEnabled = state.radioPhaserEnabled,
                phaserDepth = state.radioPhaserDepth / 1000f,
                phaserRate = state.radioPhaserRate / 1000f,
                bathroomReverbEnabled = state.radioBathroomReverbEnabled,
                bathroomReverbAmount = state.radioBathroomReverbAmount / 1000f
            )
        }
    }

    fun setBassBoostDismissed(dismissed: Boolean) {
        viewModelScope.launch {
            equalizerPreferencesRepository.setBassBoostDismissed(dismissed)
        }
    }

    fun setVirtualizerDismissed(dismissed: Boolean) {
        viewModelScope.launch {
            equalizerPreferencesRepository.setVirtualizerDismissed(dismissed)
        }
    }

    fun setLoudnessDismissed(dismissed: Boolean) {
        viewModelScope.launch {
            equalizerPreferencesRepository.setLoudnessDismissed(dismissed)
        }
    }

    fun setReverbDismissed(dismissed: Boolean) {
        viewModelScope.launch {
            equalizerPreferencesRepository.setReverbDismissed(dismissed)
        }
    }

    fun updatePinnedPresetsOrder(newOrder: List<String>) {
        viewModelScope.launch {
            equalizerPreferencesRepository.setPinnedPresets(newOrder)
        }
    }

    fun resetPinnedPresetsToDefault() {
        viewModelScope.launch {
            val defaultOrder = EqualizerPreset.ALL_PRESETS.map { it.name }
            equalizerPreferencesRepository.setPinnedPresets(defaultOrder)
        }
    }

    fun togglePinPreset(presetName: String) {
        viewModelScope.launch {
            val currentPinned = _uiState.value.pinnedPresetsNames.toMutableList()
            if (currentPinned.contains(presetName)) {
                currentPinned.remove(presetName)
            } else {
                currentPinned.add(presetName)
            }
            equalizerPreferencesRepository.setPinnedPresets(currentPinned)
        }
    }

    fun reattachToPlayer() {
        viewModelScope.launch {
            val audioSessionId = dualPlayerEngine.getAudioSessionId()
            Timber.tag(TAG).d("Reattaching equalizer to new audio session: $audioSessionId")
            equalizerManager.attachToAudioSessionIfNeeded(audioSessionId, source = "reattach_to_player")
        }
    }

    private suspend fun flushStateToPreferences() {
        val latest = _uiState.value
        runCatching {
            equalizerPreferencesRepository.setEqualizerEnabled(latest.isEnabled)
            equalizerPreferencesRepository.setEqualizerPreset(latest.currentPreset.name)
            equalizerPreferencesRepository.setEqualizerCustomBands(equalizerManager.bandLevels.value)
            equalizerPreferencesRepository.setBassBoostEnabled(latest.bassBoostEnabled)
            equalizerPreferencesRepository.setBassBoostStrength(latest.bassBoostStrength.toInt().coerceIn(0, 1000))
            equalizerPreferencesRepository.setVirtualizerEnabled(latest.virtualizerEnabled)
            equalizerPreferencesRepository.setVirtualizerStrength(latest.virtualizerStrength.toInt().coerceIn(0, 1000))
            equalizerPreferencesRepository.setLoudnessEnhancerEnabled(latest.loudnessEnhancerEnabled)
            equalizerPreferencesRepository.setLoudnessEnhancerStrength(latest.loudnessEnhancerStrength.toInt().coerceIn(0, 1000))
            equalizerPreferencesRepository.setReverbEnabled(latest.reverbEnabled)
            equalizerPreferencesRepository.setReverbStrength(latest.reverbStrength.toInt().coerceIn(0, 1000))
            equalizerPreferencesRepository.setReverbDecay(latest.reverbDecay.toInt().coerceIn(100, 20000))
            equalizerPreferencesRepository.setRadioEnabled(latest.radioEffectEnabled)
            equalizerPreferencesRepository.setRadioNoise(latest.radioNoise.toInt().coerceIn(0, 1000))
            equalizerPreferencesRepository.setRadioDistortion(latest.radioDistortion.toInt().coerceIn(0, 1000))
            equalizerPreferencesRepository.setRadioBandpass(latest.radioBandpass)
            equalizerPreferencesRepository.setRadioCrackle(latest.radioCrackle)
            equalizerPreferencesRepository.setRadioTapeWowEnabled(latest.radioTapeWowEnabled)
            equalizerPreferencesRepository.setRadioTapeWowDepth(latest.radioTapeWowDepth.toInt().coerceIn(0, 1000))
            equalizerPreferencesRepository.setRadioPhaserEnabled(latest.radioPhaserEnabled)
            equalizerPreferencesRepository.setRadioPhaserDepth(latest.radioPhaserDepth.toInt().coerceIn(0, 1000))
            equalizerPreferencesRepository.setRadioPhaserRate(latest.radioPhaserRate.toInt().coerceIn(0, 1000))
            equalizerPreferencesRepository.setRadioBathroomReverbEnabled(latest.radioBathroomReverbEnabled)
            equalizerPreferencesRepository.setRadioBathroomReverbAmount(latest.radioBathroomReverbAmount.toInt().coerceIn(0, 1000))
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Failed to flush equalizer state during onCleared")
        }
    }

    override fun onCleared() {
        persistBandLevelsJob?.cancel()
        persistBassBoostJob?.cancel()
        persistVirtualizerJob?.cancel()
        persistLoudnessJob?.cancel()
        persistReverbJob?.cancel()
        persistRadioJob?.cancel()

        // Execute flush on NonCancellable IO context to protect pending DataStore writes
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            flushStateToPreferences()
        }

        super.onCleared()
        Timber.tag(TAG).d("ViewModel cleared")
    }
}

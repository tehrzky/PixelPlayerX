package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.plugin.PluginDefinition
import com.theveloper.pixelplay.data.plugin.PluginRepository
import com.theveloper.pixelplay.data.service.player.PluginStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DisabledSortMode { DATE_NEWEST, DATE_OLDEST, ALPHA_AZ, ALPHA_ZA }

data class PluginUiModel(
    val definition: PluginDefinition,
    val enabled: Boolean = true,
    val audioFxActive: Boolean = false,
    val paramValues: Map<String, Float> = emptyMap(),
    val macroValues: Map<String, Float> = emptyMap(),
    val nodeEnabled: Map<String, Boolean> = emptyMap(),
    val outputGainDb: Float = 0f,
    val dryWetMix: Float = 100f,
    val fileSizeBytes: Long = 0L,
    val dateAdded: Long = 0L
)

data class PluginManagerUiState(
    val plugins: List<PluginUiModel> = emptyList(),
    val importError: String? = null,
    val disabledSortMode: DisabledSortMode = DisabledSortMode.DATE_NEWEST,
    val isMultiSelectMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val isArrangeMode: Boolean = false,
    val batchImportSummary: String? = null
) {
    /** Enabled plugins in strict DSP execution order — this is the actual audio
     * topology, so it is never re-sorted automatically. Only manual drag reorder
     * (Arrange mode) is allowed to change this order. */
    val activePlugins: List<PluginUiModel> get() = plugins.filter { it.enabled }

    /** Disabled plugins, ordered by whichever sort the user picked in the divider
     * menu. This list has no bearing on audio topology since none of these are
     * hooked into the DSP graph. */
    val disabledPlugins: List<PluginUiModel> get() {
        val disabled = plugins.filterNot { it.enabled }
        return when (disabledSortMode) {
            DisabledSortMode.DATE_NEWEST -> disabled.sortedByDescending { it.dateAdded }
            DisabledSortMode.DATE_OLDEST -> disabled.sortedBy { it.dateAdded }
            DisabledSortMode.ALPHA_AZ -> disabled.sortedBy { it.definition.name.lowercase() }
            DisabledSortMode.ALPHA_ZA -> disabled.sortedByDescending { it.definition.name.lowercase() }
        }
    }
}

@HiltViewModel
class PluginManagerViewModel @Inject constructor(
    private val pluginRepository: PluginRepository,
    private val pluginStateHolder: PluginStateHolder,
    private val dualPlayerEngine: com.theveloper.pixelplay.data.service.player.DualPlayerEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(PluginManagerUiState())
    val uiState: StateFlow<PluginManagerUiState> = _uiState.asStateFlow()

    init { observePlugins() }

    private fun observePlugins() {
        viewModelScope.launch {
            pluginRepository.pluginOrderFlow.collect { orderedIds ->
                val installed = pluginRepository.listInstalledPlugins().associateBy { it.id }
                val ordered = orderedIds.mapNotNull { installed[it] }

                _uiState.update { state ->
                    state.copy(plugins = ordered.map { def ->
                        PluginUiModel(
                            definition = def,
                            enabled = true,
                            paramValues = def.chain.flatMap { it.params.entries }.associate { it.key to it.value.default },
                            macroValues = def.macros.associate { it.id to it.default },
                            nodeEnabled = def.chain.mapIndexed { i, n -> n.effectiveId(i) to true }.toMap(),
                            outputGainDb = def.master.outputGainDb,
                            dryWetMix = def.master.dryWetMix,
                            fileSizeBytes = pluginRepository.pluginFileSizeBytes(def.id)
                        )
                    })
                }

                ordered.forEach { def ->
                    launch {
                        pluginRepository.pluginEnabledFlow(def.id).collect { enabled ->
                            updatePlugin(def.id) { it.copy(enabled = enabled) }
                        }
                    }
                    launch {
                        pluginRepository.audioFxActiveFlow(def.id).collect { active ->
                            updatePlugin(def.id) { it.copy(audioFxActive = active) }
                        }
                    }
                    launch {
                        pluginRepository.dateAddedFlow(def.id).collect { added ->
                            updatePlugin(def.id) { it.copy(dateAdded = added) }
                        }
                    }
                    def.chain.forEach { node -> node.params.forEach { (key, paramDef) ->
                        launch {
                            combine(
                                pluginRepository.pluginParamFlow(def.id, key, paramDef.default),
                                pluginRepository.overriddenParamsFlow(def.id)
                            ) { value, overriddenSet -> value to (key in overriddenSet) }
                                .collect { (value, isOverridden) ->
                                    updatePlugin(def.id) { it.copy(paramValues = it.paramValues + (key to value)) }
                                    val fullKey = "${def.id}:$key"
                                    if (isOverridden) {
                                        pluginStateHolder.paramValues[fullKey] = value
                                        pluginStateHolder.paramOverridden.add(fullKey)
                                    } else {
                                        pluginStateHolder.paramValues.remove(fullKey)
                                        pluginStateHolder.paramOverridden.remove(fullKey)
                                    }
                                }
                        }
                    } }
                    def.macros.forEach { macro ->
                        launch {
                            pluginRepository.macroFlow(def.id, macro.id, macro.default).collect { value ->
                                updatePlugin(def.id) { it.copy(macroValues = it.macroValues + (macro.id to value)) }
                            }
                        }
                    }
                    def.chain.forEachIndexed { i, node ->
                        val nodeId = node.effectiveId(i)
                        launch {
                            pluginRepository.nodeEnabledFlow(def.id, nodeId).collect { enabled ->
                                updatePlugin(def.id) { it.copy(nodeEnabled = it.nodeEnabled + (nodeId to enabled)) }
                            }
                        }
                    }
                    launch {
                        pluginRepository.masterFlow(def.id, "outputGainDb", def.master.outputGainDb).collect { value ->
                            updatePlugin(def.id) { it.copy(outputGainDb = value) }
                        }
                    }
                    launch {
                        pluginRepository.masterFlow(def.id, "dryWetMix", def.master.dryWetMix).collect { value ->
                            updatePlugin(def.id) { it.copy(dryWetMix = value) }
                        }
                    }
                }
            }
        }
    }

    private fun updatePlugin(id: String, transform: (PluginUiModel) -> PluginUiModel) {
        _uiState.update { state ->
            state.copy(plugins = state.plugins.map { if (it.definition.id == id) transform(it) else it })
        }
    }

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        // No rebuild needed here — PluginAudioProcessor already reads enabled state
        // live every audio buffer via PluginStateHolder, so this takes effect
        // instantly with zero rebuild pause. The rebuild call I added here in an
        // earlier pass was unnecessary and was itself a source of avoidable pauses.
        viewModelScope.launch {
            pluginRepository.setPluginEnabled(pluginId, enabled)
            // Safety default: turning the Manager switch ON must never also turn
            // audio processing on. The Audio FX page toggle always resets to off
            // so nothing starts playing through the effect until the user
            // deliberately switches it on there.
            if (enabled) pluginRepository.setAudioFxActive(pluginId, false)
        }
    }

    fun setAudioFxActiveLive(pluginId: String, active: Boolean) {
        pluginStateHolder.audioFxActiveMap[pluginId] = active
    }
    fun setAudioFxActive(pluginId: String, active: Boolean) {
        pluginStateHolder.audioFxActiveMap[pluginId] = active
        viewModelScope.launch { pluginRepository.setAudioFxActive(pluginId, active) }
    }

    fun setPluginParamLive(pluginId: String, key: String, value: Float) {
        pluginStateHolder.paramValues["$pluginId:$key"] = value
        pluginStateHolder.paramOverridden.add("$pluginId:$key")
    }
    fun setPluginParam(pluginId: String, key: String, value: Float) {
        viewModelScope.launch {
            pluginRepository.setPluginParam(pluginId, key, value)
            pluginRepository.setParamOverridden(pluginId, key, true)
        }
    }

    fun setMacroLive(pluginId: String, macroId: String, value: Float) {
        pluginStateHolder.macroValues["$pluginId:$macroId"] = value
    }
    fun setMacro(pluginId: String, macroId: String, value: Float) {
        viewModelScope.launch { pluginRepository.setMacro(pluginId, macroId, value) }
    }

    fun setMasterLive(pluginId: String, key: String, value: Float) {
        pluginStateHolder.masterOverrides["$pluginId:$key"] = value
    }
    fun setMaster(pluginId: String, key: String, value: Float) {
        viewModelScope.launch { pluginRepository.setMaster(pluginId, key, value) }
    }

    fun setNodeEnabled(pluginId: String, nodeId: String, enabled: Boolean) {
        pluginStateHolder.nodeEnabledMap["$pluginId:$nodeId"] = enabled
        viewModelScope.launch { pluginRepository.setNodeEnabled(pluginId, nodeId, enabled) }
    }

    fun importPlugin(rawJson: String) {
        viewModelScope.launch {
            try {
                pluginRepository.importPlugin(rawJson)
                _uiState.update { it.copy(importError = null) }
                dualPlayerEngine.refreshAudioFxPluginChain()
            } catch (e: IllegalArgumentException) {
                _uiState.update { it.copy(importError = e.message) }
            }
        }
    }

    /** Handles both multi-selected .json files and .zip archives in one pass.
     * Every source is parsed/validated first; the audio graph rebuilds exactly
     * once at the end regardless of how many plugins came in (batch-imported
     * plugins always land disabled, so in practice this rebuild is a no-op for
     * topology — it only exists to be safe if a rebuild happens to coincide). */
    fun importBatch(sources: List<Pair<String, ByteArray>>) {
        viewModelScope.launch {
            var successCount = 0
            val skipped = mutableListOf<String>()
            sources.forEach { (fileName, bytes) ->
                pluginRepository.parseBatchImportSource(fileName, bytes).forEach { entry ->
                    if (entry.rawJson != null) {
                        try {
                            pluginRepository.importPlugin(entry.rawJson)
                            successCount++
                        } catch (e: IllegalArgumentException) {
                            skipped.add("${entry.fileName}: ${e.message}")
                        }
                    } else {
                        skipped.add("${entry.fileName}: ${entry.skippedReason}")
                    }
                }
            }
            val summary = buildString {
                append("Imported $successCount plugin${if (successCount == 1) "" else "s"} successfully")
                if (skipped.isNotEmpty()) append(", ${skipped.size} skipped")
            }
            _uiState.update { it.copy(batchImportSummary = summary) }
            if (successCount > 0) dualPlayerEngine.refreshAudioFxPluginChain()
        }
    }

    fun dismissBatchImportSummary() {
        _uiState.update { it.copy(batchImportSummary = null) }
    }

    fun deletePlugin(pluginId: String) {
        viewModelScope.launch {
            // Only rebuild if the deleted plugin was actually enabled (i.e. actually
            // processing audio right now). Deleting an already-disabled plugin changes
            // nothing audible, so skip the pause entirely and let it drop out on the
            // next natural rebuild (song change, Hi-Fi toggle, etc).
            val wasEnabled = pluginStateHolder.isEnabled(pluginId)
            pluginRepository.deletePlugin(pluginId)
            if (wasEnabled) dualPlayerEngine.refreshAudioFxPluginChain()
        }
    }

    fun movePlugin(pluginId: String, delta: Int) {
        viewModelScope.launch {
            val current = _uiState.value.plugins.map { it.definition.id }.toMutableList()
            val index = current.indexOf(pluginId)
            val newIndex = (index + delta).coerceIn(0, current.size - 1)
            if (index == -1 || index == newIndex) return@launch
            current.removeAt(index)
            current.add(newIndex, pluginId)
            pluginRepository.setPluginOrder(current)
        }
    }

    /** Commits a full new top-level plugin order (id list across ALL plugins,
     * enabled and disabled) after a drag-and-drop reorder in Arrange mode. */
    fun commitPluginOrder(orderedIds: List<String>) {
        viewModelScope.launch { pluginRepository.setPluginOrder(orderedIds) }
    }

    fun setArrangeMode(enabled: Boolean) {
        _uiState.update { it.copy(isArrangeMode = enabled) }
    }

    fun setDisabledSortMode(mode: DisabledSortMode) {
        _uiState.update { it.copy(disabledSortMode = mode) }
    }

    fun setMultiSelectMode(enabled: Boolean) {
        _uiState.update { it.copy(isMultiSelectMode = enabled, selectedIds = if (enabled) it.selectedIds else emptySet()) }
    }

    fun toggleSelected(pluginId: String) {
        _uiState.update { state ->
            val next = state.selectedIds.toMutableSet()
            if (!next.add(pluginId)) next.remove(pluginId)
            state.copy(selectedIds = next)
        }
    }

    fun selectAll() {
        _uiState.update { it.copy(selectedIds = it.plugins.map { p -> p.definition.id }.toSet()) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    /** Enables/disables every selected plugin, then rebuilds the audio graph
     * exactly once at the end — a single atomic transaction instead of one
     * rebuild per plugin, so a 10-plugin batch doesn't cause 10 audio pauses. */
    fun batchSetEnabled(pluginIds: Set<String>, enabled: Boolean) {
        viewModelScope.launch {
            pluginIds.forEach { id ->
                pluginRepository.setPluginEnabled(id, enabled)
                // Same safety default as the single-plugin path: batch-enabling
                // must not batch-activate audio on every one of them at once.
                if (enabled) pluginRepository.setAudioFxActive(id, false)
            }
            dualPlayerEngine.refreshAudioFxPluginChain()
            _uiState.update { it.copy(isMultiSelectMode = false, selectedIds = emptySet()) }
        }
    }

    /** Deletes every selected plugin, then rebuilds the audio graph exactly once
     * — same atomic-batch reasoning as batchSetEnabled. */
    fun batchDelete(pluginIds: Set<String>) {
        viewModelScope.launch {
            val anyWasEnabled = pluginIds.any { pluginStateHolder.isEnabled(it) }
            pluginIds.forEach { id -> pluginRepository.deletePlugin(id) }
            if (anyWasEnabled) dualPlayerEngine.refreshAudioFxPluginChain()
            _uiState.update { it.copy(isMultiSelectMode = false, selectedIds = emptySet()) }
        }
    }

    fun resetToDefaults(pluginId: String) {
        val plugin = _uiState.value.plugins.find { it.definition.id == pluginId } ?: return
        // Clear in-memory live state synchronously first (cheap, no I/O) so the
        // audio thread reflects defaults instantly, then persist in one batch.
        plugin.definition.chain.forEach { node -> node.params.keys.forEach { key ->
            pluginStateHolder.paramValues.remove("$pluginId:$key")
            pluginStateHolder.paramOverridden.remove("$pluginId:$key")
        } }
        plugin.definition.macros.forEach { macro -> pluginStateHolder.macroValues.remove("$pluginId:${macro.id}") }
        plugin.definition.chain.forEachIndexed { i, node -> pluginStateHolder.nodeEnabledMap.remove("$pluginId:${node.effectiveId(i)}") }
        pluginStateHolder.masterOverrides.remove("$pluginId:outputGainDb")
        pluginStateHolder.masterOverrides.remove("$pluginId:dryWetMix")

        viewModelScope.launch { pluginRepository.resetPluginToDefaults(plugin.definition) }
    }

    fun dismissError() {
        _uiState.update { it.copy(importError = null) }
    }
}

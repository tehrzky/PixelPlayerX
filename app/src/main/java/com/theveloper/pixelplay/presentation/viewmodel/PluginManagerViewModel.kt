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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PluginUiModel(
    val definition: PluginDefinition,
    val enabled: Boolean = true,
    val paramValues: Map<String, Float> = emptyMap(),
    val macroValues: Map<String, Float> = emptyMap(),
    val nodeEnabled: Map<String, Boolean> = emptyMap(),
    val outputGainDb: Float = 0f,
    val dryWetMix: Float = 100f
)

data class PluginManagerUiState(
    val plugins: List<PluginUiModel> = emptyList(),
    val importError: String? = null
)

@HiltViewModel
class PluginManagerViewModel @Inject constructor(
    private val pluginRepository: PluginRepository,
    private val pluginStateHolder: PluginStateHolder
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
                            dryWetMix = def.master.dryWetMix
                        )
                    })
                }

                ordered.forEach { def ->
                    launch {
                        pluginRepository.pluginEnabledFlow(def.id).collect { enabled ->
                            updatePlugin(def.id) { it.copy(enabled = enabled) }
                        }
                    }
                    def.chain.forEach { node -> node.params.forEach { (key, paramDef) ->
                        launch {
                            pluginRepository.pluginParamFlow(def.id, key, paramDef.default).collect { value ->
                                updatePlugin(def.id) { it.copy(paramValues = it.paramValues + (key to value)) }
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
        viewModelScope.launch { pluginRepository.setPluginEnabled(pluginId, enabled) }
    }

    fun setPluginParamLive(pluginId: String, key: String, value: Float) {
        pluginStateHolder.paramValues["$pluginId:$key"] = value
        pluginStateHolder.paramOverridden.add("$pluginId:$key")
    }
    fun setPluginParam(pluginId: String, key: String, value: Float) {
        viewModelScope.launch { pluginRepository.setPluginParam(pluginId, key, value) }
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
            } catch (e: IllegalArgumentException) {
                _uiState.update { it.copy(importError = e.message) }
            }
        }
    }

    fun deletePlugin(pluginId: String) {
        viewModelScope.launch { pluginRepository.deletePlugin(pluginId) }
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

    fun resetToDefaults(pluginId: String) {
        val plugin = _uiState.value.plugins.find { it.definition.id == pluginId } ?: return
        viewModelScope.launch {
            plugin.definition.chain.forEach { node -> node.params.forEach { (key, paramDef) ->
                pluginStateHolder.paramValues.remove("$pluginId:$key")
                pluginStateHolder.paramOverridden.remove("$pluginId:$key")
                pluginRepository.setPluginParam(pluginId, key, paramDef.default)
            } }
            plugin.definition.macros.forEach { macro ->
                pluginStateHolder.macroValues["$pluginId:${macro.id}"] = macro.default
                pluginRepository.setMacro(pluginId, macro.id, macro.default)
            }
            plugin.definition.chain.forEachIndexed { i, node ->
                val nodeId = node.effectiveId(i)
                pluginStateHolder.nodeEnabledMap["$pluginId:$nodeId"] = true
                pluginRepository.setNodeEnabled(pluginId, nodeId, true)
            }
            pluginStateHolder.masterOverrides.remove("$pluginId:outputGainDb")
            pluginStateHolder.masterOverrides.remove("$pluginId:dryWetMix")
            pluginRepository.setMaster(pluginId, "outputGainDb", plugin.definition.master.outputGainDb)
            pluginRepository.setMaster(pluginId, "dryWetMix", plugin.definition.master.dryWetMix)
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(importError = null) }
    }
}

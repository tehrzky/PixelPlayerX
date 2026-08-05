package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.plugin.PluginDefinition
import com.theveloper.pixelplay.data.plugin.PluginRepository
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
    val paramValues: Map<String, Float> = emptyMap()
)

data class PluginManagerUiState(
    val plugins: List<PluginUiModel> = emptyList(),
    val importError: String? = null
)

@HiltViewModel
class PluginManagerViewModel @Inject constructor(
    private val pluginRepository: PluginRepository,
    private val pluginStateHolder: com.theveloper.pixelplay.data.service.player.PluginStateHolder
) : ViewModel() {

    private val _uiState = MutableStateFlow(PluginManagerUiState())
    val uiState: StateFlow<PluginManagerUiState> = _uiState.asStateFlow()

    init { observePlugins() }

    private fun observePlugins() {
        viewModelScope.launch {
            pluginRepository.pluginOrderFlow.collect { orderedIds ->
                val installed = pluginRepository.listInstalledPlugins().associateBy { it.id }
                val ordered = orderedIds.mapNotNull { installed[it] }

                // Seed immediately with defaults so the UI isn't blank while the
                // per-param DataStore flows below are still warming up.
                _uiState.update { state ->
                    state.copy(plugins = ordered.map { def ->
                        PluginUiModel(
                            definition = def,
                            enabled = true,
                            paramValues = def.chain.flatMap { it.params.entries }
                                .associate { it.key to it.value.default }
                        )
                    })
                }

                ordered.forEach { def ->
                    launch {
                        pluginRepository.pluginEnabledFlow(def.id).collect { enabled ->
                            updatePlugin(def.id) { it.copy(enabled = enabled) }
                        }
                    }
                    def.chain.forEach { node ->
                        node.params.forEach { (key, paramDef) ->
                            launch {
                                pluginRepository.pluginParamFlow(def.id, key, paramDef.default).collect { value ->
                                    updatePlugin(def.id) { it.copy(paramValues = it.paramValues + (key to value)) }
                                }
                            }
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
    }

    fun setPluginParam(pluginId: String, key: String, value: Float) {
        viewModelScope.launch { pluginRepository.setPluginParam(pluginId, key, value) }
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

    fun dismissError() {
        _uiState.update { it.copy(importError = null) }
    }
}

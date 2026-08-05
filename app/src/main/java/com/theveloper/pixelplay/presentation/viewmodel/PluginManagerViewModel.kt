package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.plugin.PluginDefinition
import com.theveloper.pixelplay.data.plugin.PluginRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PluginManagerUiState(
    val plugins: List<PluginDefinition> = emptyList(),
    val importError: String? = null
)

@HiltViewModel
class PluginManagerViewModel @Inject constructor(
    private val pluginRepository: PluginRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PluginManagerUiState())
    val uiState: StateFlow<PluginManagerUiState> = _uiState.asStateFlow()

    init { refresh() }

    private fun refresh() {
        viewModelScope.launch {
            val orderedIds = pluginRepository.pluginOrderFlow
            val installed = pluginRepository.listInstalledPlugins().associateBy { it.id }
            orderedIds.collect { ids ->
                val ordered = ids.mapNotNull { installed[it] }
                val unordered = installed.values.filter { it.id !in ids }
                _uiState.update { it.copy(plugins = ordered + unordered) }
            }
        }
    }

    fun importPlugin(rawJson: String) {
        viewModelScope.launch {
            try {
                pluginRepository.importPlugin(rawJson)
                _uiState.update { it.copy(importError = null) }
                reloadList()
            } catch (e: IllegalArgumentException) {
                _uiState.update { it.copy(importError = e.message) }
            }
        }
    }

    fun deletePlugin(pluginId: String) {
        viewModelScope.launch {
            pluginRepository.deletePlugin(pluginId)
            reloadList()
        }
    }

    fun movePlugin(pluginId: String, delta: Int) {
        viewModelScope.launch {
            val current = _uiState.value.plugins.map { it.id }.toMutableList()
            val index = current.indexOf(pluginId)
            val newIndex = (index + delta).coerceIn(0, current.size - 1)
            if (index == -1 || index == newIndex) return@launch
            current.removeAt(index)
            current.add(newIndex, pluginId)
            pluginRepository.setPluginOrder(current)
            reloadList()
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(importError = null) }
    }

    private fun reloadList() {
        val installed = pluginRepository.listInstalledPlugins().associateBy { it.id }
        viewModelScope.launch {
            val ids = pluginRepository.pluginOrderFlow
            ids.collect { orderedIds ->
                val ordered = orderedIds.mapNotNull { installed[it] }
                _uiState.update { it.copy(plugins = ordered) }
                return@collect
            }
        }
    }
}

private inline fun MutableStateFlow<PluginManagerUiState>.update(f: (PluginManagerUiState) -> PluginManagerUiState) {
    value = f(value)
}

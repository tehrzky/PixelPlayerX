package com.theveloper.pixelplay.data.service.player

import com.theveloper.pixelplay.data.plugin.PluginDefinition
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginStateHolder @Inject constructor() {
    @Volatile var activePlugins: List<PluginDefinition> = emptyList()

    val enabledMap = ConcurrentHashMap<String, Boolean>()       // pluginId -> master enabled
    val paramValues = ConcurrentHashMap<String, Float>()        // "pluginId:paramKey" -> raw override
    val macroValues = ConcurrentHashMap<String, Float>()        // "pluginId:macroId" -> value
    val nodeEnabledMap = ConcurrentHashMap<String, Boolean>()   // "pluginId:nodeId" -> enabled (bypass)
    val masterOverrides = ConcurrentHashMap<String, Float>()    // "pluginId:outputGainDb" / "pluginId:dryWetMix"

    fun isEnabled(pluginId: String): Boolean = enabledMap[pluginId] ?: true
    fun isNodeEnabled(pluginId: String, nodeId: String): Boolean = nodeEnabledMap["$pluginId:$nodeId"] ?: true
    fun paramValue(pluginId: String, key: String, default: Float): Float = paramValues["$pluginId:$key"] ?: default
    fun macroValue(pluginId: String, macroId: String, default: Float): Float = macroValues["$pluginId:$macroId"] ?: default
    fun masterValue(pluginId: String, key: String, default: Float): Float = masterOverrides["$pluginId:$key"] ?: default
}

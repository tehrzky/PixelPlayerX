package com.theveloper.pixelplay.data.service.player

import com.theveloper.pixelplay.data.plugin.PluginDefinition
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginStateHolder @Inject constructor() {
    @Volatile var activePlugins: List<PluginDefinition> = emptyList()

    val enabledMap = ConcurrentHashMap<String, Boolean>()       // pluginId -> Manager master enabled
    val audioFxActiveMap = ConcurrentHashMap<String, Boolean>() // pluginId -> Audio FX page bypass toggle
    val paramValues = ConcurrentHashMap<String, Float>()        // "pluginId:paramKey" -> raw override
    val macroValues = ConcurrentHashMap<String, Float>()        // "pluginId:macroId" -> value
    val paramOverridden: MutableSet<String> = ConcurrentHashMap.newKeySet() // "pluginId:paramKey" explicitly touched by user this session
    val nodeEnabledMap = ConcurrentHashMap<String, Boolean>()   // "pluginId:nodeId" -> enabled (bypass)
    val masterOverrides = ConcurrentHashMap<String, Float>()    // "pluginId:outputGainDb" / "pluginId:dryWetMix"

    // Defaults to false, not true: a plugin that just got added to the chain
    // (fresh import, or Manager just re-enabled it) has an async DataStore
    // read in flight for its real flag. Defaulting to "off" during that brief
    // window means silence instead of a moment of unbypassed audio — this is
    // the fix for the "audio glitch on import" safety requirement.
    fun isEnabled(pluginId: String): Boolean = enabledMap[pluginId] ?: false
    // The actual gate PluginAudioProcessor reads: audio only flows through a
    // node when BOTH the Manager switch and the Audio FX page's own bypass
    // toggle are on. Manager-disabled always wins regardless of the page toggle.
    fun isProcessingActive(pluginId: String): Boolean = isEnabled(pluginId) && (audioFxActiveMap[pluginId] ?: false)
    fun isNodeEnabled(pluginId: String, nodeId: String): Boolean = nodeEnabledMap["$pluginId:$nodeId"] ?: true
    fun paramValue(pluginId: String, key: String, default: Float): Float = paramValues["$pluginId:$key"] ?: default
    fun macroValue(pluginId: String, macroId: String, default: Float): Float = macroValues["$pluginId:$macroId"] ?: default
    fun masterValue(pluginId: String, key: String, default: Float): Float = masterOverrides["$pluginId:$key"] ?: default
}

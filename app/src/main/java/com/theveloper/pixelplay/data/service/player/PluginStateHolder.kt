package com.theveloper.pixelplay.data.service.player

import com.theveloper.pixelplay.data.plugin.PluginDefinition
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginStateHolder @Inject constructor() {
    // Ordered list of installed plugin definitions, snapshotted at each player
    // build. Never mutated in place — always replaced wholesale — so the audio
    // thread can read it without locking.
    @Volatile var activePlugins: List<PluginDefinition> = emptyList()

    val enabledMap = ConcurrentHashMap<String, Boolean>()
    val paramValues = ConcurrentHashMap<String, Float>()

    fun isEnabled(pluginId: String): Boolean = enabledMap[pluginId] ?: true
    fun paramValue(pluginId: String, key: String, default: Float): Float =
        paramValues["$pluginId:$key"] ?: default
}

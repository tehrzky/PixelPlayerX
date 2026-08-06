package com.theveloper.pixelplay.data.plugin

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pluginsDir: File get() = File(context.filesDir, "audio_fx_plugins").apply { mkdirs() }
    private val supportedNodeTypes = setOf("bandpass", "distortion", "noise", "wobble", "reverb", "bitcrusher", "delay", "compressor", "pitchshift")
    // Kept for future validation clarity; actual routing lives in PluginAudioProcessor's
    // `when`. Adding a type here requires also adding the matching DSP node class and
    // wiring it into that `when` block.

    private object Keys {
        val PLUGIN_ORDER = stringPreferencesKey("audio_fx_plugin_order")
    }

    fun parseAndValidate(rawJson: String): PluginDefinition {
        val def = try {
            json.decodeFromString<PluginDefinition>(rawJson)
        } catch (e: Exception) {
            throw IllegalArgumentException("Couldn't read this file as a plugin (${e.message ?: "invalid JSON"})")
        }
        require(def.id.isNotBlank()) { "Plugin is missing an \"id\"" }
        require(def.name.isNotBlank()) { "Plugin is missing a \"name\"" }
        require(def.chain.isNotEmpty()) { "Plugin has an empty effect chain" }
        def.chain.forEach { node ->
            require(node.type in supportedNodeTypes) {
                "Unknown effect type \"${node.type}\" — supported types are: ${supportedNodeTypes.joinToString()}"
            }
        }
        return def
    }

    suspend fun importPlugin(rawJson: String): PluginDefinition {
        val def = parseAndValidate(rawJson)
        File(pluginsDir, "${def.id}.json").writeText(rawJson)
        dataStore.edit { prefs ->
            val order = (prefs[Keys.PLUGIN_ORDER] ?: "").split(",").filter { it.isNotBlank() }
            if (def.id !in order) prefs[Keys.PLUGIN_ORDER] = (order + def.id).joinToString(",")
        }
        // Newly imported plugins start disabled — avoid an unexpected volume/
        // processing shift on import until the user explicitly turns it on.
        setPluginEnabled(def.id, false)
        return def
    }

    fun macroFlow(pluginId: String, macroId: String, default: Float): Flow<Float> = dataStore.data.map { prefs ->
        prefs[floatPreferencesKey("plugin_$pluginId:macro:$macroId")] ?: default
    }

    suspend fun setMacro(pluginId: String, macroId: String, value: Float) {
        dataStore.edit { prefs -> prefs[floatPreferencesKey("plugin_$pluginId:macro:$macroId")] = value }
    }

    fun nodeEnabledFlow(pluginId: String, nodeId: String): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[booleanPreferencesKey("plugin_$pluginId:node:$nodeId:enabled")] ?: true
    }

    suspend fun setNodeEnabled(pluginId: String, nodeId: String, enabled: Boolean) {
        dataStore.edit { prefs -> prefs[booleanPreferencesKey("plugin_$pluginId:node:$nodeId:enabled")] = enabled }
    }

    fun masterFlow(pluginId: String, key: String, default: Float): Flow<Float> = dataStore.data.map { prefs ->
        prefs[floatPreferencesKey("plugin_$pluginId:master:$key")] ?: default
    }

    suspend fun setMaster(pluginId: String, key: String, value: Float) {
        dataStore.edit { prefs -> prefs[floatPreferencesKey("plugin_$pluginId:master:$key")] = value }
    }

    fun listInstalledPlugins(): List<PluginDefinition> {
        val files = pluginsDir.listFiles { f -> f.extension == "json" } ?: emptyArray()
        return files.mapNotNull { f -> try { parseAndValidate(f.readText()) } catch (e: Exception) { null } }
    }

    suspend fun deletePlugin(pluginId: String) {
        File(pluginsDir, "$pluginId.json").delete()
        dataStore.edit { prefs ->
            val order = (prefs[Keys.PLUGIN_ORDER] ?: "").split(",").filter { it.isNotBlank() && it != pluginId }
            prefs[Keys.PLUGIN_ORDER] = order.joinToString(",")
        }
    }

    suspend fun setPluginOrder(orderedIds: List<String>) {
        dataStore.edit { prefs -> prefs[Keys.PLUGIN_ORDER] = orderedIds.joinToString(",") }
    }

    val pluginOrderFlow: Flow<List<String>> = dataStore.data.map { prefs ->
        (prefs[Keys.PLUGIN_ORDER] ?: "").split(",").filter { it.isNotBlank() }
    }

    fun pluginEnabledFlow(pluginId: String): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[booleanPreferencesKey("plugin_$pluginId:enabled")] ?: true
    }

    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        dataStore.edit { prefs -> prefs[booleanPreferencesKey("plugin_$pluginId:enabled")] = enabled }
    }

    fun pluginParamFlow(pluginId: String, paramKey: String, default: Float): Flow<Float> = dataStore.data.map { prefs ->
        prefs[floatPreferencesKey("plugin_$pluginId:$paramKey")] ?: default
    }

    suspend fun setPluginParam(pluginId: String, paramKey: String, value: Float) {
        dataStore.edit { prefs -> prefs[floatPreferencesKey("plugin_$pluginId:$paramKey")] = value }
    }
}

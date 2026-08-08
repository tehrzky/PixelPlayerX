package com.theveloper.pixelplay.data.plugin

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** One parsed entry out of a batch import (multi-file .json select, or a .zip
 * archive). "skipped" carries a human-readable reason when a file wasn't a
 * valid plugin, so the batch summary dialog can show the user what happened. */
data class BatchImportEntry(
    val fileName: String,
    val rawJson: String? = null,
    val skippedReason: String? = null
)

@Singleton
class PluginRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pluginsDir: File get() = File(context.filesDir, "audio_fx_plugins").apply { mkdirs() }
    private val supportedNodeTypes = setOf(
        "bandpass", "distortion", "noise", "wobble", "reverb", "bitcrusher", "delay",
        "compressor", "pitchshift", "pitch_shifter", "gain", "mono_utility",
        "stereo_widener", "parametric_eq", "shelving_eq", "limiter", "gate",
        "chorus", "tape_saturator", "dc_blocker", "vinyl_dropout",
        "phaser", "exciter", "envelope_follower", "de_esser"
    )
    // Kept for future validation clarity; actual routing lives in PluginAudioProcessor's
    // `when`. Adding a type here requires also adding the matching DSP node class and
    // wiring it into that `when` block.

    private object Keys {
        val PLUGIN_ORDER = stringPreferencesKey("audio_fx_plugin_order")
    }

    private fun dateAddedKey(pluginId: String) = longPreferencesKey("plugin_$pluginId:date_added")

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
            // Keep the original date-added if this id already existed (a re-import
            // is an update, not a fresh add) — only stamp it the first time.
            if (prefs[dateAddedKey(def.id)] == null) prefs[dateAddedKey(def.id)] = System.currentTimeMillis()
        }
        // Newly imported plugins start disabled — avoid an unexpected volume/
        // processing shift on import until the user explicitly turns it on.
        setPluginEnabled(def.id, false)
        return def
    }

    /** Parses raw bytes from a file picker selection into batch entries. If the
     * bytes are a .zip archive, extracts every .json entry inside; otherwise
     * treats the whole thing as one plugin file. Never throws — invalid entries
     * come back with a skippedReason instead, for the batch summary dialog. */
    fun parseBatchImportSource(fileName: String, bytes: ByteArray): List<BatchImportEntry> {
        val looksLikeZip = fileName.endsWith(".zip", ignoreCase = true) ||
            (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte())
        if (!looksLikeZip) {
            return listOf(BatchImportEntry(fileName = fileName, rawJson = bytes.toString(Charsets.UTF_8)))
        }
        val entries = mutableListOf<BatchImportEntry>()
        try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".json", ignoreCase = true)) {
                        val out = ByteArrayOutputStream()
                        zip.copyTo(out)
                        entries.add(BatchImportEntry(fileName = entry.name, rawJson = out.toString("UTF-8")))
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            entries.add(BatchImportEntry(fileName = fileName, skippedReason = "Couldn't read .zip archive (${e.message ?: "corrupt file"})"))
        }
        if (entries.isEmpty()) {
            entries.add(BatchImportEntry(fileName = fileName, skippedReason = "No .json plugin files found inside the archive"))
        }
        return entries
    }

    fun pluginFileSizeBytes(pluginId: String): Long = File(pluginsDir, "$pluginId.json").length()

    fun dateAddedFlow(pluginId: String): Flow<Long> = dataStore.data.map { prefs ->
        prefs[dateAddedKey(pluginId)] ?: 0L
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

    /** Resets every param, macro, node-bypass, and master value for one plugin in a
     * single atomic DataStore transaction — one disk write, one Flow emission,
     * instead of ~20 separate ones (that fan-out was the actual cause of Reset lag). */
    suspend fun resetPluginToDefaults(def: PluginDefinition) {
        dataStore.edit { prefs ->
            def.chain.forEach { node -> node.params.forEach { (key, paramDef) ->
                prefs[floatPreferencesKey("plugin_${def.id}:$key")] = paramDef.default
            } }
            def.macros.forEach { macro ->
                prefs[floatPreferencesKey("plugin_${def.id}:macro:${macro.id}")] = macro.default
            }
            def.chain.forEachIndexed { i, node ->
                prefs[booleanPreferencesKey("plugin_${def.id}:node:${node.effectiveId(i)}:enabled")] = true
            }
            prefs[floatPreferencesKey("plugin_${def.id}:master:outputGainDb")] = def.master.outputGainDb
            prefs[floatPreferencesKey("plugin_${def.id}:master:dryWetMix")] = def.master.dryWetMix
            prefs[stringPreferencesKey("plugin_${def.id}:overridden_params")] = ""
        }
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

    /** Separate from "enabled" above (that's the Plugin Manager master switch,
     * controlling whether the card exists on the Audio FX page at all). This is
     * the Audio FX page's own on-page bypass toggle — flipping it never touches
     * Manager state or triggers a DSP graph rebuild, it's a live audio-thread
     * bypass exactly like a node-level bypass, just at the whole-plugin level.
     * Defaults to false: a plugin that's never been touched on the Audio FX
     * page is bypassed there, satisfying "enabling in Manager must not also
     * start processing audio." */
    fun audioFxActiveFlow(pluginId: String): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[booleanPreferencesKey("plugin_$pluginId:audiofx_active")] ?: false
    }

    suspend fun setAudioFxActive(pluginId: String, active: Boolean) {
        dataStore.edit { prefs -> prefs[booleanPreferencesKey("plugin_$pluginId:audiofx_active")] = active }
    }

    fun pluginParamFlow(pluginId: String, paramKey: String, default: Float): Flow<Float> = dataStore.data.map { prefs ->
        prefs[floatPreferencesKey("plugin_$pluginId:$paramKey")] ?: default
    }

    suspend fun setPluginParam(pluginId: String, paramKey: String, value: Float) {
        dataStore.edit { prefs -> prefs[floatPreferencesKey("plugin_$pluginId:$paramKey")] = value }
    }

    /** Persists which raw params the user explicitly overrode (vs. macro-driven),
     * so "manual override beats macro" survives app restart instead of resetting. */
    fun overriddenParamsFlow(pluginId: String): Flow<Set<String>> = dataStore.data.map { prefs ->
        (prefs[stringPreferencesKey("plugin_$pluginId:overridden_params")] ?: "")
            .split(",").filter { it.isNotBlank() }.toSet()
    }

    suspend fun setParamOverridden(pluginId: String, key: String, overridden: Boolean) {
        val prefKey = stringPreferencesKey("plugin_$pluginId:overridden_params")
        dataStore.edit { prefs ->
            val current = (prefs[prefKey] ?: "").split(",").filter { it.isNotBlank() }.toMutableSet()
            if (overridden) current.add(key) else current.remove(key)
            prefs[prefKey] = current.joinToString(",")
        }
    }
}

package com.theveloper.pixelplay.data.plugin

import kotlinx.serialization.Serializable

@Serializable
data class PluginParamDef(
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String = "",
    val visible: Boolean = true
)

@Serializable
data class PluginNodeDef(
    val id: String = "",
    val type: String,
    val enabled: Boolean = true,
    val params: Map<String, PluginParamDef> = emptyMap()
) {
    fun effectiveId(index: Int): String = id.ifBlank { "node_$index" }
}

@Serializable
data class PluginMasterDef(
    val outputGainDb: Float = 0f,
    val dryWetMix: Float = 100f
)

@Serializable
data class PluginMacroBinding(
    val nodeIndex: Int,
    val param: String,
    val weight: Float = 1f
)

@Serializable
data class PluginMacroDef(
    val id: String,
    val label: String,
    val default: Float = 50f,
    val bindings: List<PluginMacroBinding> = emptyList()
)

@Serializable
data class PluginDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val version: Int = 1,
    val master: PluginMasterDef = PluginMasterDef(),
    val macros: List<PluginMacroDef> = emptyList(),
    val chain: List<PluginNodeDef>
)

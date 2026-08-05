package com.theveloper.pixelplay.data.plugin

import kotlinx.serialization.Serializable

@Serializable
data class PluginParamDef(
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String = ""
)

@Serializable
data class PluginNodeDef(
    val type: String, // "bandpass" | "distortion" | "noise" | "wobble" | "reverb"
    val params: Map<String, PluginParamDef> = emptyMap()
)

@Serializable
data class PluginDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val version: Int = 1,
    val chain: List<PluginNodeDef>
)

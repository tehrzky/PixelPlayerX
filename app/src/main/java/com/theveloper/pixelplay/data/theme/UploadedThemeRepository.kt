package com.theveloper.pixelplay.data.theme

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Same shape as PluginRepository: disk-backed JSON files under the app's
 * internal storage, parsed and validated on read, no DataStore ordering
 * needed since themes don't have a chain/sequence concept the way plugins
 * do — just a flat list of installed theme files. */
@Singleton
class UploadedThemeRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val themesDir: File get() = File(context.filesDir, "custom_themes").apply { mkdirs() }

    fun parseAndValidate(rawJson: String): UploadedThemeSchema {
        val schema = try {
            json.decodeFromString<UploadedThemeSchema>(rawJson)
        } catch (e: Exception) {
            throw IllegalArgumentException("Couldn't read this file as a theme (${e.message ?: "invalid JSON"})")
        }
        require(schema.id.isNotBlank()) { "Theme is missing an \"id\"" }
        require(schema.name.isNotBlank()) { "Theme is missing a \"name\"" }
        require(schema.id != "default" && schema.id != "tui_oled") {
            "Theme id \"${schema.id}\" is reserved for a built-in theme — pick a different id"
        }
        return schema
    }

    fun importTheme(rawJson: String): UploadedThemeSchema {
        val schema = parseAndValidate(rawJson)
        File(themesDir, "${schema.id}.json").writeText(rawJson)
        return schema
    }

    fun listInstalledThemes(): List<UploadedThemeSchema> {
        val files = themesDir.listFiles { f -> f.extension == "json" } ?: emptyArray()
        return files.mapNotNull { f -> try { parseAndValidate(f.readText()) } catch (e: Exception) { null } }
    }

    fun deleteTheme(id: String) {
        File(themesDir, "$id.json").delete()
    }
}

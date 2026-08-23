package com.theveloper.pixelplay.presentation.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.data.preferences.CustomThemeMode
import com.theveloper.pixelplay.data.preferences.SavedThemePalette
import com.theveloper.pixelplay.presentation.components.CollapsibleCommonTopBar
import com.theveloper.pixelplay.presentation.components.ColorPickerDialog
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.viewmodel.SettingsViewModel

private data class ThemePreset(
    val mode: String,
    val title: String,
    val description: String,
    val previewBackground: Color,
    val previewAccent: Color,
    val previewFont: FontFamily
)

private val presets = listOf(
    ThemePreset(
        mode = CustomThemeMode.DEFAULT,
        title = "Default",
        description = "The normal PixelPlay look — dynamic color, light/dark follows your system theme setting.",
        previewBackground = Color(0xFF1B1B1B),
        previewAccent = Color(0xFFBB86FC),
        previewFont = FontFamily.Default
    ),
    ThemePreset(
        mode = CustomThemeMode.TUI_OLED,
        title = "TUI / ASCII (OLED Black)",
        description = "Pure black background for OLED screens, monospace font, sharp corners, terminal-green accent.",
        previewBackground = Color(0xFF000000),
        previewAccent = Color(0xFF00FF41),
        previewFont = FontFamily.Monospace
    )
)

private enum class ColorRole(val label: String) {
    ACCENT("Primary Accent"),
    BACKGROUND("Background"),
    SURFACE("Card / Box Surface"),
    BUTTON("Button Fill"),
    TEXT("Text")
}

@Composable
fun CustomThemesScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topBarHeight = 56.dp + statusBarHeight
    val context = LocalContext.current

    val themeFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val rawJson = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.toString(Charsets.UTF_8)
        if (rawJson != null) settingsViewModel.importTheme(rawJson)
    }

    var accent by remember { mutableStateOf(Color(0xFFBB86FC)) }
    var background by remember { mutableStateOf(Color(0xFF1B1B1B)) }
    var surface by remember { mutableStateOf(Color(0xFF2A2A2A)) }
    var button by remember { mutableStateOf(Color(0xFFBB86FC)) }
    var text by remember { mutableStateOf(Color(0xFFEEEEEE)) }

    var editingRole by remember { mutableStateOf<ColorRole?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(
                top = topBarHeight + 16.dp,
                bottom = MiniPlayerHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "intro") {
                Text(
                    "Choose a built-in theme preset, or upload your own theme file.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            items(presets, key = { it.mode }) { preset ->
                ThemePresetCard(
                    preset = preset,
                    selected = uiState.customThemeMode == preset.mode,
                    onClick = {
                        settingsViewModel.setCustomThemeMode(preset.mode)
                        if (preset.mode != CustomThemeMode.DEFAULT) settingsViewModel.selectPalette(null)
                    }
                )
            }

            item(key = "uploaded_header") {
                Text(
                    "Uploaded themes",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
            }
            items(uiState.uploadedThemes, key = { "uploaded_" + it.id }) { uploaded ->
                UploadedThemeCard(
                    theme = uploaded,
                    selected = uiState.customThemeMode == uploaded.id,
                    onClick = {
                        settingsViewModel.setCustomThemeMode(uploaded.id)
                        settingsViewModel.selectPalette(null)
                    },
                    onDelete = { settingsViewModel.deleteUploadedTheme(uploaded.id) }
                )
            }
            item(key = "import_theme_button") {
                OutlinedButton(
                    onClick = { themeFilePicker.launch(arrayOf("application/json", "text/*", "*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Import theme (.json)")
                }
            }

            // Rendered as its own top-level LazyColumn item — same as the cards
            // above it, not nested with any additional modifier padding.
            if (uiState.customThemeMode == CustomThemeMode.DEFAULT) {
                item(key = "advanced") {
                    AdvancedDefaultThemeSection(
                        accent = accent, onAccentClick = { editingRole = ColorRole.ACCENT },
                        background = background, onBackgroundClick = { editingRole = ColorRole.BACKGROUND },
                        surface = surface, onSurfaceClick = { editingRole = ColorRole.SURFACE },
                        button = button, onButtonClick = { editingRole = ColorRole.BUTTON },
                        text = text, onTextClick = { editingRole = ColorRole.TEXT },
                        onSaveClick = { showSaveDialog = true },
                        savedPalettes = uiState.savedPalettes,
                        activePaletteId = uiState.activePaletteId,
                        onSelectPalette = { id ->
                            settingsViewModel.selectPalette(id)
                            uiState.savedPalettes.find { it.id == id }?.let { p ->
                                accent = Color(p.accentColorArgb.toInt())
                                background = Color(p.backgroundColorArgb.toInt())
                                surface = Color(p.surfaceColorArgb.toInt())
                                button = Color(p.buttonColorArgb.toInt())
                                text = Color(p.textColorArgb.toInt())
                            }
                        },
                        onClearPalette = { settingsViewModel.selectPalette(null) },
                        onDeletePalette = { pendingDeleteId = it }
                    )
                }
            }
        }

        CollapsibleCommonTopBar(
            title = "Custom Themes",
            collapseFraction = 1f,
            headerHeight = topBarHeight,
            onBackClick = { navController.popBackStack() },
            collapsedTitleStartPadding = 72.dp
        )
    }

    editingRole?.let { role ->
        val current = when (role) {
            ColorRole.ACCENT -> accent
            ColorRole.BACKGROUND -> background
            ColorRole.SURFACE -> surface
            ColorRole.BUTTON -> button
            ColorRole.TEXT -> text
        }
        ColorPickerDialog(
            initialColor = current,
            title = role.label,
            onDismiss = { editingRole = null },
            onConfirm = { picked ->
                when (role) {
                    ColorRole.ACCENT -> accent = picked
                    ColorRole.BACKGROUND -> background = picked
                    ColorRole.SURFACE -> surface = picked
                    ColorRole.BUTTON -> button = picked
                    ColorRole.TEXT -> text = picked
                }
                editingRole = null
            }
        )
    }

    if (showSaveDialog) {
        var nameInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save palette") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    enabled = nameInput.isNotBlank(),
                    onClick = {
                        settingsViewModel.saveAndActivatePalette(
                            nameInput.trim(),
                            accent.toArgb().toLong(),
                            background.toArgb().toLong(),
                            surface.toArgb().toLong(),
                            button.toArgb().toLong(),
                            text.toArgb().toLong()
                        )
                        showSaveDialog = false
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") } }
        )
    }

    uiState.themeImportError?.let { error ->
        AlertDialog(
            onDismissRequest = settingsViewModel::dismissThemeImportError,
            confirmButton = { Button(onClick = settingsViewModel::dismissThemeImportError) { Text("OK") } },
            title = { Text("Import failed") },
            text = { Text(error) }
        )
    }

    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete this palette?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                Button(onClick = {
                    settingsViewModel.deletePalette(id)
                    pendingDeleteId = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteId = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun UploadedThemeCard(
    theme: com.theveloper.pixelplay.data.theme.UploadedThemeSchema,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                else Modifier
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color(theme.backgroundColorArgb.toInt()), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(theme.accentColorArgb.toInt()).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(theme.name, style = MaterialTheme.typography.titleMedium)
                Text("Uploaded theme", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AdvancedDefaultThemeSection(
    accent: Color, onAccentClick: () -> Unit,
    background: Color, onBackgroundClick: () -> Unit,
    surface: Color, onSurfaceClick: () -> Unit,
    button: Color, onButtonClick: () -> Unit,
    text: Color, onTextClick: () -> Unit,
    onSaveClick: () -> Unit,
    savedPalettes: List<SavedThemePalette>,
    activePaletteId: String?,
    onSelectPalette: (String) -> Unit,
    onClearPalette: () -> Unit,
    onDeletePalette: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Advanced", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "Tap any swatch to open the color picker for that part of the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )

            ColorRoleRow("Primary Accent", accent, onAccentClick)
            ColorRoleRow("Background", background, onBackgroundClick)
            ColorRoleRow("Card / Box Surface", surface, onSurfaceClick)
            ColorRoleRow("Button Fill", button, onButtonClick)
            ColorRoleRow("Text", text, onTextClick)

            OutlinedButton(
                onClick = onSaveClick,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Save as new palette") }

            if (savedPalettes.isNotEmpty()) {
                Text(
                    "Your palettes",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
                )
                savedPalettes.forEach { palette ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (activePaletteId == palette.id) onClearPalette() else onSelectPalette(palette.id)
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(Color(palette.accentColorArgb.toInt()), CircleShape)
                        )
                        Text(palette.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        if (activePaletteId == palette.id) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { onDeletePalette(palette.id) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorRoleRow(label: String, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(color, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ThemePresetCard(
    preset: ThemePreset,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                else Modifier
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(preset.previewBackground, RoundedCornerShape(8.dp))
                    .border(1.dp, preset.previewAccent.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Aa",
                    color = preset.previewAccent,
                    fontFamily = preset.previewFont,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(preset.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (selected) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

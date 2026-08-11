package com.theveloper.pixelplay.presentation.screens

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.data.preferences.CustomThemeMode
import com.theveloper.pixelplay.data.preferences.SavedThemePalette
import com.theveloper.pixelplay.presentation.components.CollapsibleCommonTopBar
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

// Curated accent swatches — not a full color wheel. Simple, fast, and covers
// the common cases; a real color picker can come later if it's actually needed.
private val accentSwatches = listOf(
    0xFFBB86FC, 0xFF00FF41, 0xFF03DAC6, 0xFFFF3B30, 0xFFFF9500,
    0xFFFFD60A, 0xFF34C759, 0xFF00C7BE, 0xFF32ADE6, 0xFF5E5CE6,
    0xFFAF52DE, 0xFFFF2D55, 0xFFFFFFFF, 0xFF8E8E93
)

@Composable
fun CustomThemesScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topBarHeight = 56.dp + statusBarHeight

    var selectedSwatch by remember { mutableStateOf(accentSwatches.first()) }
    var oledBlackDraft by remember { mutableStateOf(false) }
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
                    "Choose a built-in theme preset. More customization options are planned.",
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

                // Advanced section only makes sense under Default — TUI is
                // deliberately fixed/monochrome, not accent-customizable.
                if (preset.mode == CustomThemeMode.DEFAULT && uiState.customThemeMode == CustomThemeMode.DEFAULT) {
                    AdvancedDefaultThemeSection(
                        selectedSwatch = selectedSwatch,
                        onSwatchSelected = { selectedSwatch = it },
                        oledBlack = oledBlackDraft,
                        onOledBlackChange = { oledBlackDraft = it },
                        onSaveClick = { showSaveDialog = true },
                        savedPalettes = uiState.savedPalettes,
                        activePaletteId = uiState.activePaletteId,
                        onSelectPalette = { settingsViewModel.selectPalette(it) },
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
                        settingsViewModel.saveAndActivatePalette(nameInput.trim(), selectedSwatch, oledBlackDraft)
                        showSaveDialog = false
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") } }
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
private fun AdvancedDefaultThemeSection(
    selectedSwatch: Long,
    onSwatchSelected: (Long) -> Unit,
    oledBlack: Boolean,
    onOledBlackChange: (Boolean) -> Unit,
    onSaveClick: () -> Unit,
    savedPalettes: List<SavedThemePalette>,
    activePaletteId: String?,
    onSelectPalette: (String) -> Unit,
    onClearPalette: () -> Unit,
    onDeletePalette: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Advanced", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "Pick an accent color and optionally force pure black — then save it as your own named palette.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(accentSwatches) { swatch ->
                    val selected = swatch == selectedSwatch
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(swatch.toInt()), CircleShape)
                            .then(
                                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                else Modifier
                            )
                            .clickable { onSwatchSelected(swatch) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Pure black (OLED)", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = oledBlack, onCheckedChange = onOledBlackChange)
            }

            OutlinedButton(
                onClick = onSaveClick,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
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
                                .background(Color(palette.primaryColorArgb.toInt()), CircleShape)
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

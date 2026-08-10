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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.data.preferences.CustomThemeMode
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

@Composable
fun CustomThemesScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topBarHeight = 56.dp + statusBarHeight

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
                    onClick = { settingsViewModel.setCustomThemeMode(preset.mode) }
                )
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
            // Mini live preview swatch — shows the actual background/accent/font
            // combination this preset applies, not just a text description.
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

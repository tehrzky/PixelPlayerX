package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.CollapsibleCommonTopBar
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.viewmodel.AudioFxViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioFxScreen(
    navController: NavController,
    audioFxViewModel: AudioFxViewModel = hiltViewModel()
) {
    val uiState by audioFxViewModel.uiState.collectAsStateWithLifecycle()
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topBarHeight = 64.dp + statusBarHeight

    LazyColumn(
        contentPadding = PaddingValues(
            top = topBarHeight + 12.dp,
            bottom = MiniPlayerHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp,
            start = 16.dp,
            end = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "info_banner") {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = stringResource(R.string.audio_fx_coming_soon),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        item(key = "lofi") {
            AudioFxCard(
                title = stringResource(R.string.audio_fx_lofi_title),
                subtitle = stringResource(R.string.audio_fx_lofi_subtitle),
                enabled = uiState.lofiEnabled,
                intensity = uiState.lofiIntensity,
                onEnabledChange = audioFxViewModel::setLofiEnabled,
                onIntensityChange = audioFxViewModel::setLofiIntensity
            )
        }
        item(key = "radio") {
            AudioFxCard(
                title = stringResource(R.string.audio_fx_radio_title),
                subtitle = stringResource(R.string.audio_fx_radio_subtitle),
                enabled = uiState.radioEnabled,
                intensity = uiState.radioIntensity,
                onEnabledChange = audioFxViewModel::setRadioEnabled,
                onIntensityChange = audioFxViewModel::setRadioIntensity
            )
        }
        item(key = "wow_flutter") {
            AudioFxCard(
                title = stringResource(R.string.audio_fx_wow_flutter_title),
                subtitle = stringResource(R.string.audio_fx_wow_flutter_subtitle),
                enabled = uiState.wowFlutterEnabled,
                intensity = uiState.wowFlutterIntensity,
                onEnabledChange = audioFxViewModel::setWowFlutterEnabled,
                onIntensityChange = audioFxViewModel::setWowFlutterIntensity
            )
        }
        item(key = "reverb") {
            AudioFxCard(
                title = stringResource(R.string.audio_fx_reverb_title),
                subtitle = stringResource(R.string.audio_fx_reverb_subtitle),
                enabled = uiState.reverbEnabled,
                intensity = uiState.reverbIntensity,
                onEnabledChange = audioFxViewModel::setReverbEnabled,
                onIntensityChange = audioFxViewModel::setReverbIntensity
            )
        }
    }

    CollapsibleCommonTopBar(
        title = stringResource(R.string.audio_fx_title),
        collapseFraction = 0f,
        headerHeight = topBarHeight,
        onBackClick = { navController.popBackStack() },
        expandedTitleStartPadding = 20.dp,
        collapsedTitleStartPadding = 20.dp
    )
}

@Composable
private fun AudioFxCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    intensity: Int,
    onEnabledChange: (Boolean) -> Unit,
    onIntensityChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.padding(end = 12.dp)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Slider(
                value = intensity.toFloat(),
                onValueChange = { onIntensityChange(it.toInt()) },
                valueRange = 0f..100f,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

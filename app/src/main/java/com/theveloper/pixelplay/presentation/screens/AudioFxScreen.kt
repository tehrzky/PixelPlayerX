package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
    val topBarHeight = 56.dp + statusBarHeight

    LazyColumn(
        contentPadding = PaddingValues(
            top = topBarHeight + 8.dp,
            bottom = MiniPlayerHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 48.dp,
            start = 16.dp,
            end = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item(key = "info_banner") {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier.padding(bottom = 12.dp)
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
                        text = stringResource(R.string.audio_fx_active_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        item(key = "lofi_header") { AudioFxSectionHeader(stringResource(R.string.audio_fx_lofi_title)) }
        item(key = "lofi_toggle") {
            SwitchSettingItem(
                title = stringResource(R.string.audio_fx_lofi_title),
                subtitle = stringResource(R.string.audio_fx_lofi_subtitle),
                checked = uiState.lofiEnabled,
                onCheckedChange = audioFxViewModel::setLofiEnabled
            )
        }
        item(key = "lofi_slider") {
            SliderSettingsItem(
                label = stringResource(R.string.audio_fx_intensity_label),
                value = uiState.lofiIntensity.toFloat(),
                valueRange = 0f..100f,
                steps = 0,
                onValueChange = { audioFxViewModel.setLofiIntensity(it.toInt()) },
                valueText = { value -> "${value.toInt()}%" }
            )
        }

        item(key = "radio_header") { AudioFxSectionHeader(stringResource(R.string.audio_fx_radio_title)) }
        item(key = "radio_toggle") {
            SwitchSettingItem(
                title = stringResource(R.string.audio_fx_radio_title),
                subtitle = stringResource(R.string.audio_fx_radio_subtitle),
                checked = uiState.radioEnabled,
                onCheckedChange = audioFxViewModel::setRadioEnabled
            )
        }
        item(key = "radio_slider") {
            SliderSettingsItem(
                label = stringResource(R.string.audio_fx_intensity_label),
                value = uiState.radioIntensity.toFloat(),
                valueRange = 0f..100f,
                steps = 0,
                onValueChange = { audioFxViewModel.setRadioIntensity(it.toInt()) },
                valueText = { value -> "${value.toInt()}%" }
            )
        }

        item(key = "wow_flutter_header") { AudioFxSectionHeader(stringResource(R.string.audio_fx_wow_flutter_title)) }
        item(key = "wow_flutter_toggle") {
            SwitchSettingItem(
                title = stringResource(R.string.audio_fx_wow_flutter_title),
                subtitle = stringResource(R.string.audio_fx_wow_flutter_subtitle),
                checked = uiState.wowFlutterEnabled,
                onCheckedChange = audioFxViewModel::setWowFlutterEnabled
            )
        }
        item(key = "wow_flutter_slider") {
            SliderSettingsItem(
                label = stringResource(R.string.audio_fx_intensity_label),
                value = uiState.wowFlutterIntensity.toFloat(),
                valueRange = 0f..100f,
                steps = 0,
                onValueChange = { audioFxViewModel.setWowFlutterIntensity(it.toInt()) },
                valueText = { value -> "${value.toInt()}%" }
            )
        }

        item(key = "reverb_header") { AudioFxSectionHeader(stringResource(R.string.audio_fx_reverb_title)) }
        item(key = "reverb_toggle") {
            SwitchSettingItem(
                title = stringResource(R.string.audio_fx_reverb_title),
                subtitle = stringResource(R.string.audio_fx_reverb_subtitle),
                checked = uiState.reverbEnabled,
                onCheckedChange = audioFxViewModel::setReverbEnabled
            )
        }
        item(key = "reverb_slider") {
            SliderSettingsItem(
                label = stringResource(R.string.audio_fx_intensity_label),
                value = uiState.reverbIntensity.toFloat(),
                valueRange = 0f..100f,
                steps = 0,
                onValueChange = { audioFxViewModel.setReverbIntensity(it.toInt()) },
                valueText = { value -> "${value.toInt()}%" }
            )
        }
    }

    CollapsibleCommonTopBar(
        title = stringResource(R.string.audio_fx_title),
        collapseFraction = 1f,
        headerHeight = topBarHeight,
        onBackClick = { navController.popBackStack() },
        collapsedTitleStartPadding = 72.dp
    )
}

@Composable
private fun AudioFxSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
    )
}

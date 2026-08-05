package com.theveloper.pixelplay.presentation.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.CollapsibleCommonTopBar
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.viewmodel.AudioFxViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PluginManagerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PluginUiModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioFxScreen(
    navController: NavController,
    audioFxViewModel: AudioFxViewModel = hiltViewModel(),
    pluginManagerViewModel: PluginManagerViewModel = hiltViewModel()
) {
    val onBack: () -> Unit = { navController.popBackStack() }
    val uiState by audioFxViewModel.uiState.collectAsStateWithLifecycle()
    val pluginState by pluginManagerViewModel.uiState.collectAsStateWithLifecycle()

    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.hide(WindowInsetsCompat.Type.navigationBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller?.show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val minTopBarHeight = 64.dp + statusBarHeight
    val maxTopBarHeight = 160.dp

    val minTopBarHeightPx = with(density) { minTopBarHeight.toPx() }
    val maxTopBarHeightPx = with(density) { maxTopBarHeight.toPx() }

    val topBarHeight = remember { Animatable(maxTopBarHeightPx) }
    var collapseFraction by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(topBarHeight.value) {
        collapseFraction = 1f - ((topBarHeight.value - minTopBarHeightPx) / (maxTopBarHeightPx - minTopBarHeightPx)).coerceIn(0f, 1f)
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val isScrollingDown = delta < 0
                if (!isScrollingDown && (lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0)) {
                    return Offset.Zero
                }
                val previousHeight = topBarHeight.value
                val newHeight = (previousHeight + delta).coerceIn(minTopBarHeightPx, maxTopBarHeightPx)
                val consumed = newHeight - previousHeight
                if (consumed.roundToInt() != 0) {
                    coroutineScope.launch { topBarHeight.snapTo(newHeight) }
                }
                val canConsumeScroll = !(isScrollingDown && newHeight == minTopBarHeightPx)
                return if (canConsumeScroll) Offset(0f, consumed) else Offset.Zero
            }
        }
    }

    LaunchedEffect(lazyListState.isScrollInProgress) {
        if (!lazyListState.isScrollInProgress) {
            val shouldExpand = topBarHeight.value > (minTopBarHeightPx + maxTopBarHeightPx) / 2
            val canExpand = lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0
            val targetValue = if (shouldExpand && canExpand) maxTopBarHeightPx else minTopBarHeightPx
            if (topBarHeight.value != targetValue) {
                coroutineScope.launch {
                    topBarHeight.animateTo(targetValue, spring(stiffness = Spring.StiffnessMedium))
                }
            }
        }
    }

    Box(modifier = Modifier.nestedScroll(nestedScrollConnection).fillMaxSize()) {
        val currentTopBarHeightDp = with(density) { topBarHeight.value.toDp() }

        LazyColumn(
            state = lazyListState,
            contentPadding = PaddingValues(
                top = currentTopBarHeightDp + 8.dp,
                bottom = MiniPlayerHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 64.dp,
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item(key = "manage_plugins_link") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    onClick = { navController.navigateSafely(Screen.PluginManager.route) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("Manage Plugins", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Text("Import your own →", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }

            item(key = "plugins_header") { AudioFxSectionHeader("Plugins") }

            if (pluginState.plugins.isEmpty()) {
                item(key = "no_plugins") {
                    Text(
                        "No Plugins Installed. Import one from Manage Plugins above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            } else {
                items(pluginState.plugins, key = { it.definition.id }) { pluginModel ->
                    PluginCard(
                        model = pluginModel,
                        onEnabledChange = { pluginManagerViewModel.setPluginEnabled(pluginModel.definition.id, it) },
                        onParamChange = { key, value -> pluginManagerViewModel.setPluginParam(pluginModel.definition.id, key, value) }
                    )
                }
            }

            item(key = "builtin_header") { AudioFxSectionHeader("Built-in Effects") }

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
                    valueText = { value -> "${value.toInt()}%" },
                    enabled = uiState.lofiEnabled
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
                    valueText = { value -> "${value.toInt()}%" },
                    enabled = uiState.radioEnabled
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
                    valueText = { value -> "${value.toInt()}%" },
                    enabled = uiState.wowFlutterEnabled
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
                    valueText = { value -> "${value.toInt()}%" },
                    enabled = uiState.reverbEnabled
                )
            }
        }

        CollapsibleCommonTopBar(
            title = stringResource(R.string.audio_fx_title),
            collapseFraction = collapseFraction,
            headerHeight = currentTopBarHeightDp,
            onBackClick = onBack,
            collapsedTitleStartPadding = 72.dp
        )
    }
}

@Composable
private fun PluginCard(
    model: PluginUiModel,
    onEnabledChange: (Boolean) -> Unit,
    onParamChange: (String, Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.padding(vertical = 8.dp)) {
            SwitchSettingItem(
                title = model.definition.name,
                subtitle = model.definition.description,
                checked = model.enabled,
                onCheckedChange = onEnabledChange
            )
            model.definition.chain.forEach { node ->
                node.params.forEach { (key, paramDef) ->
                    SliderSettingsItem(
                        label = paramDef.label,
                        value = model.paramValues[key] ?: paramDef.default,
                        valueRange = paramDef.min..paramDef.max,
                        steps = 0,
                        onValueChange = { onParamChange(key, it) },
                        valueText = { v -> if (paramDef.unit.isNotBlank()) "${v.roundToInt()}${paramDef.unit}" else "${v.roundToInt()}" },
                        enabled = model.enabled
                    )
                }
            }
        }
    }
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

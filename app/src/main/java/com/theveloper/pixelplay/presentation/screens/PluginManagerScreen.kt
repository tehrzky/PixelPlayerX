package com.theveloper.pixelplay.presentation.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Reorder
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.presentation.components.CollapsibleCommonTopBar
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.viewmodel.DisabledSortMode
import com.theveloper.pixelplay.presentation.viewmodel.PluginManagerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PluginUiModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginManagerScreen(
    navController: NavController,
    viewModel: PluginManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topBarHeight = 56.dp + statusBarHeight

    var optionsModalPlugin by remember { mutableStateOf<PluginUiModel?>(null) }
    var pendingBatchDelete by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val sources = uris.mapNotNull { uri ->
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@mapNotNull null
            val name = uri.lastPathSegment ?: "plugin"
            name to bytes
        }
        viewModel.importBatch(sources)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (uiState.isArrangeMode) {
            ArrangePluginsList(
                topBarHeight = topBarHeight,
                activePlugins = uiState.activePlugins,
                onCommitOrder = { reorderedActiveIds ->
                    // Active order changed — disabled plugins keep their existing
                    // relative order, just appended after the new active order so
                    // the underlying persisted order list stays complete.
                    val disabledIds = uiState.disabledPlugins.map { it.definition.id }
                    viewModel.commitPluginOrder(reorderedActiveIds + disabledIds)
                }
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    top = topBarHeight + 12.dp,
                    bottom = MiniPlayerHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                        (if (uiState.isMultiSelectMode) 88.dp else 48.dp),
                    start = 16.dp,
                    end = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "import_button") {
                    Button(
                        onClick = { filePicker.launch(arrayOf("application/json", "application/zip", "text/*", "*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = null)
                        androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                        Text("Import Plugin (.json or .zip)")
                    }
                }

                item(key = "action_bar") {
                    if (uiState.isMultiSelectMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = { viewModel.selectAll() }) { Text("Select All") }
                            TextButton(onClick = { viewModel.setMultiSelectMode(false) }) { Text("Cancel") }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            OutlinedButton(onClick = { viewModel.setArrangeMode(true) }) {
                                Icon(Icons.Rounded.Reorder, contentDescription = null, modifier = Modifier.size(18.dp))
                                androidx.compose.foundation.layout.Spacer(Modifier.padding(3.dp))
                                Text("Arrange")
                            }
                            OutlinedButton(onClick = { viewModel.setMultiSelectMode(true) }) {
                                Text("Select")
                            }
                        }
                    }
                }

                if (uiState.plugins.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "No plugins installed yet. Import a .json plugin file or a .zip archive to get started.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                if (uiState.activePlugins.isNotEmpty()) {
                    item(key = "active_header") {
                        Text(
                            "Active",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    items(uiState.activePlugins, key = { "active_" + it.definition.id }) { plugin ->
                        PluginCard(
                            plugin = plugin,
                            multiSelect = uiState.isMultiSelectMode,
                            selected = plugin.definition.id in uiState.selectedIds,
                            onToggleEnabled = { viewModel.setPluginEnabled(plugin.definition.id, it) },
                            onToggleSelected = { viewModel.toggleSelected(plugin.definition.id) },
                            onTap = { optionsModalPlugin = plugin }
                        )
                    }
                }

                item(key = "divider") {
                    DisabledSectionDivider(
                        sortMode = uiState.disabledSortMode,
                        onSortModeChange = { viewModel.setDisabledSortMode(it) }
                    )
                }

                if (uiState.disabledPlugins.isEmpty() && uiState.activePlugins.isNotEmpty()) {
                    item(key = "disabled_empty") {
                        Text(
                            "No disabled plugins.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }

                items(uiState.disabledPlugins, key = { "disabled_" + it.definition.id }) { plugin ->
                    PluginCard(
                        plugin = plugin,
                        multiSelect = uiState.isMultiSelectMode,
                        selected = plugin.definition.id in uiState.selectedIds,
                        onToggleEnabled = { viewModel.setPluginEnabled(plugin.definition.id, it) },
                        onToggleSelected = { viewModel.toggleSelected(plugin.definition.id) },
                        onTap = { optionsModalPlugin = plugin }
                    )
                }
            }
        }

        if (uiState.isMultiSelectMode && !uiState.isArrangeMode) {
            BatchActionBar(
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                selectedCount = uiState.selectedIds.size,
                onEnable = { viewModel.batchSetEnabled(uiState.selectedIds, true) },
                onDisable = { viewModel.batchSetEnabled(uiState.selectedIds, false) },
                onDeleteRequest = { pendingBatchDelete = true }
            )
        }
    }

    uiState.importError?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            confirmButton = { Button(onClick = viewModel::dismissError) { Text("OK") } },
            title = { Text("Import failed") },
            text = { Text(error) }
        )
    }

    uiState.batchImportSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = viewModel::dismissBatchImportSummary,
            confirmButton = { Button(onClick = viewModel::dismissBatchImportSummary) { Text("OK") } },
            title = { Text("Import complete") },
            text = { Text(summary) }
        )
    }

    if (pendingBatchDelete) {
        AlertDialog(
            onDismissRequest = { pendingBatchDelete = false },
            title = { Text("Delete ${uiState.selectedIds.size} plugin(s)?") },
            text = { Text("This permanently removes the selected plugin files. This can't be undone.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.batchDelete(uiState.selectedIds)
                    pendingBatchDelete = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingBatchDelete = false }) { Text("Cancel") } }
        )
    }

    optionsModalPlugin?.let { plugin ->
        PluginOptionsModal(
            plugin = plugin,
            onDismiss = { optionsModalPlugin = null },
            onDelete = {
                viewModel.deletePlugin(plugin.definition.id)
                optionsModalPlugin = null
            }
        )
    }

    CollapsibleCommonTopBar(
        title = if (uiState.isArrangeMode) "Arrange Plugins" else "Plugin Manager",
        collapseFraction = 1f,
        headerHeight = topBarHeight,
        onBackClick = {
            if (uiState.isArrangeMode) viewModel.setArrangeMode(false) else navController.popBackStack()
        },
        collapsedTitleStartPadding = 72.dp
    )
}

@Composable
private fun PluginCard(
    plugin: PluginUiModel,
    multiSelect: Boolean,
    selected: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleSelected: () -> Unit,
    onTap: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (multiSelect) Modifier.pointerInput(plugin.definition.id, multiSelect) {
                    detectTapGestures(onTap = { onToggleSelected() })
                } else Modifier.pointerInput(plugin.definition.id, multiSelect) {
                    detectTapGestures(onTap = { onTap() })
                }
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(plugin.definition.name, style = MaterialTheme.typography.titleMedium)
                if (plugin.definition.description.isNotBlank()) {
                    Text(plugin.definition.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(
                modifier = Modifier.width(52.dp),
                contentAlignment = androidx.compose.ui.Alignment.CenterEnd
            ) {
                if (multiSelect) {
                    Icon(
                        imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (selected) "Selected" else "Not selected",
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    androidx.compose.material3.Switch(
                        checked = plugin.enabled,
                        onCheckedChange = onToggleEnabled
                    )
                }
            }
        }
    }
}

@Composable
private fun DisabledSectionDivider(
    sortMode: DisabledSortMode,
    onSortModeChange: (DisabledSortMode) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "Disabled",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            TextButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.Sort, contentDescription = null, modifier = Modifier.size(16.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.padding(2.dp))
                Text(
                    when (sortMode) {
                        DisabledSortMode.DATE_NEWEST -> "Newest"
                        DisabledSortMode.DATE_OLDEST -> "Oldest"
                        DisabledSortMode.ALPHA_AZ -> "A–Z"
                        DisabledSortMode.ALPHA_ZA -> "Z–A"
                    }
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Date Added (Newest)") }, onClick = { onSortModeChange(DisabledSortMode.DATE_NEWEST); menuOpen = false })
                DropdownMenuItem(text = { Text("Date Added (Oldest)") }, onClick = { onSortModeChange(DisabledSortMode.DATE_OLDEST); menuOpen = false })
                DropdownMenuItem(text = { Text("Alphabetical (A–Z)") }, onClick = { onSortModeChange(DisabledSortMode.ALPHA_AZ); menuOpen = false })
                DropdownMenuItem(text = { Text("Alphabetical (Z–A)") }, onClick = { onSortModeChange(DisabledSortMode.ALPHA_ZA); menuOpen = false })
            }
        }
    }
}

@Composable
private fun BatchActionBar(
    modifier: Modifier = Modifier,
    selectedCount: Int,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onDeleteRequest: () -> Unit
) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = RoundedCornerShape(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text("$selectedCount selected", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onEnable, enabled = selectedCount > 0) { Text("Enable") }
                    TextButton(onClick = onDisable, enabled = selectedCount > 0) { Text("Disable") }
                    TextButton(onClick = onDeleteRequest, enabled = selectedCount > 0) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
              }
            }
        }
}

@Composable
private fun PluginOptionsModal(
    plugin: PluginUiModel,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    val sizeKb = plugin.fileSizeBytes / 1024f

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(plugin.definition.name) },
        text = {
            Column {
                if (plugin.definition.description.isNotBlank()) {
                    Text(plugin.definition.description, style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 6.dp))
                }
                Text(
                    "Author: ${plugin.definition.author.ifBlank { "Unknown" }}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Version: ${plugin.definition.version}", style = MaterialTheme.typography.bodySmall)
                Text("File size: ${"%.1f".format(sizeKb)} KB", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { confirmingDelete = true }) {
                Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.padding(3.dp))
                Text("Delete Plugin", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete \"${plugin.definition.name}\"?") },
            text = { Text("This permanently removes the plugin file. This can't be undone.") },
            confirmButton = { Button(onClick = onDelete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } }
        )
    }
}

/** Arrange mode: a dedicated full-list view of the active (enabled) plugins in
 * their exact DSP execution order, reorderable via drag handle. This is
 * deliberately separate from the main list — the audio topology order only
 * ever applies to enabled plugins, so disabled ones don't appear here.
 * Uses the same sh.calvin.reorderable library already proven out in
 * ReorderPresetsSheet, instead of a hand-rolled drag detector. */
@Composable
private fun ArrangePluginsList(
    topBarHeight: androidx.compose.ui.unit.Dp,
    activePlugins: List<PluginUiModel>,
    onCommitOrder: (List<String>) -> Unit
) {
    // No key here deliberately — this must NOT reset while a drag is in progress.
    // activePlugins reshapes on every unrelated param/macro/master flow update
    // elsewhere in the plugin system; keying on it caused the list to reset out
    // from under an active drag gesture mid-gesture, leaving the reorder
    // library's internal position tracking pointing at stale indices and
    // crashing with IndexOutOfBoundsException on drop. Seeding once here means
    // Arrange mode shows a frozen snapshot for the duration of the drag session
    // — correct, since re-entering Arrange mode (leaving and coming back)
    // naturally re-seeds from the latest state anyway.
    var items by remember { mutableStateOf(activePlugins) }
    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(
        onMove = { from, to ->
            items = items.toMutableList().apply { add(to.index, removeAt(from.index)) }
        },
        lazyListState = listState
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "This is the exact signal chain order. Drag by the handle to reorder.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = topBarHeight + 12.dp, start = 16.dp, end = 16.dp, bottom = 4.dp)
        )
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(
            top = 0.dp,
            bottom = 48.dp,
            start = 16.dp,
            end = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Deliberately no header item in this LazyColumn — the reorderable
        // library's onMove gives raw indices into this exact list, so any
        // preceding header item shifts those indices by one relative to
        // `items`, which was the second real cause of the drop-gesture crash
        // (the first was the remember-key issue fixed earlier). The hint text
        // above is now a plain sibling Text outside the LazyColumn instead.
        items(items, key = { it.definition.id }) { plugin ->
            ReorderableItem(reorderableState, key = plugin.definition.id) { isDragging ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(plugin.definition.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Icon(
                            Icons.Rounded.DragHandle,
                            contentDescription = "Drag to reorder",
                            modifier = Modifier.size(28.dp).draggableHandle(
                                onDragStopped = { onCommitOrder(items.map { it.definition.id }) }
                            )
                        )
                    }
                }
            }
        }
    }
    }
}

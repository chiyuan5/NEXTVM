package com.nextvm.feature.filemanager

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nextvm.core.model.FileViewMode
import com.nextvm.feature.filemanager.components.BreadcrumbBar
import com.nextvm.feature.filemanager.components.CreateFileDialog
import com.nextvm.feature.filemanager.components.CreateFolderDialog
import com.nextvm.feature.filemanager.components.DeleteConfirmDialog
import com.nextvm.feature.filemanager.components.FileGridItem
import com.nextvm.feature.filemanager.components.FileListItem
import com.nextvm.feature.filemanager.components.PropertiesDialog
import com.nextvm.feature.filemanager.components.RenameDialog
import com.nextvm.feature.filemanager.components.SortDialog
import com.nextvm.feature.filemanager.components.StorageInfoCard

// Glass morphism helpers
private val GlassWhite = Color.White.copy(alpha = 0.55f)
private val GlassBorder = Color.White.copy(alpha = 0.35f)
private val GlassShape = RoundedCornerShape(20.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    onNavigateBack: () -> Unit,
    viewModel: FileManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSearch by remember { mutableStateOf(false) }
    var showFabMenu by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showStorageInfo by remember { mutableStateOf(false) }

    // Inject FileOperationsManager for PropertiesDialog
    val fileOps = remember { FileOperationsManager() }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is FileManagerEffect.ShowError -> snackbarHostState.showSnackbar(effect.message)
                is FileManagerEffect.ShowSuccess -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    // Gradient background
    val bgGradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.15f),
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgGradient)
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Column {
                    // Top bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(GlassShape)
                                .background(GlassWhite)
                                .border(1.dp, GlassBorder, GlassShape)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                if (showSearch) {
                                    showSearch = false
                                    viewModel.onIntent(FileManagerIntent.ClearSearch)
                                } else {
                                    onNavigateBack()
                                }
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            if (showSearch) {
                                OutlinedTextField(
                                    value = uiState.searchQuery,
                                    onValueChange = { viewModel.onIntent(FileManagerIntent.Search(it)) },
                                    placeholder = { Text("Search files...") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent
                                    )
                                )
                                IconButton(onClick = {
                                    showSearch = false
                                    viewModel.onIntent(FileManagerIntent.ClearSearch)
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close search")
                                }
                            } else {
                                Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                                    Text(
                                        text = "Files",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                IconButton(onClick = { showSearch = true }) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = "Search",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                IconButton(onClick = { viewModel.onIntent(FileManagerIntent.ToggleViewMode) }) {
                                    Icon(
                                        imageVector = if (uiState.viewMode == FileViewMode.LIST)
                                            Icons.Default.GridView else Icons.Default.ViewList,
                                        contentDescription = "Toggle view",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                IconButton(onClick = { viewModel.onIntent(FileManagerIntent.ShowSortDialog) }) {
                                    Icon(
                                        Icons.Default.Sort,
                                        contentDescription = "Sort",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Box {
                                    IconButton(onClick = { showOverflowMenu = true }) {
                                        Icon(
                                            Icons.Default.MoreVert,
                                            contentDescription = "More",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showOverflowMenu,
                                        onDismissRequest = { showOverflowMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    if (uiState.showHiddenFiles) "Hide hidden files"
                                                    else "Show hidden files"
                                                )
                                            },
                                            onClick = {
                                                showOverflowMenu = false
                                                viewModel.onIntent(FileManagerIntent.ToggleHiddenFiles)
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    if (uiState.showHiddenFiles) Icons.Default.VisibilityOff
                                                    else Icons.Default.Visibility,
                                                    contentDescription = null
                                                )
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Storage info") },
                                            onClick = {
                                                showOverflowMenu = false
                                                showStorageInfo = !showStorageInfo
                                                viewModel.onIntent(FileManagerIntent.RefreshStorageInfo)
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.Info, contentDescription = null)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Storage info (collapsible)
                    AnimatedVisibility(
                        visible = showStorageInfo && uiState.storageInfo != null,
                        enter = slideInVertically() + fadeIn(),
                        exit = slideOutVertically() + fadeOut()
                    ) {
                        uiState.storageInfo?.let {
                            StorageInfoCard(
                                storageInfo = it,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Breadcrumbs
                    if (!showSearch) {
                        BreadcrumbBar(
                            breadcrumbs = uiState.breadcrumbs,
                            onBreadcrumbClick = { viewModel.onIntent(FileManagerIntent.BreadcrumbClicked(it)) }
                        )
                    }

                    // Multi-select action bar
                    AnimatedVisibility(
                        visible = uiState.isMultiSelectMode,
                        enter = slideInVertically() + fadeIn(),
                        exit = slideOutVertically() + fadeOut()
                    ) {
                        MultiSelectActionBar(
                            selectedCount = uiState.selectedItems.size,
                            onCopy = { viewModel.onIntent(FileManagerIntent.CopySelected) },
                            onCut = { viewModel.onIntent(FileManagerIntent.CutSelected) },
                            onDelete = { viewModel.onIntent(FileManagerIntent.ShowDeleteConfirmDialog) },
                            onSelectAll = { viewModel.onIntent(FileManagerIntent.SelectAll) },
                            onClearSelection = { viewModel.onIntent(FileManagerIntent.ClearSelection) },
                            onRename = {
                                val selected = uiState.selectedItems
                                val item = uiState.items.firstOrNull { it.path in selected }
                                if (item != null) {
                                    viewModel.onIntent(FileManagerIntent.ShowRenameDialog(item))
                                }
                            },
                            onProperties = {
                                val selected = uiState.selectedItems
                                val item = uiState.items.firstOrNull { it.path in selected }
                                if (item != null) {
                                    viewModel.onIntent(FileManagerIntent.ShowProperties(item))
                                }
                            },
                            showSingleItemActions = uiState.selectedItems.size == 1
                        )
                    }

                    // Paste bar
                    AnimatedVisibility(
                        visible = uiState.clipboard != null,
                        enter = slideInVertically() + fadeIn(),
                        exit = slideOutVertically() + fadeOut()
                    ) {
                        PasteBar(
                            itemCount = uiState.clipboard?.items?.size ?: 0,
                            isCut = uiState.clipboard?.operation == com.nextvm.core.model.ClipboardOperation.CUT,
                            onPaste = { viewModel.onIntent(FileManagerIntent.Paste) },
                            onClear = {
                                // Clear clipboard by setting it to null through a paste cancel
                                viewModel.onIntent(FileManagerIntent.ClearSelection)
                            }
                        )
                    }
                }
            },
            floatingActionButton = {
                Column(horizontalAlignment = Alignment.End) {
                    AnimatedVisibility(
                        visible = showFabMenu,
                        enter = fadeIn() + slideInVertically { it },
                        exit = fadeOut() + slideOutVertically { it }
                    ) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SmallFloatingActionButton(
                                onClick = {
                                    showFabMenu = false
                                    viewModel.onIntent(FileManagerIntent.ShowCreateFileDialog)
                                },
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.NoteAdd, contentDescription = "New File")
                            }
                            SmallFloatingActionButton(
                                onClick = {
                                    showFabMenu = false
                                    viewModel.onIntent(FileManagerIntent.ShowCreateFolderDialog)
                                },
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    FloatingActionButton(
                        onClick = { showFabMenu = !showFabMenu },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.shadow(8.dp, RoundedCornerShape(16.dp))
                    ) {
                        Icon(
                            if (showFabMenu) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = "Create"
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when {
                    uiState.isLoading -> LoadingState()
                    showSearch && uiState.searchQuery.length >= 2 -> {
                        if (uiState.isSearching) {
                            LoadingState()
                        } else if (uiState.searchResults.isEmpty()) {
                            EmptySearchState(query = uiState.searchQuery)
                        } else {
                            FileListContent(
                                viewModel = viewModel,
                                uiState = uiState,
                                items = uiState.searchResults
                            )
                        }
                    }
                    uiState.items.isEmpty() -> EmptyDirectoryState()
                    else -> FileListContent(
                        viewModel = viewModel,
                        uiState = uiState,
                        items = uiState.items
                    )
                }
            }
        }
    }

    // Dialogs
    if (uiState.showCreateFolderDialog) {
        CreateFolderDialog(
            onConfirm = { viewModel.onIntent(FileManagerIntent.CreateFolder(it)) },
            onDismiss = { viewModel.onIntent(FileManagerIntent.DismissDialog) }
        )
    }

    if (uiState.showCreateFileDialog) {
        CreateFileDialog(
            onConfirm = { viewModel.onIntent(FileManagerIntent.CreateFile(it)) },
            onDismiss = { viewModel.onIntent(FileManagerIntent.DismissDialog) }
        )
    }

    if (uiState.showRenameDialog && uiState.renameTarget != null) {
        RenameDialog(
            item = uiState.renameTarget!!,
            onConfirm = { item, newName -> viewModel.onIntent(FileManagerIntent.RenameItem(item, newName)) },
            onDismiss = { viewModel.onIntent(FileManagerIntent.DismissDialog) }
        )
    }

    if (uiState.showDeleteConfirmDialog) {
        DeleteConfirmDialog(
            count = uiState.selectedItems.size,
            onConfirm = { viewModel.onIntent(FileManagerIntent.DeleteSelected) },
            onDismiss = { viewModel.onIntent(FileManagerIntent.DismissDialog) }
        )
    }

    if (uiState.showPropertiesDialog && uiState.propertiesTarget != null) {
        PropertiesDialog(
            item = uiState.propertiesTarget!!,
            fileOps = fileOps,
            onDismiss = { viewModel.onIntent(FileManagerIntent.DismissDialog) }
        )
    }

    if (uiState.showSortDialog) {
        SortDialog(
            currentSortMode = uiState.sortMode,
            onSortSelected = { viewModel.onIntent(FileManagerIntent.SetSortMode(it)) },
            onDismiss = { viewModel.onIntent(FileManagerIntent.DismissDialog) }
        )
    }
}

@Composable
private fun FileListContent(
    viewModel: FileManagerViewModel,
    uiState: FileManagerUiState,
    items: List<com.nextvm.core.model.FileItem>
) {
    if (uiState.viewMode == FileViewMode.LIST) {
        LazyColumn(
            contentPadding = PaddingValues(vertical = 4.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items, key = { it.path }) { item ->
                FileListItem(
                    item = item,
                    isSelected = item.path in uiState.selectedItems,
                    isMultiSelectMode = uiState.isMultiSelectMode,
                    onClick = { viewModel.onIntent(FileManagerIntent.ItemClicked(item)) },
                    onLongClick = { viewModel.onIntent(FileManagerIntent.ItemLongClicked(item)) }
                )
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items, key = { it.path }) { item ->
                FileGridItem(
                    item = item,
                    isSelected = item.path in uiState.selectedItems,
                    isMultiSelectMode = uiState.isMultiSelectMode,
                    onClick = { viewModel.onIntent(FileManagerIntent.ItemClicked(item)) },
                    onLongClick = { viewModel.onIntent(FileManagerIntent.ItemLongClicked(item)) }
                )
            }
        }
    }
}

@Composable
private fun MultiSelectActionBar(
    selectedCount: Int,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRename: () -> Unit,
    onProperties: () -> Unit,
    showSingleItemActions: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClearSelection) {
            Icon(Icons.Default.Close, contentDescription = "Clear selection", modifier = Modifier.size(20.dp))
        }
        Text(
            text = "$selectedCount selected",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Spacer(modifier = Modifier.weight(1f))

        IconButton(onClick = onSelectAll) {
            Icon(Icons.Default.SelectAll, contentDescription = "Select all", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onCopy) {
            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onCut) {
            Icon(Icons.Default.ContentCut, contentDescription = "Cut", modifier = Modifier.size(20.dp))
        }
        if (showSingleItemActions) {
            IconButton(onClick = onRename) {
                Icon(Icons.Default.DriveFileRenameOutline, contentDescription = "Rename", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onProperties) {
                Icon(Icons.Default.Info, contentDescription = "Properties", modifier = Modifier.size(20.dp))
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun PasteBar(
    itemCount: Int,
    isCut: Boolean,
    onPaste: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.ContentPaste,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onTertiaryContainer
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$itemCount ${if (isCut) "to move" else "to paste"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onPaste) {
            Text("Paste Here", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Loading...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyDirectoryState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Empty Folder",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "This directory has no files or folders.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun EmptySearchState(query: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No results for \"$query\"",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

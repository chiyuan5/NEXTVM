package com.nextvm.feature.filemanager

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextvm.core.model.ClipboardOperation
import com.nextvm.core.model.FileClipboard
import com.nextvm.core.model.FileItem
import com.nextvm.core.model.FileSortMode
import com.nextvm.core.model.FileViewMode
import com.nextvm.core.model.StorageInfo
import com.nextvm.core.model.VmResult
import com.nextvm.feature.filemanager.FileOperationsManager.Companion.toFileItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

// ============================================================
// MVI CONTRACT
// ============================================================

data class BreadcrumbItem(
    val name: String,
    val path: String
)

data class FileManagerUiState(
    val isLoading: Boolean = false,
    val currentPath: String = "",
    val breadcrumbs: List<BreadcrumbItem> = emptyList(),
    val items: List<FileItem> = emptyList(),
    val selectedItems: Set<String> = emptySet(),
    val isMultiSelectMode: Boolean = false,
    val clipboard: FileClipboard? = null,
    val sortMode: FileSortMode = FileSortMode.NAME_ASC,
    val viewMode: FileViewMode = FileViewMode.LIST,
    val storageInfo: StorageInfo? = null,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<FileItem> = emptyList(),
    val error: String? = null,
    val showHiddenFiles: Boolean = false,
    // Dialog state
    val showCreateFolderDialog: Boolean = false,
    val showCreateFileDialog: Boolean = false,
    val showRenameDialog: Boolean = false,
    val showDeleteConfirmDialog: Boolean = false,
    val showPropertiesDialog: Boolean = false,
    val showSortDialog: Boolean = false,
    val propertiesTarget: FileItem? = null,
    val renameTarget: FileItem? = null
)

sealed class FileManagerIntent {
    data class NavigateTo(val path: String) : FileManagerIntent()
    data object NavigateUp : FileManagerIntent()
    data class BreadcrumbClicked(val path: String) : FileManagerIntent()
    // Selection
    data class ToggleSelection(val item: FileItem) : FileManagerIntent()
    data object SelectAll : FileManagerIntent()
    data object ClearSelection : FileManagerIntent()
    data class ItemLongClicked(val item: FileItem) : FileManagerIntent()
    // File operations
    data class CreateFolder(val name: String) : FileManagerIntent()
    data class CreateFile(val name: String) : FileManagerIntent()
    data class RenameItem(val item: FileItem, val newName: String) : FileManagerIntent()
    data object DeleteSelected : FileManagerIntent()
    data object CopySelected : FileManagerIntent()
    data object CutSelected : FileManagerIntent()
    data object Paste : FileManagerIntent()
    // View
    data class SetSortMode(val mode: FileSortMode) : FileManagerIntent()
    data object ToggleViewMode : FileManagerIntent()
    data object ToggleHiddenFiles : FileManagerIntent()
    // Search
    data class Search(val query: String) : FileManagerIntent()
    data object ClearSearch : FileManagerIntent()
    // Dialogs
    data object ShowCreateFolderDialog : FileManagerIntent()
    data object ShowCreateFileDialog : FileManagerIntent()
    data class ShowRenameDialog(val item: FileItem) : FileManagerIntent()
    data object ShowDeleteConfirmDialog : FileManagerIntent()
    data class ShowProperties(val item: FileItem) : FileManagerIntent()
    data object ShowSortDialog : FileManagerIntent()
    data object DismissDialog : FileManagerIntent()
    // Storage
    data object RefreshStorageInfo : FileManagerIntent()
    data object Refresh : FileManagerIntent()
    // Item click
    data class ItemClicked(val item: FileItem) : FileManagerIntent()
}

sealed class FileManagerEffect {
    data class ShowError(val message: String) : FileManagerEffect()
    data class ShowSuccess(val message: String) : FileManagerEffect()
}

// ============================================================
// VIEWMODEL
// ============================================================

@HiltViewModel
class FileManagerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileOps: FileOperationsManager
) : ViewModel() {

    private val virtualRoot: File = context.getDir("virtual", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(FileManagerUiState())
    val uiState: StateFlow<FileManagerUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<FileManagerEffect>()
    val effects: SharedFlow<FileManagerEffect> = _effects.asSharedFlow()

    init {
        virtualRoot.mkdirs()
        navigateTo(virtualRoot.absolutePath)
        loadStorageInfo()
    }

    fun onIntent(intent: FileManagerIntent) {
        when (intent) {
            is FileManagerIntent.NavigateTo -> navigateTo(intent.path)
            is FileManagerIntent.NavigateUp -> navigateUp()
            is FileManagerIntent.BreadcrumbClicked -> navigateTo(intent.path)
            is FileManagerIntent.ToggleSelection -> toggleSelection(intent.item)
            is FileManagerIntent.SelectAll -> selectAll()
            is FileManagerIntent.ClearSelection -> clearSelection()
            is FileManagerIntent.ItemLongClicked -> enterMultiSelect(intent.item)
            is FileManagerIntent.CreateFolder -> createFolder(intent.name)
            is FileManagerIntent.CreateFile -> createFile(intent.name)
            is FileManagerIntent.RenameItem -> renameItem(intent.item, intent.newName)
            is FileManagerIntent.DeleteSelected -> deleteSelected()
            is FileManagerIntent.CopySelected -> copySelected()
            is FileManagerIntent.CutSelected -> cutSelected()
            is FileManagerIntent.Paste -> paste()
            is FileManagerIntent.SetSortMode -> setSortMode(intent.mode)
            is FileManagerIntent.ToggleViewMode -> toggleViewMode()
            is FileManagerIntent.ToggleHiddenFiles -> toggleHiddenFiles()
            is FileManagerIntent.Search -> search(intent.query)
            is FileManagerIntent.ClearSearch -> clearSearch()
            is FileManagerIntent.ShowCreateFolderDialog -> _uiState.update { it.copy(showCreateFolderDialog = true) }
            is FileManagerIntent.ShowCreateFileDialog -> _uiState.update { it.copy(showCreateFileDialog = true) }
            is FileManagerIntent.ShowRenameDialog -> _uiState.update { it.copy(showRenameDialog = true, renameTarget = intent.item) }
            is FileManagerIntent.ShowDeleteConfirmDialog -> _uiState.update { it.copy(showDeleteConfirmDialog = true) }
            is FileManagerIntent.ShowProperties -> showProperties(intent.item)
            is FileManagerIntent.ShowSortDialog -> _uiState.update { it.copy(showSortDialog = true) }
            is FileManagerIntent.DismissDialog -> dismissAllDialogs()
            is FileManagerIntent.RefreshStorageInfo -> loadStorageInfo()
            is FileManagerIntent.Refresh -> refresh()
            is FileManagerIntent.ItemClicked -> handleItemClick(intent.item)
        }
    }

    private fun navigateTo(path: String) {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) {
            viewModelScope.launch { _effects.emit(FileManagerEffect.ShowError("Cannot access directory")) }
            return
        }
        // Prevent navigating above virtual root
        if (!dir.absolutePath.startsWith(virtualRoot.absolutePath)) {
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val state = _uiState.value
                val items = fileOps.listFiles(dir, state.sortMode, state.showHiddenFiles)
                val breadcrumbs = buildBreadcrumbs(dir.absolutePath)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentPath = dir.absolutePath,
                        items = items,
                        breadcrumbs = breadcrumbs,
                        selectedItems = emptySet(),
                        isMultiSelectMode = false,
                        isSearching = false,
                        searchQuery = "",
                        searchResults = emptyList(),
                        error = null
                    )
                }
            } catch (e: Exception) {
                Timber.tag("FileManagerVM").e(e, "navigateTo failed")
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun navigateUp() {
        val current = File(_uiState.value.currentPath)
        if (current.absolutePath == virtualRoot.absolutePath) return
        val parent = current.parentFile ?: return
        if (!parent.absolutePath.startsWith(virtualRoot.absolutePath)) return
        navigateTo(parent.absolutePath)
    }

    private fun handleItemClick(item: FileItem) {
        if (_uiState.value.isMultiSelectMode) {
            toggleSelection(item)
        } else if (item.isDirectory) {
            navigateTo(item.path)
        }
        // Non-directory files in non-select mode: no action (could open viewer later)
    }

    private fun enterMultiSelect(item: FileItem) {
        _uiState.update {
            it.copy(
                isMultiSelectMode = true,
                selectedItems = setOf(item.path)
            )
        }
    }

    private fun toggleSelection(item: FileItem) {
        _uiState.update {
            val newSelection = it.selectedItems.toMutableSet()
            if (item.path in newSelection) {
                newSelection.remove(item.path)
            } else {
                newSelection.add(item.path)
            }
            val stillInMultiSelect = newSelection.isNotEmpty()
            it.copy(
                selectedItems = newSelection,
                isMultiSelectMode = stillInMultiSelect
            )
        }
    }

    private fun selectAll() {
        _uiState.update {
            it.copy(selectedItems = it.items.map { item -> item.path }.toSet())
        }
    }

    private fun clearSelection() {
        _uiState.update {
            it.copy(selectedItems = emptySet(), isMultiSelectMode = false)
        }
    }

    private fun createFolder(name: String) {
        viewModelScope.launch {
            val result = fileOps.createDirectory(File(_uiState.value.currentPath), name)
            when (result) {
                is VmResult.Success -> {
                    _effects.emit(FileManagerEffect.ShowSuccess("Folder created"))
                    dismissAllDialogs()
                    refresh()
                }
                is VmResult.Error -> {
                    _effects.emit(FileManagerEffect.ShowError(result.message))
                }
                is VmResult.Loading -> {}
            }
        }
    }

    private fun createFile(name: String) {
        viewModelScope.launch {
            val result = fileOps.createFile(File(_uiState.value.currentPath), name)
            when (result) {
                is VmResult.Success -> {
                    _effects.emit(FileManagerEffect.ShowSuccess("File created"))
                    dismissAllDialogs()
                    refresh()
                }
                is VmResult.Error -> {
                    _effects.emit(FileManagerEffect.ShowError(result.message))
                }
                is VmResult.Loading -> {}
            }
        }
    }

    private fun renameItem(item: FileItem, newName: String) {
        viewModelScope.launch {
            val result = fileOps.renameItem(item, newName)
            when (result) {
                is VmResult.Success -> {
                    _effects.emit(FileManagerEffect.ShowSuccess("Renamed successfully"))
                    dismissAllDialogs()
                    refresh()
                }
                is VmResult.Error -> {
                    _effects.emit(FileManagerEffect.ShowError(result.message))
                }
                is VmResult.Loading -> {}
            }
        }
    }

    private fun deleteSelected() {
        viewModelScope.launch {
            val selected = _uiState.value.selectedItems
            val items = _uiState.value.items.filter { it.path in selected }
            if (items.isEmpty()) return@launch

            val result = fileOps.deleteItems(items)
            when (result) {
                is VmResult.Success -> {
                    _effects.emit(FileManagerEffect.ShowSuccess("Deleted ${result.data} items"))
                    dismissAllDialogs()
                    clearSelection()
                    refresh()
                }
                is VmResult.Error -> {
                    _effects.emit(FileManagerEffect.ShowError(result.message))
                }
                is VmResult.Loading -> {}
            }
        }
    }

    private fun copySelected() {
        val selected = _uiState.value.selectedItems
        val items = _uiState.value.items.filter { it.path in selected }
        if (items.isEmpty()) return
        _uiState.update {
            it.copy(
                clipboard = FileClipboard(items, ClipboardOperation.COPY),
                isMultiSelectMode = false,
                selectedItems = emptySet()
            )
        }
        viewModelScope.launch {
            _effects.emit(FileManagerEffect.ShowSuccess("${items.size} items copied"))
        }
    }

    private fun cutSelected() {
        val selected = _uiState.value.selectedItems
        val items = _uiState.value.items.filter { it.path in selected }
        if (items.isEmpty()) return
        _uiState.update {
            it.copy(
                clipboard = FileClipboard(items, ClipboardOperation.CUT),
                isMultiSelectMode = false,
                selectedItems = emptySet()
            )
        }
        viewModelScope.launch {
            _effects.emit(FileManagerEffect.ShowSuccess("${items.size} items cut"))
        }
    }

    private fun paste() {
        val clipboard = _uiState.value.clipboard ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val destination = File(_uiState.value.currentPath)
            val result = when (clipboard.operation) {
                ClipboardOperation.COPY -> fileOps.copyItems(clipboard.items, destination)
                ClipboardOperation.CUT -> fileOps.moveItems(clipboard.items, destination)
            }
            when (result) {
                is VmResult.Success -> {
                    val verb = if (clipboard.operation == ClipboardOperation.COPY) "Pasted" else "Moved"
                    _effects.emit(FileManagerEffect.ShowSuccess("$verb ${result.data} items"))
                    // Clear clipboard after cut, keep after copy
                    if (clipboard.operation == ClipboardOperation.CUT) {
                        _uiState.update { it.copy(clipboard = null) }
                    }
                    refresh()
                }
                is VmResult.Error -> {
                    _uiState.update { it.copy(isLoading = false) }
                    _effects.emit(FileManagerEffect.ShowError(result.message))
                }
                is VmResult.Loading -> {}
            }
        }
    }

    private fun setSortMode(mode: FileSortMode) {
        _uiState.update { it.copy(sortMode = mode, showSortDialog = false) }
        refresh()
    }

    private fun toggleViewMode() {
        _uiState.update {
            it.copy(
                viewMode = if (it.viewMode == FileViewMode.LIST) FileViewMode.GRID else FileViewMode.LIST
            )
        }
    }

    private fun toggleHiddenFiles() {
        _uiState.update { it.copy(showHiddenFiles = !it.showHiddenFiles) }
        refresh()
    }

    private fun search(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        if (query.length < 2) {
            _uiState.update { it.copy(isSearching = false, searchResults = emptyList()) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true) }
            val results = fileOps.searchFiles(virtualRoot, query)
            _uiState.update { it.copy(isSearching = false, searchResults = results) }
        }
    }

    private fun clearSearch() {
        _uiState.update {
            it.copy(searchQuery = "", isSearching = false, searchResults = emptyList())
        }
    }

    private fun showProperties(item: FileItem) {
        _uiState.update {
            it.copy(showPropertiesDialog = true, propertiesTarget = item)
        }
    }

    private fun dismissAllDialogs() {
        _uiState.update {
            it.copy(
                showCreateFolderDialog = false,
                showCreateFileDialog = false,
                showRenameDialog = false,
                showDeleteConfirmDialog = false,
                showPropertiesDialog = false,
                showSortDialog = false,
                renameTarget = null,
                propertiesTarget = null
            )
        }
    }

    private fun refresh() {
        navigateTo(_uiState.value.currentPath)
    }

    private fun loadStorageInfo() {
        viewModelScope.launch {
            val info = fileOps.getStorageInfo(virtualRoot)
            _uiState.update { it.copy(storageInfo = info) }
        }
    }

    private fun buildBreadcrumbs(path: String): List<BreadcrumbItem> {
        val rootPath = virtualRoot.absolutePath
        val crumbs = mutableListOf(BreadcrumbItem("Virtual Storage", rootPath))

        if (path == rootPath) return crumbs

        val relativePath = path.removePrefix(rootPath).trimStart(File.separatorChar)
        val segments = relativePath.split(File.separator)
        var currentPath = rootPath
        for (segment in segments) {
            if (segment.isEmpty()) continue
            currentPath = "$currentPath${File.separator}$segment"
            crumbs.add(BreadcrumbItem(segment, currentPath))
        }
        return crumbs
    }
}

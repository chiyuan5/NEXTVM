package com.nextvm.feature.launcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextvm.core.model.EngineStatus
import com.nextvm.core.model.LauncherItem
import com.nextvm.core.model.SystemApp
import com.nextvm.core.model.SystemAppIcon
import com.nextvm.core.model.VirtualApp
import com.nextvm.core.model.VmResult
import com.nextvm.core.virtualization.engine.VirtualEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// ============================================================
// MVI CONTRACT
// ============================================================

data class LauncherUiState(
    val isLoading: Boolean = true,
    val engineStatus: EngineStatus = EngineStatus.NOT_INITIALIZED,
    val launcherItems: List<LauncherItem> = emptyList(),
    val error: String? = null,
    val selectedApp: VirtualApp? = null
) {
    val virtualAppCount: Int get() = launcherItems.count { it is LauncherItem.Virtual }
}

sealed class LauncherIntent {
    data object Refresh : LauncherIntent()
    data class LaunchApp(val app: VirtualApp) : LauncherIntent()
    data class LaunchSystemApp(val route: String) : LauncherIntent()
    data class ShowAppOptions(val app: VirtualApp) : LauncherIntent()
    data class UninstallApp(val app: VirtualApp) : LauncherIntent()
}

sealed class LauncherEffect {
    data class ShowError(val message: String) : LauncherEffect()
    data class AppLaunched(val appName: String) : LauncherEffect()
    data class AppUninstalled(val appName: String) : LauncherEffect()
    data class NavigateToRoute(val route: String) : LauncherEffect()
}

// ============================================================
// VIEWMODEL
// ============================================================

@HiltViewModel
class LauncherViewModel @Inject constructor(
    private val engine: VirtualEngine
) : ViewModel() {

    companion object {
        val SYSTEM_APPS = listOf(
            SystemApp(
                id = "com.nextvm.filemanager",
                appName = "Files",
                route = "filemanager",
                iconType = SystemAppIcon.FILE_MANAGER
            )
        )
    }

    private val _uiState = MutableStateFlow(LauncherUiState())
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<LauncherEffect>()
    val effects: SharedFlow<LauncherEffect> = _effects.asSharedFlow()

    init {
        // Observe engine initialization status.
        // Shows initialization screen until engine is READY.
        viewModelScope.launch {
            engine.status.collect { status ->
                _uiState.update { it.copy(engineStatus = status) }
                Timber.tag("LauncherVM").d("Engine status: $status")
            }
        }

        // Observe the engine's installed apps StateFlow.
        // This automatically updates the launcher grid whenever:
        //   - A new app is installed (from AppManager or any other source)
        //   - An app is uninstalled
        //   - An app's running state changes
        // No manual refresh needed.
        viewModelScope.launch {
            engine.installedApps.collect { apps ->
                val items = buildLauncherItems(apps)
                _uiState.update { it.copy(isLoading = false, launcherItems = items, error = null) }
                Timber.tag("LauncherVM").d("Apps updated: ${apps.size} virtual apps")
            }
        }
    }

    fun onIntent(intent: LauncherIntent) {
        when (intent) {
            is LauncherIntent.Refresh -> refreshApps()
            is LauncherIntent.LaunchApp -> launchApp(intent.app)
            is LauncherIntent.LaunchSystemApp -> launchSystemApp(intent.route)
            is LauncherIntent.ShowAppOptions -> showAppOptions(intent.app)
            is LauncherIntent.UninstallApp -> uninstallApp(intent.app)
        }
    }

    private fun buildLauncherItems(apps: List<VirtualApp>): List<LauncherItem> {
        val systemItems = SYSTEM_APPS.map { LauncherItem.System(it) }
        val virtualItems = apps.map { LauncherItem.Virtual(it) }
        return systemItems + virtualItems
    }

    private fun refreshApps() {
        // The StateFlow collector handles updates automatically.
        // Refresh just re-reads from engine to pick up any missed changes.
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val apps = engine.getInstalledApps()
                val items = buildLauncherItems(apps)
                _uiState.update { it.copy(isLoading = false, launcherItems = items) }
            } catch (e: Exception) {
                Timber.tag("LauncherVM").e(e, "Failed to refresh apps")
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Unknown error")
                }
            }
        }
    }

    private fun launchApp(app: VirtualApp) {
        viewModelScope.launch {
            Timber.tag("LauncherVM").d("Launching ${app.packageName}")
            when (val result = engine.launchApp(app.instanceId)) {
                is VmResult.Success -> {
                    _effects.emit(LauncherEffect.AppLaunched(app.appName))
                    // No manual refresh needed — StateFlow auto-updates running state
                }
                is VmResult.Error -> {
                    Timber.tag("LauncherVM").e(result.exception, "Launch failed: ${result.message}")
                    _effects.emit(LauncherEffect.ShowError("Failed to launch: ${result.message}"))
                }
                is VmResult.Loading -> { /* no-op */ }
            }
        }
    }

    private fun launchSystemApp(route: String) {
        viewModelScope.launch {
            _effects.emit(LauncherEffect.NavigateToRoute(route))
        }
    }

    private fun showAppOptions(app: VirtualApp) {
        _uiState.update { it.copy(selectedApp = app) }
    }

    private fun uninstallApp(app: VirtualApp) {
        viewModelScope.launch {
            Timber.tag("LauncherVM").d("Uninstalling ${app.packageName}")
            when (val result = engine.uninstallApp(app.instanceId)) {
                is VmResult.Success -> {
                    _effects.emit(LauncherEffect.AppUninstalled(app.appName))
                    // No manual refresh needed — StateFlow auto-removes the app
                }
                is VmResult.Error -> {
                    Timber.tag("LauncherVM").e(result.exception, "Uninstall failed")
                    _effects.emit(LauncherEffect.ShowError("Failed to uninstall: ${result.message}"))
                }
                is VmResult.Loading -> { /* no-op */ }
            }
        }
    }
}

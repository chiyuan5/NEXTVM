package com.nextvm.feature.appmanager

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextvm.core.apk.ApkParser
import com.nextvm.core.model.ApkInfo
import com.nextvm.core.model.VmResult
import com.nextvm.core.virtualization.engine.VirtualEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

// ============================================================
// MVI CONTRACT
// ============================================================

data class DeviceAppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val apkPath: String,
    val versionName: String,
    val isSystemApp: Boolean
)

data class AppManagerUiState(
    val isLoading: Boolean = false,
    val isInstalling: Boolean = false,
    val installProgress: Float = 0f,
    val installStatus: String = "",
    val selectedApk: ApkInfo? = null,
    val selectedApkPath: String? = null,
    val installedApps: List<ApkInfo> = emptyList(),
    val deviceApps: List<DeviceAppInfo> = emptyList(),
    val deviceAppsLoading: Boolean = false,
    val searchQuery: String = "",
    val error: String? = null
)

sealed class AppManagerIntent {
    data class ApkSelected(val uri: Uri) : AppManagerIntent()
    data object InstallSelected : AppManagerIntent()
    data class UninstallApp(val packageName: String) : AppManagerIntent()
    data class ShowAppInfo(val packageName: String) : AppManagerIntent()
    data object LoadDeviceApps : AppManagerIntent()
    data class InstallDeviceApp(val deviceApp: DeviceAppInfo) : AppManagerIntent()
    data class SearchDeviceApps(val query: String) : AppManagerIntent()
}

sealed class AppManagerEffect {
    data class ShowError(val message: String) : AppManagerEffect()
    data class InstallSuccess(val appName: String) : AppManagerEffect()
    data object NavigateBack : AppManagerEffect()
}

// ============================================================
// VIEWMODEL
// ============================================================

@HiltViewModel
class AppManagerViewModel @Inject constructor(
    application: Application,
    private val engine: VirtualEngine,
    private val apkParser: ApkParser
) : ViewModel() {

    private val context: Context = application.applicationContext

    private val _uiState = MutableStateFlow(AppManagerUiState())
    val uiState: StateFlow<AppManagerUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<AppManagerEffect>()
    val effects: SharedFlow<AppManagerEffect> = _effects.asSharedFlow()

    private var allDeviceApps: List<DeviceAppInfo> = emptyList()

    init {
        loadInstalledApps()
    }

    fun onIntent(intent: AppManagerIntent) {
        when (intent) {
            is AppManagerIntent.ApkSelected -> handleApkSelected(intent.uri)
            is AppManagerIntent.InstallSelected -> installSelectedApk()
            is AppManagerIntent.UninstallApp -> uninstallApp(intent.packageName)
            is AppManagerIntent.ShowAppInfo -> { /* Navigate to detail */ }
            is AppManagerIntent.LoadDeviceApps -> loadDeviceApps()
            is AppManagerIntent.InstallDeviceApp -> installDeviceApp(intent.deviceApp)
            is AppManagerIntent.SearchDeviceApps -> filterDeviceApps(intent.query)
        }
    }

    /**
     * Load device-installed apps in two phases for fast perceived performance:
     *
     * Phase 1 (instant): Load app names and APK paths only — no icon decoding.
     *   The list appears immediately with placeholder icons.
     *
     * Phase 2 (background): Load icons in parallel batches and update the list
     *   progressively as each batch completes.
     */
    private fun loadDeviceApps() {
        if (allDeviceApps.isNotEmpty()) {
            // Already loaded — just show cached list
            _uiState.update { it.copy(deviceApps = allDeviceApps, deviceAppsLoading = false) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(deviceAppsLoading = true) }

            // Phase 1: Get app list without icons (fast — no bitmap decoding)
            val appsNoIcons = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                pm.getInstalledApplications(0) // No flags = fastest query
                    .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                    .map { appInfo ->
                        DeviceAppInfo(
                            packageName = appInfo.packageName,
                            appName = appInfo.loadLabel(pm).toString(),
                            icon = null, // Skip icon for now
                            apkPath = appInfo.sourceDir,
                            versionName = "",
                            isSystemApp = false
                        )
                    }
                    .sortedBy { it.appName.lowercase() }
            }

            // Show list immediately with placeholder icons
            allDeviceApps = appsNoIcons
            _uiState.update { it.copy(deviceApps = appsNoIcons, deviceAppsLoading = false) }

            // Phase 2: Load icons in parallel batches of 10
            val pm = context.packageManager
            val batchSize = 10
            val chunks = appsNoIcons.chunked(batchSize)

            for (chunk in chunks) {
                val updatedChunk = withContext(Dispatchers.IO) {
                    chunk.map { app ->
                        async {
                            try {
                                val appInfo = pm.getApplicationInfo(app.packageName, 0)
                                val icon = pm.getApplicationIcon(appInfo)
                                val pkgInfo = pm.getPackageInfo(app.packageName, 0)
                                app.copy(
                                    icon = icon,
                                    versionName = pkgInfo.versionName ?: ""
                                )
                            } catch (_: Exception) {
                                app
                            }
                        }
                    }.awaitAll()
                }

                // Merge updated entries back into the full list
                val updatedPkgs = updatedChunk.associateBy { it.packageName }
                allDeviceApps = allDeviceApps.map { updatedPkgs[it.packageName] ?: it }

                // Update UI with current search filter applied
                val query = _uiState.value.searchQuery
                val filtered = if (query.isBlank()) allDeviceApps
                else allDeviceApps.filter {
                    it.appName.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
                }
                _uiState.update { it.copy(deviceApps = filtered) }
            }
        }
    }

    private fun filterDeviceApps(query: String) {
        _uiState.update { state ->
            val filtered = if (query.isBlank()) allDeviceApps
            else allDeviceApps.filter {
                it.appName.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
            }
            state.copy(searchQuery = query, deviceApps = filtered)
        }
    }

    private fun installDeviceApp(deviceApp: DeviceAppInfo) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isInstalling = true,
                    installProgress = 0f,
                    installStatus = "Installing ${deviceApp.appName}..."
                )
            }

            // Check engine readiness
            if (engine.status.value != com.nextvm.core.model.EngineStatus.READY) {
                _uiState.update { it.copy(installStatus = "Initializing engine...") }
                when (val initResult = engine.initialize()) {
                    is VmResult.Error -> {
                        _uiState.update { it.copy(isInstalling = false) }
                        _effects.emit(AppManagerEffect.ShowError("Engine init failed: ${initResult.message}"))
                        return@launch
                    }
                    else -> { /* continue */ }
                }
            }

            try {
                _uiState.update { it.copy(installProgress = 0.3f, installStatus = "Copying APK...") }

                when (val result = engine.installApp(deviceApp.apkPath)) {
                    is VmResult.Success -> {
                        _uiState.update {
                            it.copy(isInstalling = false, installProgress = 1f, installStatus = "Complete!")
                        }
                        _effects.emit(AppManagerEffect.InstallSuccess(deviceApp.appName))
                        loadInstalledApps()
                    }
                    is VmResult.Error -> {
                        _uiState.update { it.copy(isInstalling = false, installProgress = 0f) }
                        _effects.emit(AppManagerEffect.ShowError("Install failed: ${result.message}"))
                    }
                    is VmResult.Loading -> { /* no-op */ }
                }
            } catch (e: Exception) {
                Timber.tag("AppManagerVM").e(e, "Install device app error")
                _uiState.update { it.copy(isInstalling = false, installProgress = 0f) }
                _effects.emit(AppManagerEffect.ShowError(e.message ?: "Install failed"))
            }
        }
    }

    private fun handleApkSelected(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val apkPath = withContext(Dispatchers.IO) { copyUriToTempFile(uri) }
                val apkInfo = apkParser.parseApk(apkPath)
                if (apkInfo != null) {
                    _uiState.update { it.copy(isLoading = false, selectedApk = apkInfo, selectedApkPath = apkPath) }
                    Timber.tag("AppManagerVM").d("Parsed APK: ${apkInfo.packageName}")
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                    _effects.emit(AppManagerEffect.ShowError("Failed to parse APK file"))
                }
            } catch (e: Exception) {
                Timber.tag("AppManagerVM").e(e, "Failed to select APK")
                _uiState.update { it.copy(isLoading = false) }
                _effects.emit(AppManagerEffect.ShowError(e.message ?: "Failed to read APK"))
            }
        }
    }

    private fun installSelectedApk() {
        val apkPath = _uiState.value.selectedApkPath ?: return
        val apkInfo = _uiState.value.selectedApk ?: return

        viewModelScope.launch {
            if (engine.status.value != com.nextvm.core.model.EngineStatus.READY) {
                _uiState.update {
                    it.copy(isInstalling = true, installProgress = 0f, installStatus = "Initializing engine...")
                }
                when (val initResult = engine.initialize()) {
                    is VmResult.Error -> {
                        _uiState.update { it.copy(isInstalling = false) }
                        _effects.emit(AppManagerEffect.ShowError("Engine initialization failed: ${initResult.message}"))
                        return@launch
                    }
                    else -> { /* continue */ }
                }
            }

            _uiState.update {
                it.copy(isInstalling = true, installProgress = 0f, installStatus = "Preparing installation...")
            }

            try {
                _uiState.update { it.copy(installProgress = 0.2f, installStatus = "Copying APK...") }
                _uiState.update { it.copy(installProgress = 0.4f, installStatus = "Extracting libraries...") }
                _uiState.update { it.copy(installProgress = 0.6f, installStatus = "Creating sandbox...") }
                _uiState.update { it.copy(installProgress = 0.8f, installStatus = "Registering app...") }

                when (val result = engine.installApp(apkPath)) {
                    is VmResult.Success -> {
                        _uiState.update {
                            it.copy(
                                isInstalling = false, installProgress = 1f,
                                installStatus = "Complete!", selectedApk = null, selectedApkPath = null
                            )
                        }
                        _effects.emit(AppManagerEffect.InstallSuccess(apkInfo.appName))
                        loadInstalledApps()
                        Timber.tag("AppManagerVM").d("Installed: ${apkInfo.packageName}")
                    }
                    is VmResult.Error -> {
                        _uiState.update { it.copy(isInstalling = false, installProgress = 0f) }
                        _effects.emit(AppManagerEffect.ShowError("Install failed: ${result.message}"))
                        Timber.tag("AppManagerVM").e(result.exception, "Install failed")
                    }
                    is VmResult.Loading -> { /* no-op */ }
                }
            } catch (e: Exception) {
                Timber.tag("AppManagerVM").e(e, "Install error")
                _uiState.update { it.copy(isInstalling = false, installProgress = 0f) }
                _effects.emit(AppManagerEffect.ShowError(e.message ?: "Install failed"))
            }
        }
    }

    private fun uninstallApp(packageName: String) {
        viewModelScope.launch {
            when (val result = engine.uninstallAppByPackage(packageName)) {
                is VmResult.Success -> {
                    loadInstalledApps()
                    Timber.tag("AppManagerVM").d("Uninstalled: $packageName")
                }
                is VmResult.Error -> {
                    _effects.emit(AppManagerEffect.ShowError("Uninstall failed: ${result.message}"))
                }
                is VmResult.Loading -> { /* no-op */ }
            }
        }
    }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            try {
                if (engine.status.value != com.nextvm.core.model.EngineStatus.READY) return@launch
                val apps = engine.getInstalledApps()
                val apkInfos = apps.mapNotNull { app -> apkParser.parseApk(app.apkPath) }
                _uiState.update { it.copy(installedApps = apkInfos) }
            } catch (e: Exception) {
                Timber.tag("AppManagerVM").e(e, "Failed to load installed apps")
            }
        }
    }

    private fun copyUriToTempFile(uri: Uri): String {
        val tempFile = File(context.cacheDir, "temp_install_${System.currentTimeMillis()}.apk")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Cannot open input stream for URI: $uri")
        return tempFile.absolutePath
    }
}

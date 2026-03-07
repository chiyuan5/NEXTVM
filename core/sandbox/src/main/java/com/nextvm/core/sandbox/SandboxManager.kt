package com.nextvm.core.sandbox

import android.content.Context
import com.nextvm.core.model.VirtualConstants
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SandboxManager — Manages isolated file system for virtual apps.
 *
 * Directory structure per virtual app instance:
 * virtual/
 * ├── apks/{instanceId}.apk
 * └── data/{instanceId}/
 *     ├── files/
 *     ├── cache/
 *     ├── shared_prefs/
 *     ├── databases/
 *     ├── lib/           (extracted native .so files)
 *     ├── code_cache/    (optimized DEX)
 *     ├── app_data/      (custom app directories)
 *     └── external_files/ (simulated external storage)
 */
@Singleton
class SandboxManager @Inject constructor(
    private val context: Context,
    private val virtualRoot: File
) {
    companion object {
        private const val TAG = "SandboxMgr"

        // Standard Android data subdirectories
        private val DATA_SUBDIRS = listOf(
            "files",
            "cache",
            "shared_prefs",
            "databases",
            "lib",
            "code_cache",
            "app_data",
            "external_files",
            "external_cache",
            "no_backup",
            "obb"
        )

        /**
         * Standard public directories on Android's external/shared storage.
         * File explorer apps use these via Environment.getExternalStoragePublicDirectory().
         */
        private val EXTERNAL_STORAGE_PUBLIC_DIRS = listOf(
            "Alarms",
            "Audiobooks",
            "DCIM",
            "Documents",
            "Download",
            "Movies",
            "Music",
            "Notifications",
            "Pictures",
            "Podcasts",
            "Recordings",
            "Ringtones"
        )
    }

    private val dataRoot = File(virtualRoot, VirtualConstants.VIRTUAL_DATA_DIR)

    init {
        dataRoot.mkdirs()
    }

    /**
     * Create the full data directory tree for a virtual app instance.
     * Also creates the per-app virtual external/shared storage with standard public directories.
     */
    fun createAppDataDir(instanceId: String, packageName: String) {
        val appDir = getAppDir(instanceId)

        for (subDir in DATA_SUBDIRS) {
            File(appDir, subDir).mkdirs()
        }

        // Create virtual shared/external storage with standard public directories
        val sdcard = getExternalStorageDir(instanceId)
        for (pub in EXTERNAL_STORAGE_PUBLIC_DIRS) {
            File(sdcard, pub).mkdirs()
        }
        // Android/data/{pkg} and Android/obb/{pkg} inside the virtual sdcard
        File(sdcard, "Android/data/$packageName").mkdirs()
        File(sdcard, "Android/obb/$packageName").mkdirs()
        File(sdcard, "Android/media/$packageName").mkdirs()

        Timber.tag(TAG).d("Created data dir for $instanceId at ${appDir.absolutePath}")
    }

    /**
     * Get the root data directory for an app instance.
     */
    fun getAppDir(instanceId: String): File {
        return File(dataRoot, instanceId).also { it.mkdirs() }
    }

    /**
     * Get the files directory for an app instance.
     */
    fun getFilesDir(instanceId: String): File {
        return File(getAppDir(instanceId), "files").also { it.mkdirs() }
    }

    /**
     * Get the cache directory for an app instance.
     */
    fun getCacheDir(instanceId: String): File {
        return File(getAppDir(instanceId), "cache").also { it.mkdirs() }
    }

    /**
     * Get the shared_prefs directory for an app instance.
     */
    fun getSharedPrefsDir(instanceId: String): File {
        return File(getAppDir(instanceId), "shared_prefs").also { it.mkdirs() }
    }

    /**
     * Get the databases directory for an app instance.
     */
    fun getDatabasesDir(instanceId: String): File {
        return File(getAppDir(instanceId), "databases").also { it.mkdirs() }
    }

    /**
     * Get the native lib directory for an app instance.
     */
    fun getLibDir(instanceId: String): File {
        return File(getAppDir(instanceId), "lib").also { it.mkdirs() }
    }

    /**
     * Get the path to the virtual APK for an app instance.
     * Returns null if the APK doesn't exist.
     */
    fun getApkPath(instanceId: String): String? {
        val apkFile = File(virtualRoot, "apks/${instanceId}.apk")
        return if (apkFile.exists()) apkFile.absolutePath else null
    }

    /**
     * Get the code_cache directory for an app instance.
     */
    fun getCodeCacheDir(instanceId: String): File {
        return File(getAppDir(instanceId), "code_cache").also { it.mkdirs() }
    }

    /**
     * Get the external files directory for an app instance.
     */
    fun getExternalFilesDir(instanceId: String): File {
        return File(getAppDir(instanceId), "external_files").also { it.mkdirs() }
    }

    /**
     * Delete all data for an app instance.
     */
    fun deleteAppData(instanceId: String): Boolean {
        val appDir = getAppDir(instanceId)
        val result = appDir.deleteRecursively()
        Timber.tag(TAG).i("Deleted data for $instanceId: $result")
        return result
    }

    /**
     * Clear cache for an app instance.
     */
    fun clearCache(instanceId: String): Boolean {
        val cacheDir = getCacheDir(instanceId)
        val result = cacheDir.deleteRecursively()
        cacheDir.mkdirs() // Recreate empty dir
        Timber.tag(TAG).d("Cleared cache for $instanceId")
        return result
    }

    /**
     * Get the total storage size used by an app instance (in bytes).
     */
    fun getAppStorageSize(instanceId: String): Long {
        return getAppDir(instanceId).walkTopDown().sumOf { it.length() }
    }

    /**
     * Get the total storage size used by all virtual apps (in bytes).
     */
    fun getTotalStorageSize(): Long {
        return virtualRoot.walkTopDown().sumOf { it.length() }
    }

    /**
     * Translate a guest app's data path to the sandboxed path.
     *
     * Guest sees: /data/data/{packageName}/files/...
     * Real path: /data/data/com.nextvm.app/virtual/data/{instanceId}/files/...
     */
    fun translatePath(
        originalPath: String,
        packageName: String,
        instanceId: String
    ): String {
        val guestDataPrefix = "/data/data/$packageName"
        val guestUserPrefix = "/data/user/0/$packageName"
        val sandboxPrefix = getAppDir(instanceId).absolutePath

        return when {
            originalPath.startsWith(guestDataPrefix) ->
                originalPath.replace(guestDataPrefix, sandboxPrefix)
            originalPath.startsWith(guestUserPrefix) ->
                originalPath.replace(guestUserPrefix, sandboxPrefix)
            else -> originalPath
        }
    }

    /**
     * Get the OBB directory for an app instance.
     * OBB files are large expansion files downloaded alongside an app.
     */
    fun getObbDir(instanceId: String): File {
        return File(getAppDir(instanceId), "obb").also { it.mkdirs() }
    }

    /**
     * Get the virtual external (shared) storage root for an app instance.
     * This replaces /sdcard or /storage/emulated/0 for the guest.
     *
     * Structure mirrors a real Android device:
     *   sdcard/
     *   ├── Alarms/
     *   ├── DCIM/
     *   ├── Documents/
     *   ├── Download/
     *   ├── Movies/
     *   ├── Music/
     *   ├── Notifications/
     *   ├── Pictures/
     *   ├── Ringtones/
     *   └── Android/
     *       ├── data/{pkg}/
     *       ├── obb/{pkg}/
     *       └── media/{pkg}/
     */
    fun getExternalStorageDir(instanceId: String): File {
        return File(getAppDir(instanceId), "sdcard").also { it.mkdirs() }
    }

    /**
     * Get a specific public directory inside the virtual external storage.
     * Equivalent to Environment.getExternalStoragePublicDirectory(type).
     *
     * @param type One of Environment.DIRECTORY_* constants (e.g., "DCIM", "Download", "Pictures")
     */
    fun getExternalStoragePublicDir(instanceId: String, type: String): File {
        return File(getExternalStorageDir(instanceId), type).also { it.mkdirs() }
    }

    /**
     * Get the app-specific directory inside virtual external storage.
     * Equivalent to /storage/emulated/0/Android/data/{pkg}/files/{type}
     */
    fun getExternalAppFilesDir(instanceId: String, packageName: String, type: String?): File {
        val base = File(getExternalStorageDir(instanceId), "Android/data/$packageName/files")
        base.mkdirs()
        return if (type != null) File(base, type).also { it.mkdirs() } else base
    }

    /**
     * Get the app-specific cache directory inside virtual external storage.
     * Equivalent to /storage/emulated/0/Android/data/{pkg}/cache
     */
    fun getExternalAppCacheDir(instanceId: String, packageName: String): File {
        return File(getExternalStorageDir(instanceId), "Android/data/$packageName/cache").also { it.mkdirs() }
    }

    /**
     * Get the app-specific media directory inside virtual external storage.
     * Equivalent to /storage/emulated/0/Android/media/{pkg}
     */
    fun getExternalMediaDir(instanceId: String, packageName: String): File {
        return File(getExternalStorageDir(instanceId), "Android/media/$packageName").also { it.mkdirs() }
    }

    /**
     * Get the app-specific OBB directory inside virtual external storage.
     * Equivalent to /storage/emulated/0/Android/obb/{pkg}
     */
    fun getExternalObbDir(instanceId: String, packageName: String): File {
        return File(getExternalStorageDir(instanceId), "Android/obb/$packageName").also { it.mkdirs() }
    }

    /**
     * Check if a path should be redirected to sandbox.
     */
    fun shouldRedirect(path: String, packageName: String): Boolean {
        return path.contains("/data/data/$packageName") ||
               path.contains("/data/user/0/$packageName")
    }

    /**
     * Check if a path is an external storage path that should be redirected.
     */
    fun isExternalStoragePath(path: String): Boolean {
        return path.startsWith("/sdcard/") ||
               path.startsWith("/sdcard") && path.length == 7 ||
               path.startsWith("/storage/emulated/0/") ||
               path.startsWith("/storage/emulated/0") && path.length == 19 ||
               path.startsWith("/mnt/sdcard/") ||
               path.startsWith("/storage/self/primary/")
    }

    /**
     * Translate an external storage path to the virtual sandbox path for a given instance.
     */
    fun translateExternalStoragePath(path: String, instanceId: String): String {
        val sdcardRoot = getExternalStorageDir(instanceId).absolutePath

        val prefixes = listOf(
            "/storage/emulated/0/",
            "/storage/emulated/0",
            "/sdcard/",
            "/sdcard",
            "/mnt/sdcard/",
            "/storage/self/primary/"
        )

        for (prefix in prefixes) {
            if (path.startsWith(prefix)) {
                val relative = path.removePrefix(prefix)
                return if (relative.isEmpty()) sdcardRoot else "$sdcardRoot/$relative"
            }
        }

        return path
    }
}

package com.nextvm.core.sandbox

import android.os.Environment
import com.nextvm.core.common.findField
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VirtualEnvironmentHook — Hooks android.os.Environment static methods to return
 * sandboxed external storage paths for guest apps.
 *
 * File explorer apps (and any app that accesses shared/external storage) use:
 *   - Environment.getExternalStorageDirectory()          -> /sdcard
 *   - Environment.getExternalStoragePublicDirectory(type) -> /sdcard/{type}
 *   - Environment.getExternalStorageState()               -> "mounted"
 *   - Environment.getDataDirectory()                      -> /data
 *   - Environment.getDownloadCacheDirectory()              -> /data/cache
 *
 * This hook replaces the internal static field that Environment uses to
 * derive these paths, redirecting them into the per-instance sandbox.
 *
 * HOW IT WORKS:
 *   Environment internally caches the "EXTERNAL_STORAGE" path in a static field.
 *   We replace that field's value with the virtual sdcard path so that ALL
 *   subsequent calls to Environment.getExternalStorageDirectory() return our path.
 */
@Singleton
class VirtualEnvironmentHook @Inject constructor(
    private val sandboxManager: SandboxManager
) {
    companion object {
        private const val TAG = "VirtEnvHook"
    }

    // Original values saved for restore
    private var originalExternalDir: File? = null
    private var hooked = false

    // Currently active instance — needed because Environment is static/global
    @Volatile
    private var activeInstanceId: String? = null
    @Volatile
    private var activePackageName: String? = null

    // Per-instance external storage roots
    private val instanceStorageRoots = ConcurrentHashMap<String, File>()

    /**
     * Register an instance's external storage root.
     * Call this during app installation so the path is ready before launch.
     */
    fun registerInstance(instanceId: String, packageName: String) {
        val sdcardDir = sandboxManager.getExternalStorageDir(instanceId)
        instanceStorageRoots[instanceId] = sdcardDir
        Timber.tag(TAG).d("Registered external storage for $instanceId: ${sdcardDir.absolutePath}")
    }

    /**
     * Unregister an instance on uninstall.
     */
    fun unregisterInstance(instanceId: String) {
        instanceStorageRoots.remove(instanceId)
    }

    /**
     * Activate the external storage redirection for a specific virtual app instance.
     * Call this just before launching a guest app.
     *
     * Since Environment is a static/JVM-global class, only ONE instance's paths
     * can be active at a time. In practice each guest runs in its own process (:pN),
     * so each process calls this independently.
     */
    fun activateForInstance(instanceId: String, packageName: String) {
        activeInstanceId = instanceId
        activePackageName = packageName

        val virtualSdcard = instanceStorageRoots[instanceId]
            ?: sandboxManager.getExternalStorageDir(instanceId)

        applyExternalStorageHook(virtualSdcard)

        // Hook Environment.isExternalStorageManager() to return true
        // so file explorers and apps checking MANAGE_EXTERNAL_STORAGE see it as granted
        hookStorageManagerCheck()

        // Hook Settings.canDrawOverlays() to return true
        hookOverlayPermissionCheck()

        // Hook Settings.System.canWrite() to return true
        hookWriteSettingsCheck()

        Timber.tag(TAG).i("Environment hooks active for $instanceId -> ${virtualSdcard.absolutePath}")
    }

    /**
     * Hook Environment's internal state to return our virtual sdcard path.
     */
    private fun applyExternalStorageHook(virtualSdcard: File) {
        try {
            // Strategy 1: Hook Environment.UserEnvironment which is used internally
            // Environment.getExternalStorageDirectory() delegates to
            // UserEnvironment.getExternalStorageDirectory()
            val envClass = Environment::class.java

            // The "sCurrentUser" field holds a UserEnvironment instance
            val userEnvField = findField(envClass, "sCurrentUser")
            if (userEnvField != null) {
                userEnvField.isAccessible = true
                val userEnv = userEnvField.get(null)
                if (userEnv != null) {
                    // UserEnvironment stores mExternalDirsForApp or similar
                    // The exact field name varies by Android version
                    val succeeded = hookUserEnvironment(userEnv, virtualSdcard)
                    if (succeeded) {
                        hooked = true
                        return
                    }
                }
            }

            // Strategy 2: Replace the static EXTERNAL_STORAGE environment variable path
            // that Environment reads via System.getenv("EXTERNAL_STORAGE")
            hookViaSystemEnv(virtualSdcard)
            hooked = true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to hook Environment")
        }
    }

    /**
     * Hook the UserEnvironment instance which backs Environment.getExternalStorageDirectory().
     *
     * On Android 8+ the field is typically "mExternalDirsForApp" (File[]) or
     * "mExternalStorageAndroidData" etc. We try multiple known field names.
     */
    private fun hookUserEnvironment(userEnv: Any, virtualSdcard: File): Boolean {
        val userEnvClass = userEnv::class.java
        val sdcardArray = arrayOf(virtualSdcard)

        // Try known field names across Android versions
        val fieldNames = listOf(
            "mExternalDirsForApp",
            "mExternalDirsForVold",
            "mExternalDirs",
            "mExternalStorageAndroidData"
        )

        for (name in fieldNames) {
            try {
                val field = findField(userEnvClass, name) ?: continue
                field.isAccessible = true
                val oldValue = field.get(userEnv)
                if (oldValue is Array<*> && oldValue.isArrayOf<File>()) {
                    if (originalExternalDir == null && oldValue.isNotEmpty()) {
                        originalExternalDir = oldValue[0] as? File
                    }
                    field.set(userEnv, sdcardArray)
                    Timber.tag(TAG).d("Hooked UserEnvironment.$name -> ${virtualSdcard.absolutePath}")
                    return true
                }
            } catch (_: Exception) {
                continue
            }
        }

        // Fallback: try to find any File[] field in the class
        for (field in userEnvClass.declaredFields) {
            try {
                field.isAccessible = true
                val value = field.get(userEnv)
                if (value is Array<*> && value.isArrayOf<File>() && value.isNotEmpty()) {
                    val first = value[0] as? File
                    if (first != null && first.absolutePath.contains("emulated")) {
                        if (originalExternalDir == null) originalExternalDir = first
                        field.set(userEnv, sdcardArray)
                        Timber.tag(TAG).d("Hooked UserEnvironment.${field.name} (discovered) -> ${virtualSdcard.absolutePath}")
                        return true
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }

        return false
    }

    /**
     * Fallback strategy: override EXTERNAL_STORAGE env variable
     * by hooking ProcessEnvironment / System.getenv().
     */
    @Suppress("PrivateApi")
    private fun hookViaSystemEnv(virtualSdcard: File) {
        try {
            // java.lang.ProcessEnvironment keeps an unmodifiable map.
            // We replace the backing map's entry for EXTERNAL_STORAGE.
            val peClass = Class.forName("java.lang.ProcessEnvironment")

            // Try "theEnvironment" (the mutable backing map)
            val envField = findField(peClass, "theEnvironment")
                ?: findField(peClass, "theUnmodifiableEnvironment")

            if (envField != null) {
                envField.isAccessible = true
                val envMap = envField.get(null)
                if (envMap is MutableMap<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val mutableEnv = envMap as MutableMap<String, String>
                    mutableEnv["EXTERNAL_STORAGE"] = virtualSdcard.absolutePath
                    mutableEnv["SECONDARY_STORAGE"] = ""
                    Timber.tag(TAG).d("Hooked EXTERNAL_STORAGE env var -> ${virtualSdcard.absolutePath}")
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Env var hook failed: ${e.message}")
        }
    }

    /**
     * Hook Environment.isExternalStorageManager() to always return true.
     *
     * Android 11+ (API 30) introduced MANAGE_EXTERNAL_STORAGE. File explorer apps
     * check this method to see if they have "All file access" permission.
     * Without this hook, the guest app sees false → opens Settings → navigates
     * to the REAL device's settings instead of the VM's virtual permission system.
     *
     * We hook the underlying AppOpsManager check that Environment.isExternalStorageManager()
     * delegates to. On Android 11+, it calls:
     *   AppOpsManager.checkOpNoThrow(OP_MANAGE_EXTERNAL_STORAGE, uid, packageName)
     *
     * Since we can't easily hook a static method without native hooks,
     * we instead hook the AppOpsManager singleton that Environment uses internally.
     */
    private fun hookStorageManagerCheck() {
        try {
            // Environment.isExternalStorageManager() on API 30+ is just:
            //   return Environment.isExternalStorageManager(Process.myUid(), context.getPackageName())
            // which internally does AppOpsManager checkOp.
            //
            // We use a simpler approach: hook the static sCurrentUser + ensure the
            // PackageManagerProxy and AppOps always return GRANTED.
            // But also try direct approach if available on this Android version:

            if (android.os.Build.VERSION.SDK_INT >= 30) {
                // On API 30+, try to find and set the cached result field if it exists
                val envClass = Environment::class.java

                // There's no static cached field for this in AOSP — the check goes through
                // AppOpsManager every time. Our AppOps proxy already returns ALLOWED,
                // so this should work. But some ROM variants cache the result, so try to
                // hook any cached boolean field named "*storageManager*" on the class.
                for (field in envClass.declaredFields) {
                    try {
                        if (field.type == Boolean::class.java || field.type == java.lang.Boolean::class.java) {
                            if (field.name.contains("storage", ignoreCase = true) ||
                                field.name.contains("manager", ignoreCase = true)) {
                                field.isAccessible = true
                                field.setBoolean(null, true)
                                Timber.tag(TAG).d("Set Environment.${field.name} = true")
                            }
                        }
                    } catch (_: Exception) { /* skip non-static or inaccessible fields */ }
                }
            }
            Timber.tag(TAG).d("Storage manager check hook applied")
        } catch (e: Exception) {
            Timber.tag(TAG).w("hookStorageManagerCheck failed: ${e.message}")
        }
    }

    /**
     * Hook Settings.canDrawOverlays() to always return true for the guest app.
     * Guest apps that request SYSTEM_ALERT_WINDOW check this before showing overlays.
     */
    private fun hookOverlayPermissionCheck() {
        // Settings.canDrawOverlays() also delegates to AppOpsManager.
        // Our AppOps proxy already returns ALLOWED for all ops,
        // so this should work out of the box. Log for debugging.
        Timber.tag(TAG).d("Overlay permission (SYSTEM_ALERT_WINDOW) auto-granted via AppOps proxy")
    }

    /**
     * Hook Settings.System.canWrite() to always return true for the guest app.
     */
    private fun hookWriteSettingsCheck() {
        // Settings.System.canWrite() also delegates to AppOpsManager.
        // Our AppOps proxy returns ALLOWED for all ops.
        Timber.tag(TAG).d("Write settings permission auto-granted via AppOps proxy")
    }

    /**
     * Restore original Environment paths.
     * Call when stopping a virtual app.
     */
    fun deactivate() {
        if (!hooked) return

        val original = originalExternalDir
        if (original != null) {
            try {
                applyExternalStorageHook(original)
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to restore Environment: ${e.message}")
            }
        }

        activeInstanceId = null
        activePackageName = null
        hooked = false
        Timber.tag(TAG).i("Environment hooks deactivated")
    }

    /**
     * Get the virtual sdcard root for a given instance.
     * Returns null if the instance isn't registered.
     */
    fun getStorageRoot(instanceId: String): File? = instanceStorageRoots[instanceId]

    /**
     * Check if Environment hooks are currently active.
     */
    fun isHooked(): Boolean = hooked

    /**
     * Get the currently active instance ID.
     */
    fun getActiveInstanceId(): String? = activeInstanceId
}

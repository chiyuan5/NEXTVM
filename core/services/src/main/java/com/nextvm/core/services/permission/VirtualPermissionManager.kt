package com.nextvm.core.services.permission

import android.Manifest
import android.content.pm.PackageManager
import com.nextvm.core.common.AndroidCompat
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VirtualPermissionManager — Complete permission system for guest apps.
 *
 * Mirrors:
 *   - PermissionManagerService.java in frameworks/base/services/core/java/com/android/server/pm/permission/
 *   - AppOpsService.java in frameworks/base/services/core/java/com/android/server/appop/
 *   - PermissionManagerServiceImpl.java
 *
 * Handles:
 *   - Runtime permission grant/deny tracking
 *   - Permission group management (CAMERA, LOCATION, CONTACTS, PHONE, STORAGE, etc.)
 *   - Special permissions (SYSTEM_ALERT_WINDOW, WRITE_SETTINGS)
 *   - AppOps-style operation tracking
 *   - Background location (Android 10+)
 *   - POST_NOTIFICATIONS (Android 13+)
 *   - Per-app permission overrides from VirtualApp.permissionOverrides
 */
@Singleton
class VirtualPermissionManager @Inject constructor() {

    companion object {
        private const val TAG = "VPermissionMgr"

        /** AppOps constants (mirrors AppOpsManager) */
        const val APP_OP_ALLOWED = 0
        const val APP_OP_IGNORED = 1
        const val APP_OP_ERRORED = 2
        const val APP_OP_DEFAULT = 3

        /** Install-time (normal) permissions — auto-granted */
        val INSTALL_PERMISSIONS = setOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_NETWORK_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.VIBRATE,
            Manifest.permission.SET_ALARM,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.REQUEST_INSTALL_PACKAGES,
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"
        )

        /** Dangerous (runtime) permissions that require user grant */
        val DANGEROUS_PERMISSIONS = setOf(
            // Camera
            Manifest.permission.CAMERA,
            // Location
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            // Contacts
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.GET_ACCOUNTS,
            // Phone
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.ADD_VOICEMAIL,
            Manifest.permission.USE_SIP,
            "android.permission.READ_PHONE_NUMBERS",
            // Calendar
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            // SMS
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_WAP_PUSH,
            Manifest.permission.RECEIVE_MMS,
            // Storage
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO",
            // Microphone
            Manifest.permission.RECORD_AUDIO,
            // Body Sensors
            Manifest.permission.BODY_SENSORS,
            // Bluetooth (Android 12+)
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.BLUETOOTH_CONNECT",
            // Notifications (Android 13+)
            "android.permission.POST_NOTIFICATIONS",
            // Nearby Devices (Android 13+)
            "android.permission.NEARBY_WIFI_DEVICES"
        )

        /** Special permissions requiring separate grant flow */
        val SPECIAL_PERMISSIONS = setOf(
            Manifest.permission.SYSTEM_ALERT_WINDOW,
            Manifest.permission.WRITE_SETTINGS,
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.SCHEDULE_EXACT_ALARM"
        )

        /** Permission groups — maps permission to its group */
        val PERMISSION_GROUPS = mapOf(
            Manifest.permission.CAMERA to "android.permission-group.CAMERA",
            Manifest.permission.ACCESS_FINE_LOCATION to "android.permission-group.LOCATION",
            Manifest.permission.ACCESS_COARSE_LOCATION to "android.permission-group.LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION" to "android.permission-group.LOCATION",
            Manifest.permission.READ_CONTACTS to "android.permission-group.CONTACTS",
            Manifest.permission.WRITE_CONTACTS to "android.permission-group.CONTACTS",
            Manifest.permission.GET_ACCOUNTS to "android.permission-group.CONTACTS",
            Manifest.permission.READ_PHONE_STATE to "android.permission-group.PHONE",
            Manifest.permission.CALL_PHONE to "android.permission-group.PHONE",
            Manifest.permission.READ_CALL_LOG to "android.permission-group.CALL_LOG",
            Manifest.permission.WRITE_CALL_LOG to "android.permission-group.CALL_LOG",
            "android.permission.READ_PHONE_NUMBERS" to "android.permission-group.PHONE",
            Manifest.permission.READ_CALENDAR to "android.permission-group.CALENDAR",
            Manifest.permission.WRITE_CALENDAR to "android.permission-group.CALENDAR",
            Manifest.permission.SEND_SMS to "android.permission-group.SMS",
            Manifest.permission.RECEIVE_SMS to "android.permission-group.SMS",
            Manifest.permission.READ_SMS to "android.permission-group.SMS",
            Manifest.permission.READ_EXTERNAL_STORAGE to "android.permission-group.STORAGE",
            Manifest.permission.WRITE_EXTERNAL_STORAGE to "android.permission-group.STORAGE",
            "android.permission.READ_MEDIA_IMAGES" to "android.permission-group.READ_MEDIA_VISUAL",
            "android.permission.READ_MEDIA_VIDEO" to "android.permission-group.READ_MEDIA_VISUAL",
            "android.permission.READ_MEDIA_AUDIO" to "android.permission-group.READ_MEDIA_AURAL",
            Manifest.permission.RECORD_AUDIO to "android.permission-group.MICROPHONE",
            Manifest.permission.BODY_SENSORS to "android.permission-group.SENSORS",
            "android.permission.POST_NOTIFICATIONS" to "android.permission-group.NOTIFICATIONS",
            "android.permission.BLUETOOTH_SCAN" to "android.permission-group.NEARBY_DEVICES",
            "android.permission.BLUETOOTH_ADVERTISE" to "android.permission-group.NEARBY_DEVICES",
            "android.permission.BLUETOOTH_CONNECT" to "android.permission-group.NEARBY_DEVICES",
            "android.permission.NEARBY_WIFI_DEVICES" to "android.permission-group.NEARBY_DEVICES"
        )
    }

    /**
     * Per-package permission state.
     */
    data class PermissionState(
        val packageName: String,
        val requestedPermissions: Set<String> = emptySet(),
        val grantedPermissions: MutableSet<String> = mutableSetOf(),
        val deniedPermissions: MutableSet<String> = mutableSetOf(),
        val permanentlyDenied: MutableSet<String> = mutableSetOf(),
        val appOps: MutableMap<Int, Int> = mutableMapOf(),
        val overrides: Map<String, Boolean> = emptyMap()
    )

    // packageName -> PermissionState
    private val permissionStates = ConcurrentHashMap<String, PermissionState>()

    /**
     * Initialize permissions for a newly installed virtual app.
     *
     * AUTO-GRANTS ALL permissions (install-time, dangerous, AND special).
     * Inside the VM, guest apps should never be blocked by permission checks.
     * The host app already holds the real permissions from the user.
     *
     * @param packageName Guest app package name
     * @param requestedPermissions Permissions from the APK manifest
     * @param overrides Per-permission overrides from VirtualApp.permissionOverrides
     */
    fun initializePermissions(
        packageName: String,
        requestedPermissions: List<String>,
        overrides: Map<String, Boolean> = emptyMap()
    ) {
        val state = PermissionState(
            packageName = packageName,
            requestedPermissions = requestedPermissions.toSet(),
            overrides = overrides
        )

        // Auto-grant ALL requested permissions (install-time, dangerous, AND special)
        for (perm in requestedPermissions) {
            state.grantedPermissions.add(perm)
        }

        // Also pre-grant common special permissions even if not explicitly requested,
        // so that checks like Environment.isExternalStorageManager() pass inside the VM
        val implicitGrants = listOf(
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            Manifest.permission.SYSTEM_ALERT_WINDOW,
            Manifest.permission.WRITE_SETTINGS,
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.SCHEDULE_EXACT_ALARM",
            "android.permission.POST_NOTIFICATIONS",
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO",
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            "android.permission.ACCESS_BACKGROUND_LOCATION"
        )
        for (perm in implicitGrants) {
            state.grantedPermissions.add(perm)
        }

        // Apply overrides (allow user to explicitly deny specific permissions if needed)
        for ((perm, granted) in overrides) {
            if (granted) {
                state.grantedPermissions.add(perm)
                state.deniedPermissions.remove(perm)
            } else {
                state.grantedPermissions.remove(perm)
                state.deniedPermissions.add(perm)
            }
        }

        permissionStates[packageName] = state

        Timber.tag(TAG).i("Permissions initialized for $packageName: " +
            "${state.grantedPermissions.size} ALL granted (auto-grant mode)")
    }

    /**
     * Check if a permission is granted.
     * In auto-grant mode, defaults to GRANTED for ALL permissions unless explicitly denied.
     *
     * @return PackageManager.PERMISSION_GRANTED or PackageManager.PERMISSION_DENIED
     */
    fun checkPermission(packageName: String, permission: String): Int {
        val state = permissionStates[packageName]
        if (state == null) {
            // No state = not registered yet, but still grant for virtual apps
            // This handles early checks before initializePermissions is called
            return PackageManager.PERMISSION_GRANTED
        }

        // Check overrides first (highest priority — allows explicit deny)
        val override = state.overrides[permission]
        if (override != null) {
            return if (override) PackageManager.PERMISSION_GRANTED
            else PackageManager.PERMISSION_DENIED
        }

        // Auto-grant mode: always return GRANTED unless explicitly denied by override
        return PackageManager.PERMISSION_GRANTED
    }

    /**
     * Grant a runtime permission to a virtual app.
     */
    fun grantPermission(packageName: String, permission: String): Boolean {
        val state = permissionStates[packageName] ?: run {
            Timber.tag(TAG).w("Cannot grant: no state for $packageName")
            return false
        }

        // Only grant if the app actually requested this permission
        if (permission !in state.requestedPermissions) {
            Timber.tag(TAG).w("App $packageName did not request $permission")
            return false
        }

        state.grantedPermissions.add(permission)
        state.deniedPermissions.remove(permission)
        state.permanentlyDenied.remove(permission)

        Timber.tag(TAG).i("Permission granted: $packageName → $permission")
        return true
    }

    /**
     * Revoke a runtime permission from a virtual app.
     */
    fun revokePermission(packageName: String, permission: String): Boolean {
        val state = permissionStates[packageName] ?: return false

        state.grantedPermissions.remove(permission)
        state.deniedPermissions.add(permission)

        Timber.tag(TAG).i("Permission revoked: $packageName → $permission")
        return true
    }

    /**
     * Mark a permission as permanently denied (user selected "Don't ask again").
     */
    fun permanentlyDenyPermission(packageName: String, permission: String): Boolean {
        val state = permissionStates[packageName] ?: return false

        state.grantedPermissions.remove(permission)
        state.deniedPermissions.add(permission)
        state.permanentlyDenied.add(permission)

        Timber.tag(TAG).d("Permission permanently denied: $packageName → $permission")
        return true
    }

    /**
     * Check if a permission has been permanently denied.
     */
    fun isPermanentlyDenied(packageName: String, permission: String): Boolean {
        val state = permissionStates[packageName] ?: return false
        return permission in state.permanentlyDenied
    }

    /**
     * Grant all requested dangerous permissions (e.g., for testing).
     */
    fun grantAllPermissions(packageName: String) {
        val state = permissionStates[packageName] ?: return

        for (perm in state.requestedPermissions) {
            state.grantedPermissions.add(perm)
        }
        state.deniedPermissions.clear()
        state.permanentlyDenied.clear()

        Timber.tag(TAG).i("All permissions granted for $packageName")
    }

    /**
     * Revoke all dangerous permissions (reset to install-time only).
     */
    fun revokeAllDangerousPermissions(packageName: String) {
        val state = permissionStates[packageName] ?: return

        val toRevoke = state.grantedPermissions.filter { it in DANGEROUS_PERMISSIONS }
        for (perm in toRevoke) {
            state.grantedPermissions.remove(perm)
            state.deniedPermissions.add(perm)
        }

        Timber.tag(TAG).i("All dangerous permissions revoked for $packageName")
    }

    // ---------- Permission Information ----------

    /**
     * Get the permission group for a permission.
     */
    fun getPermissionGroup(permission: String): String? {
        return PERMISSION_GROUPS[permission]
    }

    /**
     * Check if a permission is dangerous (requires runtime grant).
     */
    fun isPermissionDangerous(permission: String): Boolean {
        return permission in DANGEROUS_PERMISSIONS
    }

    /**
     * Check if a permission is a special permission.
     */
    fun isPermissionSpecial(permission: String): Boolean {
        return permission in SPECIAL_PERMISSIONS
    }

    /**
     * Get all granted permissions for a package.
     */
    fun getGrantedPermissions(packageName: String): Set<String> {
        return permissionStates[packageName]?.grantedPermissions?.toSet() ?: emptySet()
    }

    /**
     * Get all denied permissions for a package.
     */
    fun getDeniedPermissions(packageName: String): Set<String> {
        val state = permissionStates[packageName] ?: return emptySet()
        return state.requestedPermissions - state.grantedPermissions
    }

    /**
     * Get all requested permissions for a package.
     */
    fun getRequestedPermissions(packageName: String): Set<String> {
        return permissionStates[packageName]?.requestedPermissions ?: emptySet()
    }

    /**
     * Get ungrantd dangerous permissions that should be requested.
     */
    fun getUngrantedDangerousPermissions(packageName: String): List<String> {
        val state = permissionStates[packageName] ?: return emptyList()
        return state.requestedPermissions
            .filter { it in DANGEROUS_PERMISSIONS }
            .filter { it !in state.grantedPermissions }
            .filter { it !in state.permanentlyDenied }
    }

    // ---------- AppOps ----------

    /**
     * Check an AppOps operation.
     * In auto-grant mode, always returns ALLOWED for virtual apps.
     *
     * @param packageName Package to check
     * @param op Operation code (mirrors AppOpsManager.OP_* constants)
     * @return APP_OP_ALLOWED, APP_OP_IGNORED, APP_OP_ERRORED, or APP_OP_DEFAULT
     */
    fun checkAppOp(packageName: String, op: Int): Int {
        val state = permissionStates[packageName]
        if (state == null) return APP_OP_ALLOWED // Auto-grant for virtual apps

        // Check explicit overrides first
        val explicitMode = state.appOps[op]
        if (explicitMode != null) return explicitMode

        // Default: always allow in VM
        return APP_OP_ALLOWED
    }

    /**
     * Set an AppOps mode.
     */
    fun setAppOp(packageName: String, op: Int, mode: Int) {
        val state = permissionStates[packageName] ?: return
        state.appOps[op] = mode
        Timber.tag(TAG).d("AppOp set: $packageName, op=$op, mode=$mode")
    }

    /**
     * Note an AppOps operation (record that it occurred).
     * Always returns ALLOWED in auto-grant mode.
     */
    fun noteOp(packageName: String, op: Int): Int {
        // Always allow in the virtual environment
        return APP_OP_ALLOWED
    }

    // ---------- Version-Specific Checks ----------

    /**
     * Check if background location permission is needed and granted.
     * Required for Android 10+ when accessing location in background.
     */
    fun hasBackgroundLocationPermission(packageName: String): Boolean {
        if (!AndroidCompat.isAtLeastQ) return true // Not needed before Q

        return checkPermission(packageName, "android.permission.ACCESS_BACKGROUND_LOCATION") ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if notification permission is needed and granted.
     * Required for Android 13+ (API 33).
     */
    fun hasNotificationPermission(packageName: String): Boolean {
        if (!AndroidCompat.isAtLeastT) return true // Not needed before T

        return checkPermission(packageName, "android.permission.POST_NOTIFICATIONS") ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if the app has the requested Bluetooth permissions.
     * Required for Android 12+ (API 31).
     */
    fun hasBluetoothPermission(packageName: String): Boolean {
        if (!AndroidCompat.isAtLeastS) return true

        val scan = checkPermission(packageName, "android.permission.BLUETOOTH_SCAN")
        val connect = checkPermission(packageName, "android.permission.BLUETOOTH_CONNECT")
        return scan == PackageManager.PERMISSION_GRANTED ||
            connect == PackageManager.PERMISSION_GRANTED
    }

    // ---------- Bulk Operations ----------

    /**
     * Apply permission overrides from VirtualApp.permissionOverrides.
     */
    fun applyOverrides(packageName: String, overrides: Map<String, Boolean>) {
        val state = permissionStates[packageName] ?: return

        for ((perm, granted) in overrides) {
            if (granted) {
                state.grantedPermissions.add(perm)
                state.deniedPermissions.remove(perm)
            } else {
                state.grantedPermissions.remove(perm)
                state.deniedPermissions.add(perm)
            }
        }

        Timber.tag(TAG).d("Applied ${overrides.size} permission overrides for $packageName")
    }

    // ---------- Cleanup ----------

    /**
     * Remove all permission state for a package.
     */
    fun removePackage(packageName: String) {
        permissionStates.remove(packageName)
        Timber.tag(TAG).d("Permission state removed for $packageName")
    }

    /**
     * Clear all state.
     */
    fun clearAll() {
        permissionStates.clear()
        Timber.tag(TAG).i("All permission state cleared")
    }

    /**
     * Get a summary of permission state for debugging.
     */
    fun getPermissionSummary(packageName: String): String {
        val state = permissionStates[packageName]
            ?: return "No permission state for $packageName"

        return buildString {
            appendLine("Permission state for $packageName:")
            appendLine("  Requested: ${state.requestedPermissions.size}")
            appendLine("  Granted: ${state.grantedPermissions.size}")
            appendLine("  Denied: ${state.deniedPermissions.size}")
            appendLine("  Permanently denied: ${state.permanentlyDenied.size}")
            appendLine("  AppOps: ${state.appOps.size}")
            appendLine("  Overrides: ${state.overrides.size}")
        }
    }

    /**
     * Initialize the permission manager service with application context.
     */
    fun initialize(context: android.content.Context) {
        Timber.tag(TAG).d("VirtualPermissionManager initialized")
    }
}

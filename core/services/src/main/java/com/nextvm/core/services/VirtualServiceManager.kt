package com.nextvm.core.services

import android.content.Context
import com.nextvm.core.services.am.VirtualActivityManagerService
import com.nextvm.core.services.component.VirtualBroadcastManager
import com.nextvm.core.services.component.VirtualContentProviderManager
import com.nextvm.core.services.component.VirtualServiceLifecycleManager
import com.nextvm.core.services.gms.VirtualGmsManager
import com.nextvm.core.services.intent.DeepLinkResolver
import com.nextvm.core.services.network.VirtualNetworkManager
import com.nextvm.core.services.permission.VirtualPermissionManager
import com.nextvm.core.services.pm.VirtualPackageManagerService
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Virtual Service Manager — the central coordinator for all virtual OS services.
 *
 * Phase 2 architecture: Manages REAL service implementations that process
 * requests from guest apps, not empty proxy stubs.
 *
 * Services:
 *   → VirtualActivityManagerService  — Activity/process lifecycle
 *   → VirtualPackageManagerService   — Package registry & intent resolution
 *   → VirtualServiceLifecycleManager — Service create/bind/destroy lifecycle
 *   → VirtualBroadcastManager        — Broadcast registration & dispatch
 *   → VirtualContentProviderManager  — ContentProvider installation & routing
 *   → DeepLinkResolver               — Deep link & implicit intent resolution
 *   → VirtualPermissionManager       — Runtime permission management
 *   → VirtualNetworkManager          — Per-app network isolation
 *   → VirtualGmsManager              — Google Play Services integration
 */
@Singleton
class VirtualServiceManager @Inject constructor(
    val activityManager: VirtualActivityManagerService,
    val packageManager: VirtualPackageManagerService,
    val serviceLifecycleManager: VirtualServiceLifecycleManager,
    val broadcastManager: VirtualBroadcastManager,
    val contentProviderManager: VirtualContentProviderManager,
    val deepLinkResolver: DeepLinkResolver,
    val permissionManager: VirtualPermissionManager,
    val networkManager: VirtualNetworkManager,
    val gmsManager: VirtualGmsManager
) {
    companion object {
        private const val TAG = "VirtualServices"
    }

    private var initialized = false

    /**
     * Initialize all virtual services in the correct dependency order.
     *
     * Order matters — PackageManager must be first since other services
     * query it for package info. PermissionManager before ActivityManager
     * since activity launches check permissions.
     */
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        Timber.tag(TAG).i("Virtual Service Manager initializing...")

        // 1. PackageManager first — all services need package info
        try {
            packageManager.initialize(context)
            Timber.tag(TAG).d("  ✓ VirtualPackageManagerService: READY")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "  ✗ VirtualPackageManagerService: FAILED")
        }

        // 2. PermissionManager — needed by ActivityManager for launch checks
        try {
            permissionManager.initialize(context)
            Timber.tag(TAG).d("  ✓ VirtualPermissionManager: READY")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "  ✗ VirtualPermissionManager: FAILED")
        }

        // 3. ActivityManager — depends on PackageManager + PermissionManager
        try {
            activityManager.initialize(context)
            Timber.tag(TAG).d("  ✓ VirtualActivityManagerService: READY")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "  ✗ VirtualActivityManagerService: FAILED")
        }

        // 4. ServiceLifecycleManager
        try {
            serviceLifecycleManager.initialize(context)
            Timber.tag(TAG).d("  ✓ VirtualServiceLifecycleManager: READY")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "  ✗ VirtualServiceLifecycleManager: FAILED")
        }

        // 5. BroadcastManager
        try {
            broadcastManager.initialize(context)
            Timber.tag(TAG).d("  ✓ VirtualBroadcastManager: READY")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "  ✗ VirtualBroadcastManager: FAILED")
        }

        // 6. ContentProviderManager
        try {
            contentProviderManager.initialize(context)
            Timber.tag(TAG).d("  ✓ VirtualContentProviderManager: READY")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "  ✗ VirtualContentProviderManager: FAILED")
        }

        // 7. DeepLinkResolver — depends on PackageManager for intent filters
        try {
            deepLinkResolver.initialize(context)
            Timber.tag(TAG).d("  ✓ DeepLinkResolver: READY")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "  ✗ DeepLinkResolver: FAILED")
        }

        // 8. NetworkManager
        try {
            networkManager.initialize(context)
            Timber.tag(TAG).d("  ✓ VirtualNetworkManager: READY")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "  ✗ VirtualNetworkManager: FAILED")
        }

        // 9. Hybrid GMS Manager — last, initializes 7 sub-components
        try {
            gmsManager.initialize(context)
            Timber.tag(TAG).d("  ✓ Hybrid GMS Manager: READY (7 sub-components)")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "  ✗ Hybrid GMS Manager: FAILED")
        }

        Timber.tag(TAG).i("Virtual Service Manager initialized (9 services)")
    }

    fun isInitialized(): Boolean = initialized

    /**
     * Check if a package is managed by the virtual services.
     */
    fun isVirtualPackage(packageName: String): Boolean {
        return packageManager.isVirtualPackage(packageName)
    }

    /**
     * Shutdown all services cleanly.
     */
    fun shutdown() {
        Timber.tag(TAG).i("Shutting down virtual services...")
        try { gmsManager.shutdown() } catch (_: Exception) {}
        try { networkManager.shutdown() } catch (_: Exception) {}
        try { broadcastManager.shutdown() } catch (_: Exception) {}
        try { serviceLifecycleManager.shutdown() } catch (_: Exception) {}
        initialized = false
        Timber.tag(TAG).i("Virtual services shut down")
    }
}

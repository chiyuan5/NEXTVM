package com.nextvm.core.framework.parsing

import android.content.pm.SigningInfo

/**
 * Kotlin adaptation of Android 16's ApkLite.java.
 *
 * Source: android16-frameworks-base/core/java/android/content/pm/parsing/ApkLite.java (709 lines)
 *
 * Lightweight parsed details about a single APK file. Contains all metadata
 * extractable from AndroidManifest.xml without fully parsing the package.
 *
 * This is the REAL data model that Android's PackageManagerService uses internally
 * for lightweight APK inspection during install, scanning, and verification.
 */
data class ApkLiteInfo(
    /** Name of the package as used to identify it in the system */
    val packageName: String,

    /** Path where this APK file was found on disk */
    val path: String,

    /** Split name of this APK (null for base APK) */
    val splitName: String? = null,

    /** Name of the split APK that this APK depends on */
    val usesSplitName: String? = null,

    /** Name of the split APK that this APK is a configuration for */
    val configForSplit: String? = null,

    /** Types of required splits necessary for this package to run */
    val requiredSplitTypes: Set<String>? = null,

    /** Split types of this APK */
    val splitTypes: Set<String>? = null,

    /** Major version number of this package */
    val versionCodeMajor: Int = 0,

    /** Minor version number of this package */
    val versionCode: Int = 0,

    /** Revision code of this APK */
    val revisionCode: Int = 0,

    /**
     * Install location preference.
     * Maps to Android's PackageInfo.INSTALL_LOCATION_* constants:
     *   - INSTALL_LOCATION_AUTO (0)
     *   - INSTALL_LOCATION_INTERNAL_ONLY (1)
     *   - INSTALL_LOCATION_PREFER_EXTERNAL (2)
     *   - INSTALL_LOCATION_UNSPECIFIED (-1, internal AOSP constant)
     */
    val installLocation: Int = -1,

    /** Minimum SDK version required */
    val minSdkVersion: Int = 1,

    /** Target SDK version */
    val targetSdkVersion: Int = 0,

    /** Whether this APK is a 'feature' split */
    val isFeatureSplit: Boolean = false,

    /** Whether each split should load into its own Context */
    val isolatedSplits: Boolean = false,

    /** Whether this package requires at least one split to be present */
    val splitRequired: Boolean = false,

    /** Whether this is a core platform app */
    val isCoreApp: Boolean = false,

    /** Whether this app can be debugged */
    val isDebuggable: Boolean = false,

    /** Whether this app is profileable by Shell */
    val isProfileableByShell: Boolean = false,

    /** Whether this app needs to be loaded into other applications' processes */
    val isMultiArch: Boolean = false,

    /** Whether the 32-bit ABI should be used */
    val use32bitAbi: Boolean = false,

    /** Whether the installer should extract native libraries */
    val extractNativeLibs: Boolean = true,

    /** Whether this package uses embedded DEX (runs dex from APK directly) */
    val useEmbeddedDex: Boolean = false,

    /** Name of the overlay target package (null if not an overlay) */
    val targetPackageName: String? = null,

    /** Whether the overlay is static */
    val overlayIsStatic: Boolean = false,

    /** Priority of the overlay */
    val overlayPriority: Int = 0,

    /**
     * Rollback data policy. Maps to:
     *   - ROLLBACK_DATA_POLICY_RESTORE (0)
     *   - ROLLBACK_DATA_POLICY_WIPE (1)
     *   - ROLLBACK_DATA_POLICY_RETAIN (2)
     */
    val rollbackDataPolicy: Int = 0,

    /** Whether this app contains a DeviceAdminReceiver */
    val hasDeviceAdminReceiver: Boolean = false,

    /** Whether this APK is an SDK library */
    val isSdkLibrary: Boolean = false,

    /** Whether this APK is a static library */
    val isStaticLibrary: Boolean = false,

    /** List of SDK library names used by this package */
    val usesSdkLibraries: List<String> = emptyList(),

    /** SDK library versions used */
    val usesSdkLibrariesVersionsMajor: LongArray? = null,

    /** Static libraries used by this package */
    val usesStaticLibraries: List<String> = emptyList(),

    /** Static library versions used */
    val usesStaticLibrariesVersions: LongArray? = null,

    /** Libraries declared by this package */
    val declaredLibraryNames: List<String> = emptyList(),

    /** Whether the system app can be updated */
    val updatableSystem: Boolean = false,

    /** Page size compatibility (Android 16 new: 16KB page support) */
    val pageSizeCompat: Int = 0,

    /** Signing info (public API) */
    val signingInfo: SigningInfo? = null,

    /** Required system property name for overlay inclusion */
    val requiredSystemPropertyName: String? = null,

    /** Required system property value for overlay inclusion */
    val requiredSystemPropertyValue: String? = null
) {
    /**
     * Compose a long version code from major + minor, matching Android's
     * PackageInfo.composeLongVersionCode().
     */
    val longVersionCode: Long
        get() = (versionCodeMajor.toLong() shl 32) or (versionCode.toLong() and 0xFFFFFFFFL)
}

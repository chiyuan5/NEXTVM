package com.nextvm.core.framework.parsing

import android.content.pm.SigningInfo

/**
 * Kotlin adaptation of Android 16's PackageLite.java.
 *
 * Source: android16-frameworks-base/core/java/android/content/pm/parsing/PackageLite.java (541 lines)
 *
 * Lightweight parsed details about a complete package (base APK + optional split APKs).
 * This is what Android's PackageManagerService uses to track installed packages.
 *
 * In the real Android OS, PackageLite is constructed from one or more ApkLite objects
 * by ApkLiteParseUtils.parsePackageLite() / parseMonolithicPackageLite() / parseClusterPackageLite().
 */
data class PackageLiteInfo(
    /** Package name */
    val packageName: String,

    /** Path to the package directory or monolithic APK */
    val path: String,

    /** Path to the base APK */
    val baseApkPath: String,

    /** Paths to all split APKs (may be empty) */
    val splitApkPaths: List<String> = emptyList(),

    /** Split names corresponding to splitApkPaths */
    val splitNames: List<String> = emptyList(),

    /** Which splits are feature splits */
    val isFeatureSplits: BooleanArray = booleanArrayOf(),

    /** Which split each split depends on (uses-split) */
    val usesSplitNames: Array<String?> = emptyArray(),

    /** Which split each config split is for */
    val configForSplit: Array<String?> = emptyArray(),

    /** Revision codes for each split */
    val splitRevisionCodes: IntArray = intArrayOf(),

    /** Version code (combined major + minor) */
    val versionCode: Int = 0,

    /** Major version code */
    val versionCodeMajor: Int = 0,

    /** Target SDK version */
    val targetSdk: Int = 0,

    /** Install location preference (-1 = unspecified, AOSP internal constant) */
    val installLocation: Int = -1,

    /** Whether splits are isolated */
    val isolatedSplits: Boolean = false,

    /** Whether at least one split is required */
    val splitRequired: Boolean = false,

    /** Whether this is a core app */
    val isCoreApp: Boolean = false,

    /** Whether the app is debuggable */
    val isDebuggable: Boolean = false,

    /** Whether the app is multi-arch */
    val isMultiArch: Boolean = false,

    /** Whether 32-bit ABI should be preferred */
    val use32bitAbi: Boolean = false,

    /** Whether to extract native libs */
    val extractNativeLibs: Boolean = true,

    /** Whether to use embedded DEX */
    val useEmbeddedDex: Boolean = false,

    /** Page size compatibility (Android 16) */
    val pageSizeCompat: Int = 0,

    /** Signing info */
    val signingInfo: SigningInfo? = null,

    /** Required split types per split */
    val requiredSplitTypes: Array<Set<String>?>? = null,

    /** Split types per split */
    val splitTypes: Array<Set<String>?>? = null,

    /** All declared library names */
    val declaredLibraryNames: List<String> = emptyList()
) {
    /** Compose long version code */
    val longVersionCode: Long
        get() = (versionCodeMajor.toLong() shl 32) or (versionCode.toLong() and 0xFFFFFFFFL)

    /**
     * Get all APK paths (base + splits), mirroring Android's PackageLite.getAllApkPaths().
     */
    fun getAllApkPaths(): List<String> {
        return buildList {
            add(baseApkPath)
            addAll(splitApkPaths)
        }
    }

    companion object {
        /**
         * Construct PackageLiteInfo from a base ApkLiteInfo (monolithic APK, no splits).
         * Mirrors the real PackageLite constructor.
         */
        fun fromBaseApk(
            packagePath: String,
            baseApk: ApkLiteInfo
        ): PackageLiteInfo {
            return PackageLiteInfo(
                packageName = baseApk.packageName,
                path = packagePath,
                baseApkPath = baseApk.path,
                versionCode = baseApk.versionCode,
                versionCodeMajor = baseApk.versionCodeMajor,
                targetSdk = baseApk.targetSdkVersion,
                installLocation = baseApk.installLocation,
                isolatedSplits = baseApk.isolatedSplits,
                splitRequired = baseApk.splitRequired,
                isCoreApp = baseApk.isCoreApp,
                isDebuggable = baseApk.isDebuggable,
                isMultiArch = baseApk.isMultiArch,
                use32bitAbi = baseApk.use32bitAbi,
                extractNativeLibs = baseApk.extractNativeLibs,
                useEmbeddedDex = baseApk.useEmbeddedDex,
                pageSizeCompat = baseApk.pageSizeCompat,
                signingInfo = baseApk.signingInfo,
                declaredLibraryNames = baseApk.declaredLibraryNames
            )
        }

        /**
         * Construct PackageLiteInfo from a base APK + split APKs (cluster install).
         * Mirrors ApkLiteParseUtils.composePackageLiteFromApks().
         */
        fun fromCluster(
            packagePath: String,
            baseApk: ApkLiteInfo,
            splitApks: Map<String, ApkLiteInfo>
        ): PackageLiteInfo {
            val sortedSplitNames = splitApks.keys.sorted()
            val splitPaths = sortedSplitNames.map { splitApks[it]!!.path }
            val isFeature = BooleanArray(sortedSplitNames.size) {
                splitApks[sortedSplitNames[it]]!!.isFeatureSplit
            }
            val usesSplit = Array<String?>(sortedSplitNames.size) {
                splitApks[sortedSplitNames[it]]!!.usesSplitName
            }
            val configFor = Array<String?>(sortedSplitNames.size) {
                splitApks[sortedSplitNames[it]]!!.configForSplit
            }
            val revisionCodes = IntArray(sortedSplitNames.size) {
                splitApks[sortedSplitNames[it]]!!.revisionCode
            }
            val reqSplitTypes = Array<Set<String>?>(sortedSplitNames.size) {
                splitApks[sortedSplitNames[it]]!!.requiredSplitTypes
            }
            val splitTypesArr = Array<Set<String>?>(sortedSplitNames.size) {
                splitApks[sortedSplitNames[it]]!!.splitTypes
            }

            return PackageLiteInfo(
                packageName = baseApk.packageName,
                path = packagePath,
                baseApkPath = baseApk.path,
                splitApkPaths = splitPaths,
                splitNames = sortedSplitNames,
                isFeatureSplits = isFeature,
                usesSplitNames = usesSplit,
                configForSplit = configFor,
                splitRevisionCodes = revisionCodes,
                versionCode = baseApk.versionCode,
                versionCodeMajor = baseApk.versionCodeMajor,
                targetSdk = baseApk.targetSdkVersion,
                installLocation = baseApk.installLocation,
                isolatedSplits = baseApk.isolatedSplits,
                splitRequired = baseApk.splitRequired,
                isCoreApp = baseApk.isCoreApp,
                isDebuggable = baseApk.isDebuggable,
                isMultiArch = baseApk.isMultiArch,
                use32bitAbi = baseApk.use32bitAbi,
                extractNativeLibs = baseApk.extractNativeLibs,
                useEmbeddedDex = baseApk.useEmbeddedDex,
                pageSizeCompat = baseApk.pageSizeCompat,
                signingInfo = baseApk.signingInfo,
                requiredSplitTypes = reqSplitTypes,
                splitTypes = splitTypesArr,
                declaredLibraryNames = baseApk.declaredLibraryNames
            )
        }
    }
}

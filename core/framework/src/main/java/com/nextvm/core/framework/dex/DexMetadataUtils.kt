package com.nextvm.core.framework.dex

import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile

/**
 * DEX metadata helper ported from Android 16's DexMetadataHelper.java.
 *
 * Source: android16-frameworks-base/core/java/android/content/pm/dex/DexMetadataHelper.java
 *         (317 lines)
 *
 * Handles .dm (dex metadata) files — companion files to APKs containing
 * pre-compiled optimization profiles. The real Android OS uses these to
 * speed up app startup by pre-loading DEX optimization data.
 *
 * NEXTVM uses this to:
 *   1. Detect if guest APKs have .dm files
 *   2. Copy .dm files alongside APKs into virtual storage
 *   3. Validate .dm file integrity
 */
object DexMetadataUtils {
    private const val TAG = "DexMetadataUtils"
    private const val DEX_METADATA_FILE_EXTENSION = ".dm"
    private const val APK_FILE_EXTENSION = ".apk"

    /**
     * Check if a file is a dex metadata file.
     * Port of DexMetadataHelper.isDexMetadataFile().
     */
    fun isDexMetadataFile(file: File): Boolean {
        return file.name.endsWith(DEX_METADATA_FILE_EXTENSION)
    }

    /**
     * Build the path to the .dm file for a given APK path.
     * Port of DexMetadataHelper.buildDexMetadataPathForApk().
     *
     * Example: "/path/to/app.apk" → "/path/to/app.dm"
     */
    fun buildDexMetadataPathForApk(apkPath: String): String {
        return apkPath.removeSuffix(APK_FILE_EXTENSION) + DEX_METADATA_FILE_EXTENSION
    }

    /**
     * Find the .dm file for a given APK file, if it exists.
     * Port of DexMetadataHelper.findDexMetadataForFile().
     */
    fun findDexMetadataForFile(apkFile: File): File? {
        val dmPath = buildDexMetadataPathForApk(apkFile.absolutePath)
        val dmFile = File(dmPath)
        return if (dmFile.exists()) dmFile else null
    }

    /**
     * Build mapping of APK paths to their .dm file paths.
     * Port of DexMetadataHelper.buildPackageApkToDexMetadataMap().
     *
     * @param codePaths List of APK paths (base + splits)
     * @return Map of APK path → .dm path (only entries where .dm exists)
     */
    fun buildApkToDexMetadataMap(codePaths: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (codePath in codePaths) {
            val dmPath = buildDexMetadataPathForApk(codePath)
            if (File(dmPath).exists()) {
                result[codePath] = dmPath
            }
        }
        return result
    }

    /**
     * Get the total size of all .dm files for a package.
     * Port of DexMetadataHelper.getPackageDexMetadataSize().
     */
    fun getPackageDexMetadataSize(codePaths: List<String>): Long {
        var size = 0L
        for (codePath in codePaths) {
            val dmFile = File(buildDexMetadataPathForApk(codePath))
            if (dmFile.exists()) {
                size += dmFile.length()
            }
        }
        return size
    }

    /**
     * Validate a .dm file's integrity.
     * Simplified port of DexMetadataHelper.validateDexMetadataFile().
     */
    fun validateDexMetadataFile(
        dmPath: String,
        packageName: String,
        versionCode: Long
    ): ParseValidationResult {
        val dmFile = File(dmPath)
        if (!dmFile.exists()) {
            return ParseValidationResult(false, "DM file not found: $dmPath")
        }

        return try {
            ZipFile(dmFile).use { zip ->
                val manifestEntry = zip.getEntry("manifest.json")
                if (manifestEntry != null) {
                    zip.getInputStream(manifestEntry).use { it.readBytes() }
                }
                ParseValidationResult(true)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Invalid DM file: $dmPath")
            ParseValidationResult(false, "Invalid DM file: ${e.message}")
        }
    }

    data class ParseValidationResult(
        val isValid: Boolean,
        val errorMessage: String? = null
    )
}

/**
 * Direct adaptation of Android 16's PackageOptimizationInfo.java.
 *
 * Source: android16-frameworks-base/core/java/android/content/pm/dex/PackageOptimizationInfo.java
 *         (50 lines — complete file)
 *
 * Tracks DEX compilation state of a virtual app.
 */
data class PackageOptimizationInfo(
    /** Compilation filter used (speed, quicken, verify, etc.) */
    val compilationFilter: Int = -1,
    /** Reason for compilation (install, bg-dexopt, boot, etc.) */
    val compilationReason: Int = -1
) {
    companion object {
        fun createWithNoInfo() = PackageOptimizationInfo(-1, -1)
    }
}

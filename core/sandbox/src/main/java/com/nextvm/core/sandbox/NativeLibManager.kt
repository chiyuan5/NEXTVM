package com.nextvm.core.sandbox

import android.os.Build
import timber.log.Timber
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * NativeLibManager — Comprehensive native library extraction for ALL guest apps.
 *
 * Handles three cases:
 * 1. Standard extraction: .so files extracted from APK to lib dir
 * 2. extractNativeLibs=false: .so stays page-aligned in APK, loaded directly
 * 3. Split APK: native libs may be in config.arm64_v8a.apk or similar splits
 *
 * Call [extractNativeLibs] during installApp() to process all APK paths.
 */
object NativeLibManager {

    private const val TAG = "NativeLibMgr"

    /** ABI priority: device native first, then common fallbacks */
    val ABI_ORDER: List<String> by lazy {
        buildList {
            Build.SUPPORTED_ABIS.forEach { add(it) }
            if (!contains("arm64-v8a")) add("arm64-v8a")
            if (!contains("armeabi-v7a")) add("armeabi-v7a")
            if (!contains("armeabi")) add("armeabi")
        }.distinct()
    }

    /**
     * Main entry point — call during installApp().
     * Handles all 3 cases: standard APK, extractNativeLibs=false, split APKs.
     *
     * @param apkPaths Main APK + all split APK paths
     * @param instanceId Unique virtual app instance ID
     * @param dataRoot Root data directory (e.g., virtualRoot/data)
     * @return [NativeLibResult] with paths and extraction stats
     */
    fun extractNativeLibs(
        apkPaths: List<String>,
        instanceId: String,
        dataRoot: File
    ): NativeLibResult {
        val libDir = File(dataRoot, "$instanceId/lib").also { it.mkdirs() }

        var totalExtracted = 0
        var selectedAbi = ""

        for (apkPath in apkPaths) {
            val result = extractFromApk(apkPath, libDir)
            totalExtracted += result.count
            if (selectedAbi.isEmpty() && result.abi.isNotEmpty()) {
                selectedAbi = result.abi
            }
        }

        // If no ABI detected from libs, use device primary
        if (selectedAbi.isEmpty()) {
            selectedAbi = Build.SUPPORTED_ABIS[0]
        }

        Timber.tag(TAG).i("Extracted $totalExtracted .so files for $instanceId (abi=$selectedAbi)")

        val libPaths = buildLibraryPath(libDir, apkPaths, selectedAbi)

        return NativeLibResult(
            libDir = libDir.absolutePath,
            librarySearchPath = libPaths,
            selectedAbi = selectedAbi,
            extractedCount = totalExtracted
        )
    }

    private fun extractFromApk(apkPath: String, libDir: File): SingleApkResult {
        if (!File(apkPath).exists()) return SingleApkResult(0, "")

        return try {
            ZipFile(apkPath).use { zip ->
                // Step 1: Find best ABI available in this APK
                val bestAbi = findBestAbi(zip) ?: return SingleApkResult(0, "")

                // Step 2: Check if .so entries are page-aligned (extractNativeLibs=false)
                val pageAligned = isPageAligned(zip, bestAbi)

                val entries = zip.entries().asSequence()
                    .filter { entry ->
                        entry.name.startsWith("lib/$bestAbi/") &&
                        entry.name.endsWith(".so") &&
                        !entry.isDirectory
                    }
                    .toList()

                var count = 0

                for (entry in entries) {
                    val soName = entry.name.substringAfterLast("/")
                    val destFile = File(libDir, soName)

                    // Skip if already extracted with same size
                    if (destFile.exists() && destFile.length() == entry.size) {
                        Timber.tag(TAG).d("Skip existing: $soName")
                        count++
                        continue
                    }

                    if (!pageAligned) {
                        // Case 1: Standard extraction — copy .so to lib dir
                        try {
                            zip.getInputStream(entry).use { input ->
                                destFile.outputStream().buffered().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            destFile.setReadable(true, false)
                            destFile.setExecutable(true, false)
                            count++
                            Timber.tag(TAG).d("Extracted: $soName (${destFile.length()} bytes)")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to extract $soName")
                        }
                    } else {
                        // Case 2: extractNativeLibs=false — .so is page-aligned in APK.
                        // The linker can load directly from APK via zip path syntax.
                        // We still extract as a fallback (some loaders don't support zip paths).
                        try {
                            zip.getInputStream(entry).use { input ->
                                destFile.outputStream().buffered().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            destFile.setReadable(true, false)
                            destFile.setExecutable(true, false)
                            count++
                            Timber.tag(TAG).d("Extracted (page-aligned fallback): $soName (${destFile.length()} bytes)")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to extract page-aligned $soName")
                        }
                    }
                }

                SingleApkResult(count, bestAbi)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to process APK $apkPath")
            SingleApkResult(0, "")
        }
    }

    private fun findBestAbi(zip: ZipFile): String? {
        val availableAbis = mutableSetOf<String>()

        zip.entries().asSequence()
            .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
            .forEach { entry ->
                val parts = entry.name.split("/")
                if (parts.size >= 3) availableAbis.add(parts[1])
            }

        if (availableAbis.isEmpty()) return null

        Timber.tag(TAG).d("APK ABIs available: $availableAbis")

        return ABI_ORDER.firstOrNull { it in availableAbis }
    }

    /**
     * Check if .so entries are page-aligned (STORED method), indicating
     * extractNativeLibs=false in the manifest.
     */
    private fun isPageAligned(zip: ZipFile, abi: String): Boolean {
        val soEntry = zip.entries().asSequence()
            .firstOrNull { it.name.startsWith("lib/$abi/") && it.name.endsWith(".so") }
            ?: return false

        // Page-aligned entries use STORED method (no compression)
        if (soEntry.method == ZipEntry.STORED) {
            Timber.tag(TAG).d("Detected page-aligned .so (STORED) — APK embedded mode")
            return true
        }
        return false
    }

    /**
     * Build the full library search path string for ClassLoader and linker namespace.
     *
     * Format: "dir1:dir2:apk!/lib/abi:..."
     * - Extracted lib dir first (highest priority)
     * - APK zip paths for direct APK loading (fallback / extractNativeLibs=false)
     */
    private fun buildLibraryPath(
        libDir: File,
        apkPaths: List<String>,
        abi: String
    ): String {
        val paths = mutableListOf<String>()

        // 1. Virtual extracted lib dir (highest priority)
        paths.add(libDir.absolutePath)

        // 2. APK zip paths for linker direct APK load
        for (apkPath in apkPaths) {
            if (File(apkPath).exists()) {
                paths.add("$apkPath!/lib/$abi/")
            }
        }

        return paths.joinToString(":")
    }

    /**
     * Re-extract native libs for an already-installed app if lib dir is empty.
     * Called during loadInstalledApps() to fix previously broken installs.
     */
    fun reExtractIfMissing(
        apkPath: String,
        splitApkPaths: List<String>,
        instanceId: String,
        dataRoot: File
    ): NativeLibResult? {
        val libDir = File(dataRoot, "$instanceId/lib")
        val hasLibs = libDir.exists() &&
            libDir.listFiles()?.any { it.name.endsWith(".so") } == true

        if (hasLibs) return null // Already has libs, no action needed

        val apkFile = File(apkPath)
        if (!apkFile.exists()) return null

        Timber.tag(TAG).i("Re-extracting libs for $instanceId")

        val allPaths = buildList {
            add(apkPath)
            addAll(splitApkPaths.filter { File(it).exists() })
        }

        val result = extractNativeLibs(allPaths, instanceId, dataRoot)
        Timber.tag(TAG).i("Re-extraction complete: ${result.extractedCount} files")
        return result
    }

    data class NativeLibResult(
        val libDir: String,
        val librarySearchPath: String,
        val selectedAbi: String,
        val extractedCount: Int
    )

    private data class SingleApkResult(val count: Int, val abi: String)
}

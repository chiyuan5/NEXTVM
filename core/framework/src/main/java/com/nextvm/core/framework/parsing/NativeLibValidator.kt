package com.nextvm.core.framework.parsing

/**
 * Native library path validation ported from Android 16's ApkParsing.cpp.
 *
 * Source: android16-frameworks-base/libs/androidfw/ApkParsing.cpp (107 lines)
 *   - ValidLibraryPathLastSlash() — validates lib/{abi}/filename.so paths
 *   - isFilenameSafe() — checks each character for safety
 *
 * The real Android OS uses this C++ code when extracting native libraries
 * from APK files during installation. We port the same validation logic
 * to Kotlin for NEXTVM's library extraction.
 */
object NativeLibValidator {

    /** Valid ABI directory names from Android's supported ABIs */
    private val VALID_ABIS = setOf(
        "armeabi-v7a", "arm64-v8a",
        "x86", "x86_64",
        "mips", "mips64",
        "riscv64"
    )

    /**
     * Check if a filename contains only safe characters.
     * Port of ApkParsing.cpp isFilenameSafe().
     *
     * Safe characters: [A-Za-z0-9_.+-]
     */
    fun isFilenameSafe(name: String): Boolean {
        if (name.isEmpty()) return false
        for (c in name) {
            if (c in 'A'..'Z') continue
            if (c in 'a'..'z') continue
            if (c in '0'..'9') continue
            if (c == '_' || c == '-' || c == '.' || c == '+') continue
            return false
        }
        return true
    }

    /**
     * Validate a library path inside an APK (e.g., "lib/arm64-v8a/libfoo.so").
     * Port of ApkParsing.cpp ValidLibraryPathLastSlash().
     *
     * Rules:
     *   - Must start with "lib/"
     *   - ABI directory must be a known ABI
     *   - Filename must contain only safe characters
     *   - Must end with ".so"
     */
    fun validateLibraryPath(path: String): Boolean {
        if (!path.startsWith("lib/")) return false

        val parts = path.split("/")
        if (parts.size != 3) return false

        val abi = parts[1]
        val filename = parts[2]

        if (!isValidAbiName(abi)) return false
        if (!isFilenameSafe(filename)) return false
        if (!filename.endsWith(".so")) return false

        return true
    }

    /**
     * Check if the given ABI name is valid.
     */
    fun isValidAbiName(abi: String): Boolean {
        return abi in VALID_ABIS
    }

    /**
     * Get the primary ABI for extraction.
     */
    fun getPrimaryAbi(): String {
        return android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
    }

    /**
     * Get the fallback (32-bit) ABI.
     */
    fun getFallbackAbi(): String? {
        val abis = android.os.Build.SUPPORTED_ABIS
        return when {
            abis.contains("arm64-v8a") && abis.contains("armeabi-v7a") -> "armeabi-v7a"
            abis.contains("x86_64") && abis.contains("x86") -> "x86"
            else -> null
        }
    }
}

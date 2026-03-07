package com.nextvm.core.framework.parsing

/**
 * Package name validation logic ported directly from Android 16's
 * FrameworkParsingPackageUtils.validateName().
 *
 * Source: android16-frameworks-base/core/java/android/content/pm/parsing/
 *         FrameworkParsingPackageUtils.java (lines 73-105)
 *
 * This is the EXACT validation the real Android OS performs when
 * parsing package names from AndroidManifest.xml.
 */
object PackageNameValidator {
    /**
     * Maximum file name size for packages.
     * From FrameworkParsingPackageUtils: "Limits size to 223 and reserves 32 for the OS"
     */
    private const val MAX_FILE_NAME_SIZE = 223

    /**
     * Validate a package/component name using Android 16's rules.
     *
     * @param name The name to validate
     * @param requireSeparator true if the name must contain at least one '.' separator
     * @param requireFilename true to apply filename validation and length limits
     * @return null if valid, error message string if invalid
     */
    fun validateName(
        name: String,
        requireSeparator: Boolean,
        requireFilename: Boolean = false
    ): String? {
        val n = name.length
        var hasSep = false
        var front = true

        for (i in 0 until n) {
            val c = name[i]
            if (c in 'a'..'z' || c in 'A'..'Z') {
                front = false
                continue
            }
            if (!front) {
                if (c in '0'..'9' || c == '_') {
                    continue
                }
            }
            if (c == '.') {
                hasSep = true
                front = true
                continue
            }
            return "bad character '$c'"
        }

        if (requireFilename) {
            if (n > MAX_FILE_NAME_SIZE) {
                return "the length of the name is greater than $MAX_FILE_NAME_SIZE"
            }
            if (name.contains("..") || name.contains("/") || name.contains("\\")) {
                return "Invalid filename"
            }
        }

        return if (hasSep || !requireSeparator) null
        else "must have at least one '.' separator"
    }

    /**
     * Validate and return ParseResult.
     */
    fun validate(
        name: String,
        requireSeparator: Boolean,
        requireFilename: Boolean = false
    ): ParseResult<Unit> {
        val error = validateName(name, requireSeparator, requireFilename)
        return if (error == null) ParseResult.success(Unit)
        else ParseResult.error(error)
    }
}

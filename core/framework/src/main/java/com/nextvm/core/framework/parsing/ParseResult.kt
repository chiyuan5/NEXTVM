package com.nextvm.core.framework.parsing

/**
 * Kotlin adaptation of Android 16's ParseInput/ParseResult pattern.
 *
 * Source: android16-frameworks-base/core/java/android/content/pm/parsing/result/
 *   - ParseInput.java
 *   - ParseResult.java
 *
 * The original Android implementation reuses a single thread-local object for
 * zero-allocation parsing. We simplify to a sealed class since NEXTVM doesn't
 * need that level of optimization.
 */
sealed class ParseResult<out T> {
    abstract val isSuccess: Boolean
    abstract val isError: Boolean

    data class Success<T>(val result: T) : ParseResult<T>() {
        override val isSuccess = true
        override val isError = false
    }

    data class Error(
        val errorCode: Int,
        val errorMessage: String?,
        val exception: Exception? = null
    ) : ParseResult<Nothing>() {
        override val isSuccess = false
        override val isError = true
    }

    companion object {
        fun <T> success(result: T): ParseResult<T> = Success(result)

        @Suppress("UNCHECKED_CAST")
        fun <T> error(errorCode: Int, errorMessage: String? = null): ParseResult<T> =
            Error(errorCode, errorMessage) as ParseResult<T>

        @Suppress("UNCHECKED_CAST")
        fun <T> error(errorMessage: String): ParseResult<T> =
            Error(-1, errorMessage) as ParseResult<T>

        @Suppress("UNCHECKED_CAST")
        fun <T> error(other: ParseResult<*>): ParseResult<T> {
            val err = other as Error
            return Error(err.errorCode, err.errorMessage, err.exception) as ParseResult<T>
        }

        // Android PackageManager error codes used by ApkLiteParseUtils
        const val INSTALL_PARSE_FAILED_NOT_APK = -100
        const val INSTALL_PARSE_FAILED_BAD_MANIFEST = -101
        const val INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME = -102
        const val INSTALL_PARSE_FAILED_MANIFEST_MALFORMED = -108
        const val INSTALL_FAILED_OLDER_SDK = -12
        const val INSTALL_FAILED_NEWER_SDK = -14
        const val INSTALL_FAILED_BAD_DEX_METADATA = -129
    }
}

/**
 * Extension to unwrap ParseResult or throw.
 */
fun <T> ParseResult<T>.getOrThrow(): T = when (this) {
    is ParseResult.Success -> result
    is ParseResult.Error -> throw IllegalStateException(
        "Parse error ($errorCode): $errorMessage", exception
    )
}

fun <T> ParseResult<T>.getOrNull(): T? = when (this) {
    is ParseResult.Success -> result
    is ParseResult.Error -> null
}

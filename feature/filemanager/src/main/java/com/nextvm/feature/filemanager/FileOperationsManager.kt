package com.nextvm.feature.filemanager

import com.nextvm.core.model.FileClipboard
import com.nextvm.core.model.FileItem
import com.nextvm.core.model.FileSortMode
import com.nextvm.core.model.StorageInfo
import com.nextvm.core.model.VmResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.URLConnection
import javax.inject.Inject

class FileOperationsManager @Inject constructor() {

    suspend fun listFiles(
        directory: File,
        sortMode: FileSortMode,
        showHidden: Boolean
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val files = directory.listFiles() ?: return@withContext emptyList()
        val items = files
            .filter { showHidden || !it.isHidden }
            .map { it.toFileItem() }
        sortItems(items, sortMode)
    }

    suspend fun createDirectory(parent: File, name: String): VmResult<FileItem> =
        withContext(Dispatchers.IO) {
            try {
                val trimmed = name.trim()
                if (trimmed.isEmpty()) {
                    return@withContext VmResult.Error("Folder name cannot be empty")
                }
                if (trimmed.contains('/') || trimmed.contains('\\')) {
                    return@withContext VmResult.Error("Folder name cannot contain path separators")
                }
                val newDir = File(parent, trimmed)
                if (newDir.exists()) {
                    return@withContext VmResult.Error("'$trimmed' already exists")
                }
                if (newDir.mkdirs()) {
                    VmResult.Success(newDir.toFileItem())
                } else {
                    VmResult.Error("Failed to create folder")
                }
            } catch (e: Exception) {
                Timber.tag("FileOps").e(e, "createDirectory failed")
                VmResult.Error("Failed to create folder: ${e.message}", e)
            }
        }

    suspend fun createFile(parent: File, name: String): VmResult<FileItem> =
        withContext(Dispatchers.IO) {
            try {
                val trimmed = name.trim()
                if (trimmed.isEmpty()) {
                    return@withContext VmResult.Error("File name cannot be empty")
                }
                if (trimmed.contains('/') || trimmed.contains('\\')) {
                    return@withContext VmResult.Error("File name cannot contain path separators")
                }
                val newFile = File(parent, trimmed)
                if (newFile.exists()) {
                    return@withContext VmResult.Error("'$trimmed' already exists")
                }
                if (newFile.createNewFile()) {
                    VmResult.Success(newFile.toFileItem())
                } else {
                    VmResult.Error("Failed to create file")
                }
            } catch (e: Exception) {
                Timber.tag("FileOps").e(e, "createFile failed")
                VmResult.Error("Failed to create file: ${e.message}", e)
            }
        }

    suspend fun deleteItems(items: List<FileItem>): VmResult<Int> =
        withContext(Dispatchers.IO) {
            try {
                var count = 0
                for (item in items) {
                    val file = File(item.path)
                    if (file.exists()) {
                        val deleted = if (file.isDirectory) {
                            file.deleteRecursively()
                        } else {
                            file.delete()
                        }
                        if (deleted) count++
                    }
                }
                VmResult.Success(count)
            } catch (e: Exception) {
                Timber.tag("FileOps").e(e, "deleteItems failed")
                VmResult.Error("Failed to delete: ${e.message}", e)
            }
        }

    suspend fun renameItem(item: FileItem, newName: String): VmResult<FileItem> =
        withContext(Dispatchers.IO) {
            try {
                val trimmed = newName.trim()
                if (trimmed.isEmpty()) {
                    return@withContext VmResult.Error("Name cannot be empty")
                }
                if (trimmed.contains('/') || trimmed.contains('\\')) {
                    return@withContext VmResult.Error("Name cannot contain path separators")
                }
                val source = File(item.path)
                val target = File(source.parentFile, trimmed)
                if (target.exists()) {
                    return@withContext VmResult.Error("'$trimmed' already exists")
                }
                if (source.renameTo(target)) {
                    VmResult.Success(target.toFileItem())
                } else {
                    VmResult.Error("Failed to rename")
                }
            } catch (e: Exception) {
                Timber.tag("FileOps").e(e, "renameItem failed")
                VmResult.Error("Failed to rename: ${e.message}", e)
            }
        }

    suspend fun copyItems(items: List<FileItem>, destination: File): VmResult<Int> =
        withContext(Dispatchers.IO) {
            try {
                var count = 0
                for (item in items) {
                    val source = File(item.path)
                    val target = resolveConflict(File(destination, source.name))
                    if (source.isDirectory) {
                        source.copyRecursively(target, overwrite = false)
                    } else {
                        source.copyTo(target, overwrite = false)
                    }
                    count++
                }
                VmResult.Success(count)
            } catch (e: Exception) {
                Timber.tag("FileOps").e(e, "copyItems failed")
                VmResult.Error("Failed to copy: ${e.message}", e)
            }
        }

    suspend fun moveItems(items: List<FileItem>, destination: File): VmResult<Int> =
        withContext(Dispatchers.IO) {
            try {
                var count = 0
                for (item in items) {
                    val source = File(item.path)
                    val target = resolveConflict(File(destination, source.name))
                    if (source.renameTo(target)) {
                        count++
                    } else {
                        // renameTo fails across filesystems, fall back to copy+delete
                        if (source.isDirectory) {
                            source.copyRecursively(target, overwrite = false)
                        } else {
                            source.copyTo(target, overwrite = false)
                        }
                        source.deleteRecursively()
                        count++
                    }
                }
                VmResult.Success(count)
            } catch (e: Exception) {
                Timber.tag("FileOps").e(e, "moveItems failed")
                VmResult.Error("Failed to move: ${e.message}", e)
            }
        }

    suspend fun getStorageInfo(rootDir: File): StorageInfo = withContext(Dispatchers.IO) {
        val usedBytes = calculateDirSize(rootDir)
        val totalBytes = rootDir.totalSpace
        val freeBytes = rootDir.freeSpace
        StorageInfo(
            totalBytes = totalBytes,
            usedBytes = usedBytes,
            freeBytes = freeBytes
        )
    }

    suspend fun searchFiles(
        rootDir: File,
        query: String,
        maxResults: Int = 200
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val lowerQuery = query.lowercase()
        rootDir.walkTopDown()
            .filter { it != rootDir && it.name.lowercase().contains(lowerQuery) }
            .take(maxResults)
            .map { it.toFileItem() }
            .toList()
    }

    fun getFileProperties(item: FileItem): Map<String, String> {
        val file = File(item.path)
        val props = linkedMapOf<String, String>()
        props["Name"] = item.name
        props["Path"] = item.path
        props["Type"] = if (item.isDirectory) "Folder" else (item.extension.uppercase().ifEmpty { "File" })
        if (!item.isDirectory) {
            props["Size"] = formatFileSize(item.sizeBytes)
        } else {
            props["Items"] = "${item.childCount} items"
        }
        props["Modified"] = formatDate(item.lastModified)
        props["Hidden"] = if (item.isHidden) "Yes" else "No"
        props["Readable"] = if (file.canRead()) "Yes" else "No"
        props["Writable"] = if (file.canWrite()) "Yes" else "No"
        if (!item.isDirectory && item.mimeType.isNotEmpty()) {
            props["MIME Type"] = item.mimeType
        }
        return props
    }

    private fun resolveConflict(file: File): File {
        if (!file.exists()) return file
        val parent = file.parentFile ?: return file
        val nameWithoutExt = file.nameWithoutExtension
        val ext = file.extension
        var counter = 1
        while (true) {
            val newName = if (ext.isNotEmpty()) {
                "${nameWithoutExt} ($counter).$ext"
            } else {
                "${nameWithoutExt} ($counter)"
            }
            val candidate = File(parent, newName)
            if (!candidate.exists()) return candidate
            counter++
        }
    }

    private fun calculateDirSize(dir: File): Long {
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun sortItems(items: List<FileItem>, sortMode: FileSortMode): List<FileItem> {
        // Always put directories first
        val dirs = items.filter { it.isDirectory }
        val files = items.filter { !it.isDirectory }

        val sortedDirs = when (sortMode) {
            FileSortMode.NAME_ASC -> dirs.sortedBy { it.name.lowercase() }
            FileSortMode.NAME_DESC -> dirs.sortedByDescending { it.name.lowercase() }
            FileSortMode.SIZE_ASC -> dirs.sortedBy { it.childCount }
            FileSortMode.SIZE_DESC -> dirs.sortedByDescending { it.childCount }
            FileSortMode.DATE_ASC -> dirs.sortedBy { it.lastModified }
            FileSortMode.DATE_DESC -> dirs.sortedByDescending { it.lastModified }
            FileSortMode.TYPE_ASC -> dirs.sortedBy { it.name.lowercase() }
            FileSortMode.TYPE_DESC -> dirs.sortedByDescending { it.name.lowercase() }
        }

        val sortedFiles = when (sortMode) {
            FileSortMode.NAME_ASC -> files.sortedBy { it.name.lowercase() }
            FileSortMode.NAME_DESC -> files.sortedByDescending { it.name.lowercase() }
            FileSortMode.SIZE_ASC -> files.sortedBy { it.sizeBytes }
            FileSortMode.SIZE_DESC -> files.sortedByDescending { it.sizeBytes }
            FileSortMode.DATE_ASC -> files.sortedBy { it.lastModified }
            FileSortMode.DATE_DESC -> files.sortedByDescending { it.lastModified }
            FileSortMode.TYPE_ASC -> files.sortedBy { it.extension.lowercase() }
            FileSortMode.TYPE_DESC -> files.sortedByDescending { it.extension.lowercase() }
        }

        return sortedDirs + sortedFiles
    }

    companion object {
        fun File.toFileItem(): FileItem {
            val ext = extension.lowercase()
            return FileItem(
                name = name,
                path = absolutePath,
                isDirectory = isDirectory,
                sizeBytes = if (isFile) length() else 0,
                lastModified = lastModified(),
                extension = ext,
                isHidden = isHidden,
                childCount = if (isDirectory) (listFiles()?.size ?: 0) else 0,
                mimeType = guessMimeType(ext)
            )
        }

        fun guessMimeType(extension: String): String {
            return when (extension) {
                "apk" -> "application/vnd.android.package-archive"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                "svg" -> "image/svg+xml"
                "mp4" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "avi" -> "video/x-msvideo"
                "mov" -> "video/quicktime"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "ogg" -> "audio/ogg"
                "flac" -> "audio/flac"
                "aac" -> "audio/aac"
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "rar" -> "application/x-rar-compressed"
                "7z" -> "application/x-7z-compressed"
                "tar" -> "application/x-tar"
                "gz" -> "application/gzip"
                "txt" -> "text/plain"
                "log" -> "text/plain"
                "md" -> "text/markdown"
                "json" -> "application/json"
                "xml" -> "application/xml"
                "yaml", "yml" -> "application/yaml"
                "html", "htm" -> "text/html"
                "css" -> "text/css"
                "js" -> "application/javascript"
                "db", "sqlite" -> "application/x-sqlite3"
                "dex" -> "application/octet-stream"
                "so" -> "application/octet-stream"
                "jar" -> "application/java-archive"
                else -> URLConnection.guessContentTypeFromName("file.$extension") ?: ""
            }
        }

        fun formatFileSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return "%.1f KB".format(kb)
            val mb = kb / 1024.0
            if (mb < 1024) return "%.1f MB".format(mb)
            val gb = mb / 1024.0
            return "%.2f GB".format(gb)
        }

        fun formatDate(millis: Long): String {
            if (millis <= 0) return "Unknown"
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            return sdf.format(java.util.Date(millis))
        }
    }
}

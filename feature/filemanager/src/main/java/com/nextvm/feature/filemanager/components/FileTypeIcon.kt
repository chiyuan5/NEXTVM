package com.nextvm.feature.filemanager.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.nextvm.core.model.FileItem

@Composable
fun FileTypeIcon(
    item: FileItem,
    modifier: Modifier = Modifier,
    tintOverride: Color? = null
) {
    val icon = when {
        item.isDirectory -> Icons.Default.Folder
        item.extension in listOf("apk") -> Icons.Default.Android
        item.extension in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg") -> Icons.Default.Image
        item.extension in listOf("mp4", "mkv", "avi", "mov", "webm") -> Icons.Default.VideoFile
        item.extension in listOf("mp3", "wav", "ogg", "flac", "aac", "m4a") -> Icons.Default.AudioFile
        item.extension in listOf("pdf") -> Icons.Default.PictureAsPdf
        item.extension in listOf("zip", "rar", "7z", "tar", "gz", "bz2") -> Icons.Default.FolderZip
        item.extension in listOf("txt", "log", "md", "csv") -> Icons.Default.Description
        item.extension in listOf("json", "xml", "yaml", "yml", "html", "htm", "css", "js", "kt", "java") -> Icons.Default.Code
        item.extension in listOf("db", "sqlite", "sqlite3") -> Icons.Default.Storage
        item.extension in listOf("so") -> Icons.Default.Memory
        item.extension in listOf("dex", "jar", "class") -> Icons.Default.DataObject
        else -> Icons.Default.InsertDriveFile
    }

    val tint = tintOverride ?: when {
        item.isDirectory -> MaterialTheme.colorScheme.primary
        item.extension == "apk" -> MaterialTheme.colorScheme.tertiary
        item.extension in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg") -> Color(0xFF66BB6A)
        item.extension in listOf("mp4", "mkv", "avi", "mov") -> Color(0xFFEF5350)
        item.extension in listOf("mp3", "wav", "ogg", "flac", "aac") -> Color(0xFFAB47BC)
        item.extension == "pdf" -> Color(0xFFEF5350)
        item.extension in listOf("zip", "rar", "7z", "tar", "gz") -> Color(0xFFFFA726)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Icon(
        imageVector = icon,
        contentDescription = if (item.isDirectory) "Folder" else item.extension.uppercase(),
        modifier = modifier,
        tint = tint
    )
}

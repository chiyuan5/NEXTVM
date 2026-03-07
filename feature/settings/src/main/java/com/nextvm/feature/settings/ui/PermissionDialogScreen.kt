package com.nextvm.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Permission request result returned from the dialog.
 */
enum class PermissionDialogResult {
    ALLOW,
    DENY,
    DENY_DONT_ASK_AGAIN
}

/**
 * Data class representing a permission request from a virtual app.
 */
data class PermissionRequest(
    val permission: String,
    val appName: String,
    val instanceId: String,
    val description: String = getPermissionDescription(permission),
    val isGrouped: Boolean = false,
    val groupPermissions: List<String> = emptyList()
)

/**
 * Material 3 Permission Dialog for virtual apps.
 *
 * Matches Android's native permission dialog UX:
 * - Shows permission name + description
 * - "Allow" / "Deny" buttons
 * - "Don't ask again" checkbox (shown after first deny)
 *
 * Usage:
 * ```kotlin
 * PermissionDialog(
 *     request = PermissionRequest(
 *         permission = "android.permission.CAMERA",
 *         appName = "WhatsApp",
 *         instanceId = "com.whatsapp_1709234567890"
 *     ),
 *     showDontAskAgain = hasDeniedBefore,
 *     onResult = { result ->
 *         when (result) {
 *             PermissionDialogResult.ALLOW -> grantPermission()
 *             PermissionDialogResult.DENY -> denyPermission()
 *             PermissionDialogResult.DENY_DONT_ASK_AGAIN -> permanentlyDeny()
 *         }
 *     }
 * )
 * ```
 */
@Composable
fun PermissionDialog(
    request: PermissionRequest,
    showDontAskAgain: Boolean = false,
    onResult: (PermissionDialogResult) -> Unit
) {
    var dontAskAgain by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            onResult(PermissionDialogResult.DENY)
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        ),
        icon = {
            Icon(
                imageVector = Icons.Outlined.Security,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Allow ${request.appName} to ${getPermissionAction(request.permission)}?",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium
            )
        },
        text = {
            Column {
                Text(
                    text = request.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (request.isGrouped && request.groupPermissions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "This includes:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    request.groupPermissions.forEach { perm ->
                        Text(
                            text = "• ${getPermissionShortName(perm)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                        )
                    }
                }

                if (showDontAskAgain) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = dontAskAgain,
                            onCheckedChange = { dontAskAgain = it }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Don't ask again",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onResult(PermissionDialogResult.ALLOW) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Allow")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = {
                    if (dontAskAgain) {
                        onResult(PermissionDialogResult.DENY_DONT_ASK_AGAIN)
                    } else {
                        onResult(PermissionDialogResult.DENY)
                    }
                }
            ) {
                Text("Deny")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        iconContentColor = MaterialTheme.colorScheme.primary,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(28.dp)
    )
}

/**
 * Multi-permission dialog — shows multiple permissions at once.
 * Used when an app requests a permission group.
 */
@Composable
fun MultiPermissionDialog(
    appName: String,
    permissions: List<String>,
    onResult: (Map<String, PermissionDialogResult>) -> Unit
) {
    val results = remember { mutableMapOf<String, PermissionDialogResult>() }

    AlertDialog(
        onDismissRequest = {
            permissions.forEach { results[it] = PermissionDialogResult.DENY }
            onResult(results)
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        ),
        icon = {
            Icon(
                imageVector = Icons.Outlined.Security,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "$appName needs permissions",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium
            )
        },
        text = {
            Column {
                Text(
                    text = "This app requires the following permissions to function properly:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                permissions.forEach { permission ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = getPermissionShortName(permission),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = getPermissionDescription(permission),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    permissions.forEach { results[it] = PermissionDialogResult.ALLOW }
                    onResult(results)
                }
            ) {
                Text("Allow All")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = {
                    permissions.forEach { results[it] = PermissionDialogResult.DENY }
                    onResult(results)
                }
            ) {
                Text("Deny All")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp)
    )
}

/**
 * Inline permission banner — non-modal permission request.
 * Displayed as a banner at the top/bottom of a screen.
 */
@Composable
fun PermissionBanner(
    permission: String,
    appName: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Security,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$appName needs ${getPermissionShortName(permission)}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = getPermissionDescription(permission),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }

            TextButton(onClick = onDeny) {
                Text("Deny", color = MaterialTheme.colorScheme.onSecondaryContainer)
            }

            Button(onClick = onAllow) {
                Text("Allow")
            }
        }
    }
}

// ==================== Permission Metadata Helpers ====================

/**
 * Get a human-readable action description for a permission.
 */
private fun getPermissionAction(permission: String): String {
    return when {
        permission.contains("CAMERA") -> "access the camera"
        permission.contains("RECORD_AUDIO") || permission.contains("MICROPHONE") -> "use the microphone"
        permission.contains("LOCATION") -> "access your location"
        permission.contains("READ_CONTACTS") -> "read your contacts"
        permission.contains("WRITE_CONTACTS") -> "modify your contacts"
        permission.contains("READ_CALENDAR") -> "read your calendar"
        permission.contains("WRITE_CALENDAR") -> "modify your calendar"
        permission.contains("READ_PHONE") || permission.contains("CALL_PHONE") -> "make and manage phone calls"
        permission.contains("SEND_SMS") || permission.contains("READ_SMS") -> "access SMS messages"
        permission.contains("READ_EXTERNAL") || permission.contains("WRITE_EXTERNAL") -> "access files on your device"
        permission.contains("READ_MEDIA_IMAGES") -> "access photos"
        permission.contains("READ_MEDIA_VIDEO") -> "access videos"
        permission.contains("READ_MEDIA_AUDIO") -> "access music and audio"
        permission.contains("BODY_SENSORS") -> "access body sensors"
        permission.contains("BLUETOOTH") -> "use Bluetooth"
        permission.contains("NEARBY_WIFI") -> "find nearby devices"
        permission.contains("POST_NOTIFICATIONS") -> "send notifications"
        permission.contains("ACTIVITY_RECOGNITION") -> "track your physical activity"
        else -> "use ${getPermissionShortName(permission).lowercase()}"
    }
}

/**
 * Get a human-readable description for a permission.
 */
internal fun getPermissionDescription(permission: String): String {
    return when {
        permission.contains("CAMERA") -> "This allows the app to take photos and record video using the camera."
        permission.contains("RECORD_AUDIO") -> "This allows the app to record audio using the microphone."
        permission.contains("ACCESS_FINE_LOCATION") -> "This allows the app to use GPS for precise location."
        permission.contains("ACCESS_COARSE_LOCATION") -> "This allows the app to use network-based approximate location."
        permission.contains("READ_CONTACTS") -> "This allows the app to read contact data stored on your device."
        permission.contains("WRITE_CONTACTS") -> "This allows the app to modify contact data stored on your device."
        permission.contains("READ_CALENDAR") -> "This allows the app to read calendar events."
        permission.contains("WRITE_CALENDAR") -> "This allows the app to add or modify calendar events."
        permission.contains("CALL_PHONE") -> "This allows the app to make phone calls without your confirmation."
        permission.contains("READ_PHONE") -> "This allows the app to read phone state information."
        permission.contains("SEND_SMS") -> "This allows the app to send SMS messages."
        permission.contains("READ_SMS") -> "This allows the app to read SMS messages."
        permission.contains("READ_EXTERNAL") -> "This allows the app to read files from shared storage."
        permission.contains("WRITE_EXTERNAL") -> "This allows the app to write files to shared storage."
        permission.contains("READ_MEDIA_IMAGES") -> "This allows the app to access photos on your device."
        permission.contains("READ_MEDIA_VIDEO") -> "This allows the app to access videos on your device."
        permission.contains("READ_MEDIA_AUDIO") -> "This allows the app to access music and audio files."
        permission.contains("BODY_SENSORS") -> "This allows the app to access data from body sensors like heart rate monitors."
        permission.contains("POST_NOTIFICATIONS") -> "This allows the app to show notifications."
        permission.contains("BLUETOOTH") -> "This allows the app to connect to Bluetooth devices."
        permission.contains("ACTIVITY_RECOGNITION") -> "This allows the app to recognize your physical activity."
        else -> "This permission is needed by the app to function properly."
    }
}

/**
 * Get a short human-readable name for a permission.
 */
internal fun getPermissionShortName(permission: String): String {
    return when {
        permission.contains("CAMERA") -> "Camera"
        permission.contains("RECORD_AUDIO") -> "Microphone"
        permission.contains("ACCESS_FINE_LOCATION") -> "Precise Location"
        permission.contains("ACCESS_COARSE_LOCATION") -> "Approximate Location"
        permission.contains("ACCESS_BACKGROUND_LOCATION") -> "Background Location"
        permission.contains("READ_CONTACTS") -> "Contacts (Read)"
        permission.contains("WRITE_CONTACTS") -> "Contacts (Write)"
        permission.contains("READ_CALENDAR") -> "Calendar (Read)"
        permission.contains("WRITE_CALENDAR") -> "Calendar (Write)"
        permission.contains("CALL_PHONE") -> "Phone Calls"
        permission.contains("READ_PHONE") -> "Phone State"
        permission.contains("SEND_SMS") -> "SMS (Send)"
        permission.contains("READ_SMS") -> "SMS (Read)"
        permission.contains("READ_EXTERNAL_STORAGE") -> "Storage (Read)"
        permission.contains("WRITE_EXTERNAL_STORAGE") -> "Storage (Write)"
        permission.contains("READ_MEDIA_IMAGES") -> "Photos"
        permission.contains("READ_MEDIA_VIDEO") -> "Videos"
        permission.contains("READ_MEDIA_AUDIO") -> "Music & Audio"
        permission.contains("BODY_SENSORS") -> "Body Sensors"
        permission.contains("POST_NOTIFICATIONS") -> "Notifications"
        permission.contains("BLUETOOTH_CONNECT") -> "Bluetooth"
        permission.contains("BLUETOOTH_SCAN") -> "Bluetooth Scan"
        permission.contains("NEARBY_WIFI") -> "Nearby WiFi"
        permission.contains("ACTIVITY_RECOGNITION") -> "Physical Activity"
        else -> permission.substringAfterLast(".").replace("_", " ")
            .lowercase().replaceFirstChar { it.uppercase() }
    }
}

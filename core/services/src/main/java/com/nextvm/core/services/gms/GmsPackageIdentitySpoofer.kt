package com.nextvm.core.services.gms

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Parcel
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GmsPackageIdentitySpoofer — Rewrites package identity for GMS IPC calls.
 *
 * WHY THIS IS NEEDED:
 * Google Play Services validates the callingPackage of every IPC request.
 * It checks:
 * 1. Is this package installed? (Guest apps are NOT installed on the host)
 * 2. Does the package's signing certificate match? (Guest apps have different certs)
 * 3. Is the calling UID authorized? (Guest apps run under NEXTVM's UID)
 *
 * SOLUTION:
 * When a guest app calls GMS, we rewrite the callingPackage to the host app's
 * package name (com.nextvm.app), which IS installed and trusted by GMS.
 * On the way back, we restore the guest app's identity in the response.
 *
 * FLOW:
 * ┌──────────┐         ┌─────────────────┐         ┌──────────┐
 * │ Guest    │ ──call──> │ IdentitySpoofer │ ──call──> │ Real GMS │
 * │ App      │         │  pkg=guest.app  │         │          │
 * │          │         │  → pkg=nextvm   │         │          │
 * │          │ <─resp─ │  pkg=nextvm     │ <─resp─ │          │
 * │          │         │  → pkg=guest.app│         │          │
 * └──────────┘         └─────────────────┘         └──────────┘
 */
@Singleton
class GmsPackageIdentitySpoofer @Inject constructor() {

    companion object {
        private const val TAG = "GmsIdSpoof"

        // GMS packages whose identity we need to handle specially
        val GMS_PACKAGES = setOf(
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending"
        )
    }

    private var hostPackageName: String = ""
    private var hostSignatureBytes: ByteArray? = null
    private var initialized = false

    // Active identity mappings: guestPackage -> instanceId
    private val activeMappings = ConcurrentHashMap<String, String>()

    /**
     * Initialize the identity spoofer.
     */
    fun initialize(context: Context) {
        hostPackageName = context.packageName

        // Cache the host app's signing certificate for signature spoofing
        try {
            @Suppress("DEPRECATION", "PackageManagerGetSignatures")
            val info = context.packageManager.getPackageInfo(
                hostPackageName,
                PackageManager.GET_SIGNATURES
            )
            @Suppress("DEPRECATION")
            hostSignatureBytes = info?.signatures?.firstOrNull()?.toByteArray()
        } catch (e: Exception) {
            Timber.tag(TAG).w("Could not cache host signature: ${e.message}")
        }

        initialized = true
        Timber.tag(TAG).i("GmsPackageIdentitySpoofer initialized (host=$hostPackageName)")
    }

    /**
     * Register an active guest app identity mapping.
     * Called when a guest app is launched.
     */
    fun registerMapping(guestPackageName: String, instanceId: String) {
        activeMappings[guestPackageName] = instanceId
        Timber.tag(TAG).d("Identity mapping registered: $guestPackageName -> $instanceId")
    }

    /**
     * Unregister a guest app identity mapping.
     */
    fun unregisterMapping(guestPackageName: String) {
        activeMappings.remove(guestPackageName)
    }

    /**
     * Get the instance ID for a guest package.
     */
    fun getInstanceId(guestPackageName: String): String? {
        return activeMappings[guestPackageName]
    }

    /**
     * Get all currently registered guest package names.
     * Used by GmsBinderProxy to rewrite ANY virtual package in Parcel data,
     * not just the one the proxy was initially configured with.
     */
    fun getRegisteredGuestPackages(): Set<String> {
        return activeMappings.keys.toSet()
    }

    // ====================================================================
    // Intent-level Package Rewriting
    // ====================================================================

    /**
     * Rewrite an outgoing Intent from a guest app to GMS.
     * Changes callingPackage from guest → host so GMS trusts the call.
     *
     * @param intent The Intent being sent to GMS
     * @param guestPackageName The guest app's real package name
     * @return Modified Intent with host package identity
     */
    fun spoofOutgoingIntent(intent: Intent, guestPackageName: String): Intent {
        val spoofed = Intent(intent)

        // Store original guest package in extras for restoration
        spoofed.putExtra("_nextvm_original_pkg", guestPackageName)
        spoofed.putExtra("_nextvm_instance_id", activeMappings[guestPackageName] ?: "")

        // Rewrite calling package references
        if (intent.`package` == guestPackageName) {
            spoofed.`package` = hostPackageName
        }

        // Rewrite extras that contain the calling package
        rewriteBundlePackageName(spoofed.extras, guestPackageName, hostPackageName)

        return spoofed
    }

    /**
     * Restore guest package identity in a response from GMS.
     *
     * @param intent The response Intent from GMS
     * @param guestPackageName The guest app's real package name
     * @return Modified Intent with guest package identity restored
     */
    fun restoreIncomingIntent(intent: Intent, guestPackageName: String): Intent {
        val restored = Intent(intent)

        // Remove our internal extras
        restored.removeExtra("_nextvm_original_pkg")
        restored.removeExtra("_nextvm_instance_id")

        // Restore package references
        if (intent.`package` == hostPackageName) {
            restored.`package` = guestPackageName
        }

        // Restore extras
        rewriteBundlePackageName(restored.extras, hostPackageName, guestPackageName)

        return restored
    }

    // ====================================================================
    // Parcel-level Package Rewriting (for Binder IPC)
    // ====================================================================

    /**
     * Rewrite package name in a Parcel's data.
     * Used by GmsBinderProxy to rewrite callingPackage in Binder transactions.
     *
     * Strategy: Parcels serialize Strings as length + UTF-16 data. We scan the
     * Parcel byte stream for occurrences of fromPkg and replace with toPkg.
     * This works because GMS service methods typically have the callingPackage
     * as one of the first String parameters.
     *
     * If fromPkg and toPkg have different lengths, we must rebuild the Parcel.
     */
    fun rewriteParcelPackageName(parcel: Parcel, fromPkg: String, toPkg: String) {
        if (fromPkg == toPkg) return

        try {
            val data = parcel.marshall()
            val fromBytes = fromPkg.toByteArray(Charsets.UTF_16LE)
            val toBytes = toPkg.toByteArray(Charsets.UTF_16LE)

            // Find occurrences of fromPkg in the marshalled data
            val positions = findBytePattern(data, fromBytes)
            if (positions.isEmpty()) return

            if (fromBytes.size == toBytes.size) {
                // Same size — in-place replacement
                for (pos in positions) {
                    System.arraycopy(toBytes, 0, data, pos, toBytes.size)
                }
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
            } else {
                // Different sizes — rebuild Parcel by reading/writing String fields
                val newParcel = Parcel.obtain()
                try {
                    parcel.setDataPosition(0)
                    rebuildParcelWithReplacement(parcel, newParcel, fromPkg, toPkg)
                    val newData = newParcel.marshall()
                    parcel.unmarshall(newData, 0, newData.size)
                    parcel.setDataPosition(0)
                } finally {
                    newParcel.recycle()
                }
            }

            Timber.tag(TAG).d("Parcel rewrite: $fromPkg -> $toPkg (${positions.size} occurrences)")
        } catch (e: RuntimeException) {
            // marshall() fails with "Tried to marshall a Parcel that contains
            // objects (binders or FDs)" — use binder-safe fallback via appendFrom
            if (e.message?.contains("binders") == true || e.message?.contains("marshall") == true) {
                rewriteParcelWithBindersFallback(parcel, fromPkg, toPkg)
            } else {
                Timber.tag(TAG).w("Parcel rewrite failed: ${e.message}")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Parcel rewrite failed: ${e.message}")
        }
    }

    /**
     * Binder-safe Parcel rewrite for Parcels containing IBinder objects or file descriptors.
     *
     * Strategy: Instead of marshall() (which fails with binders/FDs), we scan EARLY
     * Parcel fields using readString(). The calling package is typically the 2nd-3rd
     * string field, BEFORE any binder objects. Android's native validateReadData() only
     * blocks reads that cross binder object positions — early fields are safe to read.
     *
     * Reconstruction uses appendFrom() which preserves binder objects at correct positions.
     */
    private fun rewriteParcelWithBindersFallback(parcel: Parcel, fromPkg: String, toPkg: String) {
        try {
            val dataSize = parcel.dataSize()
            if (dataSize < 8) return

            parcel.setDataPosition(0)

            // Scan 4-byte-aligned positions looking for [int32 length == fromPkg.length]
            // followed by matching string. In GMS broker calls, the package name may be
            // AFTER binder objects (e.g., inside a GetServiceRequest Bundle), so we scan
            // the full range rather than stopping at binder boundaries.
            data class FoundString(val startPos: Int, val endPos: Int)
            val locations = mutableListOf<FoundString>()
            val maxScanBytes = minOf(dataSize, 1024)

            while (parcel.dataPosition() + 4 <= maxScanBytes) {
                val pos = parcel.dataPosition()
                val lenValue: Int
                try {
                    lenValue = parcel.readInt()
                } catch (_: Exception) {
                    // readInt() failed — skip forward past possible binder region
                    parcel.setDataPosition(pos + 48)
                    continue
                }

                if (lenValue == fromPkg.length) {
                    // Potential match — go back and verify with readString()
                    parcel.setDataPosition(pos)
                    val startPos = pos
                    try {
                        val str = parcel.readString()
                        if (str == fromPkg) {
                            locations.add(FoundString(startPos, parcel.dataPosition()))
                            continue
                        }
                    } catch (_: Exception) {
                        // readString() failed, continue scanning
                    }
                    parcel.setDataPosition(pos + 4)
                }
                // For any other value (including 0 from protected data), just continue
                // scanning — readInt() already advanced position by 4
            }

            if (locations.isEmpty()) {
                parcel.setDataPosition(0)
                return
            }

            // Reconstruct using appendFrom() which preserves binder objects
            val newParcel = Parcel.obtain()
            try {
                var lastEnd = 0
                for (loc in locations) {
                    if (loc.startPos > lastEnd) {
                        newParcel.appendFrom(parcel, lastEnd, loc.startPos - lastEnd)
                    }
                    newParcel.writeString(toPkg)
                    lastEnd = loc.endPos
                }
                if (lastEnd < dataSize) {
                    newParcel.appendFrom(parcel, lastEnd, dataSize - lastEnd)
                }

                parcel.setDataSize(0)
                parcel.appendFrom(newParcel, 0, newParcel.dataSize())
                parcel.setDataPosition(0)

                Timber.tag(TAG).d("Parcel rewrite (binder-safe): $fromPkg -> $toPkg (${locations.size} occurrences)")
            } finally {
                newParcel.recycle()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Parcel binder-safe rewrite failed: ${e.message}")
            parcel.setDataPosition(0)
        }
    }

    /**
     * Restore package name in a response Parcel.
     */
    fun restoreParcelPackageName(parcel: Parcel, fromPkg: String, toPkg: String) {
        // Reuse the same logic in reverse direction
        rewriteParcelPackageName(parcel, fromPkg, toPkg)
    }

    /**
     * Find all occurrences of a byte pattern in a byte array.
     */
    private fun findBytePattern(data: ByteArray, pattern: ByteArray): List<Int> {
        val positions = mutableListOf<Int>()
        if (pattern.isEmpty() || data.size < pattern.size) return positions

        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            positions.add(i)
        }
        return positions
    }

    /**
     * Rebuild a Parcel, replacing String fields that match fromPkg with toPkg.
     * This is a best-effort approach — it reads all data from the source Parcel
     * as raw bytes and performs string replacement in the serialized form.
     */
    private fun rebuildParcelWithReplacement(
        source: Parcel, dest: Parcel,
        fromPkg: String, toPkg: String
    ) {
        // Read entire source as bytes, do byte-level replacement
        val sourceData = source.marshall()
        val fromUtf16 = fromPkg.toByteArray(Charsets.UTF_16LE)
        val toUtf16 = toPkg.toByteArray(Charsets.UTF_16LE)

        // Also need to fix the string length prefix (4 bytes before UTF-16 data)
        // Parcel String format: [int32 length_in_chars] [UTF-16LE data] [0x0000 null terminator]
        val fromLenBytes = intToLittleEndian(fromPkg.length)
        val toLenBytes = intToLittleEndian(toPkg.length)

        var result = sourceData
        val positions = findBytePattern(result, fromUtf16)
        if (positions.isEmpty()) {
            dest.unmarshall(sourceData, 0, sourceData.size)
            dest.setDataPosition(0)
            return
        }

        // For each occurrence, check if 4 bytes before it contain the length prefix
        // and replace both length and string data
        val output = mutableListOf<Byte>()
        var lastCopyEnd = 0

        for (pos in positions) {
            val lengthPos = pos - 4
            if (lengthPos >= 0) {
                val currentLen = littleEndianToInt(result, lengthPos)
                if (currentLen == fromPkg.length) {
                    // Copy everything before the length prefix
                    for (i in lastCopyEnd until lengthPos) output.add(result[i])
                    // Write new length
                    for (b in toLenBytes) output.add(b)
                    // Write new string data
                    for (b in toUtf16) output.add(b)
                    // Skip old string data (length prefix + string + null terminator)
                    lastCopyEnd = pos + fromUtf16.size
                    continue
                }
            }
            // No valid length prefix — just replace the bytes
            for (i in lastCopyEnd until pos) output.add(result[i])
            for (b in toUtf16) output.add(b)
            lastCopyEnd = pos + fromUtf16.size
        }

        // Copy remaining data
        for (i in lastCopyEnd until result.size) output.add(result[i])

        val outputArray = output.toByteArray()
        dest.unmarshall(outputArray, 0, outputArray.size)
        dest.setDataPosition(0)
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun littleEndianToInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return -1
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    // ====================================================================
    // Bundle-level Package Rewriting
    // ====================================================================

    /**
     * Rewrite package name references inside a Bundle.
     * GMS often passes callingPackage, packageName, etc. in Bundle extras.
     */
    fun rewriteBundlePackageName(bundle: Bundle?, fromPkg: String, toPkg: String) {
        if (bundle == null) return

        // Common GMS Bundle keys that contain package names
        val packageKeys = listOf(
            "callingPackage", "calling_package", "packageName", "package_name",
            "pkg", "app", "appPackageName", "target_package", "clientPackageName",
            "com.google.android.gms.CALLING_PACKAGE"
        )

        for (key in packageKeys) {
            try {
                val value = bundle.getString(key)
                if (value == fromPkg) {
                    bundle.putString(key, toPkg)
                }
            } catch (_: Exception) {}
        }
    }

    // ====================================================================
    // Signature Spoofing for GMS
    // ====================================================================

    /**
     * Check if a package is a GMS package whose identity/signature
     * needs special handling.
     */
    fun isGmsPackage(packageName: String): Boolean {
        return packageName in GMS_PACKAGES ||
                packageName.startsWith("com.google.android.gms")
    }

    /**
     * Get the host app's signing certificate bytes.
     * Used by PackageManagerProxy to make guest apps appear
     * to have the host app's certificate when queried by GMS.
     */
    fun getHostSignatureBytes(): ByteArray? = hostSignatureBytes

    /**
     * Get the real GMS signing certificate from the host device.
     * Used to spoof GMS package signature for guest apps that check it.
     */
    fun getRealGmsSignature(context: Context): ByteArray? {
        return try {
            @Suppress("DEPRECATION", "PackageManagerGetSignatures")
            val info = context.packageManager.getPackageInfo(
                "com.google.android.gms",
                PackageManager.GET_SIGNATURES
            )
            @Suppress("DEPRECATION")
            info?.signatures?.firstOrNull()?.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    // ====================================================================
    // Cleanup
    // ====================================================================

    fun cleanup(guestPackageName: String) {
        activeMappings.remove(guestPackageName)
    }

    fun cleanupAll() {
        activeMappings.clear()
    }

    fun shutdown() {
        cleanupAll()
        initialized = false
    }
}

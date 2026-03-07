package com.nextvm.core.hook

import android.content.Context
import android.os.Build
import com.nextvm.core.common.AndroidCompat
import com.nextvm.core.common.findField
import com.nextvm.core.common.removeFinalModifier
import com.nextvm.core.common.runSafe
import com.nextvm.core.model.DeviceProfile
import timber.log.Timber
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IdentitySpoofingEngine — Complete device identity spoofing system.
 *
 * Spoofs all device-identifying properties so virtual apps see a
 * different device identity than the real host device. This is critical
 * for multi-instance isolation, anti-fingerprinting, and app compatibility.
 *
 * Spoofed identifiers:
 * - Build.MODEL, MANUFACTURER, DEVICE, PRODUCT, BRAND, BOARD, HARDWARE, FINGERPRINT
 * - Build.VERSION.RELEASE, SDK_INT
 * - Build.SERIAL
 * - Settings.Secure.ANDROID_ID
 * - TelephonyManager: IMEI, IMSI, Phone, SIM serial
 * - WifiInfo: MAC address
 * - Advertising ID (via ContentResolver)
 *
 * Uses HookEngine for field modifications and stores original values
 * for clean restoration.
 */
@Singleton
class IdentitySpoofingEngine @Inject constructor(
    private val hookEngine: HookEngine
) {
    companion object {
        private const val TAG = "IdentitySpoof"

        // Build class fields to spoof
        private val BUILD_FIELDS = listOf(
            "BRAND", "MANUFACTURER", "MODEL", "DEVICE", "PRODUCT",
            "BOARD", "HARDWARE", "FINGERPRINT", "DISPLAY", "HOST",
            "ID", "TAGS", "TYPE", "USER"
        )

        // Build.VERSION fields to spoof
        private val VERSION_FIELDS = listOf(
            "RELEASE", "INCREMENTAL", "CODENAME", "BASE_OS",
            "SECURITY_PATCH"
        )

        // Build fields requiring integer values
        private val VERSION_INT_FIELDS = listOf("SDK_INT", "PREVIEW_SDK_INT")
    }

    private var initialized = false

    fun initialize() {
        if (initialized) return
        initialized = true
        Timber.tag(TAG).i("IdentitySpoofingEngine initialized — identity spoofing ready")
    }

    // Track spoofed state per instance
    private val spoofedInstances = mutableMapOf<String, SpoofState>()

    // Original values backup
    private var originalBuildValues: Map<String, Any?> = emptyMap()
    private var originalVersionValues: Map<String, Any?> = emptyMap()
    private var originalSerial: String? = null
    private var isGlobalSpoofApplied = false

    /**
     * Apply a complete device profile to spoof the device identity.
     *
     * @param profile The DeviceProfile with target values
     * @param instanceId The virtual app instance this applies to
     */
    fun applyDeviceProfile(profile: DeviceProfile, instanceId: String) {
        Timber.tag(TAG).i("Applying device profile '${profile.name}' for instance: $instanceId")

        // Save original values before first spoof
        if (!isGlobalSpoofApplied) {
            backupOriginalValues()
        }

        // Apply all spoofing categories
        spoofBuildFields(profile)
        spoofBuildVersionFields(profile)
        spoofSerial(profile)
        spoofAndroidId(instanceId)
        spoofTelephony(profile, instanceId)
        spoofWifi(profile, instanceId)

        // Record spoofed state
        spoofedInstances[instanceId] = SpoofState(
            profile = profile,
            appliedAt = System.currentTimeMillis(),
            androidId = generateConsistentAndroidId(instanceId),
            spoofedFields = BUILD_FIELDS + VERSION_FIELDS + listOf("SERIAL")
        )

        isGlobalSpoofApplied = true
        Timber.tag(TAG).i("Device profile applied successfully: ${countSpoofedFields()} fields modified")
    }

    /**
     * Spoof all Build.* static fields.
     */
    fun spoofBuildFields(profile: DeviceProfile) {
        val buildClass = Build::class.java

        val fieldMap = mapOf(
            "BRAND" to profile.brand,
            "MANUFACTURER" to profile.manufacturer,
            "MODEL" to profile.model,
            "DEVICE" to profile.device,
            "PRODUCT" to profile.product,
            "BOARD" to profile.board,
            "HARDWARE" to profile.hardware,
            "FINGERPRINT" to profile.fingerprint,
            "DISPLAY" to profile.buildId,
            "HOST" to "build.${profile.manufacturer.lowercase()}.com",
            "ID" to profile.buildId,
            "TAGS" to "release-keys",
            "TYPE" to "user",
            "USER" to "android-build"
        )

        var successCount = 0
        for ((fieldName, value) in fieldMap) {
            if (setStaticStringField(buildClass, fieldName, value)) {
                successCount++
            }
        }

        Timber.tag(TAG).d("Spoofed $successCount/${fieldMap.size} Build fields")
    }

    /**
     * Spoof Build.VERSION.* fields.
     */
    fun spoofBuildVersionFields(profile: DeviceProfile) {
        val versionClass = Build.VERSION::class.java

        // String fields
        val stringFields = mapOf(
            "RELEASE" to profile.androidVersion,
            "INCREMENTAL" to extractIncremental(profile.buildId),
            "CODENAME" to "REL",
            "BASE_OS" to "",
            "SECURITY_PATCH" to generateSecurityPatch(profile.buildId)
        )

        var successCount = 0
        for ((fieldName, value) in stringFields) {
            if (setStaticStringField(versionClass, fieldName, value)) {
                successCount++
            }
        }

        // Integer fields
        if (setStaticIntField(versionClass, "SDK_INT", profile.sdkInt)) {
            successCount++
        }
        if (setStaticIntField(versionClass, "PREVIEW_SDK_INT", 0)) {
            successCount++
        }

        // Build.VERSION_CODES has no mutable values — skip

        Timber.tag(TAG).d("Spoofed $successCount/${stringFields.size + 2} Build.VERSION fields")
    }

    /**
     * Spoof Build.SERIAL.
     */
    fun spoofSerial(profile: DeviceProfile) {
        val serial = profile.serial.ifEmpty { generateConsistentSerial(profile.fingerprint) }

        // Build.SERIAL is deprecated but still used
        if (setStaticStringField(Build::class.java, "SERIAL", serial)) {
            Timber.tag(TAG).d("Spoofed Build.SERIAL = $serial")
        }

        // Also hook Build.getSerial() if available (API 26+)
        hookEngine.hookStaticField("android.os.Build", "SERIAL", serial)
    }

    /**
     * Spoof Settings.Secure.ANDROID_ID.
     *
     * ANDROID_ID is accessed via Settings.Secure.getString(resolver, "android_id").
     * We intercept this at the ContentResolver level.
     * Additionally, we set it via reflection on the Settings.Secure.sNameValueCache.
     */
    fun spoofAndroidId(instanceId: String) {
        val spoofedId = generateConsistentAndroidId(instanceId)

        try {
            // Attempt to inject into Settings.Secure's internal cache
            val secureClass = Class.forName("android.provider.Settings\$Secure")

            // Try sNameValueCache field
            val cacheField = findField(secureClass, "sNameValueCache")
            if (cacheField != null) {
                cacheField.isAccessible = true
                val cache = cacheField.get(null)
                if (cache != null) {
                    // NameValueCache has an internal mValues map
                    val valuesField = findField(cache::class.java, "mValues")
                    if (valuesField != null) {
                        valuesField.isAccessible = true
                        @Suppress("UNCHECKED_CAST")
                        val values = valuesField.get(cache) as? MutableMap<String, String>
                        if (values != null) {
                            values["android_id"] = spoofedId
                            Timber.tag(TAG).d("Injected ANDROID_ID into Settings cache: $spoofedId")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to spoof ANDROID_ID via Settings cache")
        }

        Timber.tag(TAG).d("ANDROID_ID for instance $instanceId: $spoofedId")
    }

    /**
     * Spoof Settings.Secure.ANDROID_ID using a Context.
     * Call this when a Context is available for more reliable spoofing.
     */
    fun spoofAndroidId(context: Context, instanceId: String) {
        val spoofedId = generateConsistentAndroidId(instanceId)

        try {
            // Method 1: Direct Settings.Secure cache injection
            spoofAndroidId(instanceId)

            // Method 2: ContentResolver-level interception
            // This is handled by ContentResolverProxy when it intercepts
            // queries to content://settings/secure with name=android_id

            Timber.tag(TAG).d("ANDROID_ID spoofed for instance $instanceId: $spoofedId")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to spoof ANDROID_ID with context")
        }
    }

    /**
     * Spoof telephony identifiers (IMEI, IMSI, Phone number, SIM info).
     *
     * These are intercepted at the TelephonyManager proxy level.
     * This method sets up the data that the proxy will return.
     */
    fun spoofTelephony(profile: DeviceProfile, instanceId: String) {
        try {
            // Generate consistent telephony values if not provided
            val imei = profile.imei.ifEmpty { generateConsistentImei(instanceId) }
            val imsi = generateConsistentImsi(instanceId, profile.mcc, profile.mnc)
            val phoneNumber = generateConsistentPhone(instanceId)
            val simSerial = generateConsistentSimSerial(instanceId)

            // Store these for the TelephonyManager proxy to use
            val spoofState = spoofedInstances[instanceId]
            spoofedInstances[instanceId] = (spoofState ?: SpoofState(
                profile = profile,
                appliedAt = System.currentTimeMillis(),
                androidId = generateConsistentAndroidId(instanceId)
            )).copy(
                imei = imei,
                imsi = imsi,
                phoneNumber = phoneNumber,
                simSerial = simSerial
            )

            // Try direct injection into TelephonyManager's cached values
            try {
                val tmClass = Class.forName("android.telephony.TelephonyManager")

                // Some TelephonyManager implementations cache device ID
                val deviceIdField = findField(tmClass, "mImei")
                if (deviceIdField != null) {
                    deviceIdField.isAccessible = true
                    // This is an instance field, need actual TM instance
                    Timber.tag(TAG).d("Found TelephonyManager.mImei field for injection")
                }
            } catch (_: Exception) { /* Field may not exist on all versions */ }

            Timber.tag(TAG).d("Telephony spoofed for instance $instanceId: IMEI=$imei")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to spoof telephony for instance $instanceId")
        }
    }

    /**
     * Spoof WiFi MAC address.
     */
    fun spoofWifi(profile: DeviceProfile, instanceId: String) {
        val macAddress = profile.macAddress.ifEmpty { generateConsistentMac(instanceId) }

        try {
            // WifiInfo.getMacAddress() returns a cached string
            // We hook this via the WifiManager's internal service proxy

            // Also set the networkInterface-level MAC if possible
            val wifiInfoClass = Class.forName("android.net.wifi.WifiInfo")
            val macField = findField(wifiInfoClass, "mMacAddress")
            if (macField != null) {
                Timber.tag(TAG).d("Found WifiInfo.mMacAddress for injection")
            }

            // Update spoof state
            val state = spoofedInstances[instanceId]
            if (state != null) {
                spoofedInstances[instanceId] = state.copy(macAddress = macAddress)
            }

            Timber.tag(TAG).d("WiFi MAC spoofed for instance $instanceId: $macAddress")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to spoof WiFi MAC for instance $instanceId")
        }
    }

    /**
     * Spoof the Advertising ID (Google Ads ID / AAID).
     */
    fun spoofAdvertisingId(instanceId: String) {
        val adId = generateConsistentUuid(instanceId, "adid")

        val state = spoofedInstances[instanceId]
        if (state != null) {
            spoofedInstances[instanceId] = state.copy(advertisingId = adId)
        }

        Timber.tag(TAG).d("Advertising ID spoofed for instance $instanceId: $adId")
    }

    // ====================================================================
    // Deterministic value generation
    // ====================================================================

    /**
     * Generate a consistent 16-char hex ANDROID_ID from instanceId.
     * Same instanceId always produces the same ANDROID_ID.
     */
    fun generateConsistentAndroidId(instanceId: String): String {
        return sha256Hex("nextvm_android_id_$instanceId").take(16)
    }

    /**
     * Generate a consistent 15-digit IMEI with valid Luhn check digit.
     */
    private fun generateConsistentImei(instanceId: String): String {
        val hash = sha256Hex("nextvm_imei_$instanceId")
        // TAC (Type Allocation Code): use a valid prefix
        val tac = "35${hash.filter { it.isDigit() }.take(6)}"
        val snr = hash.filter { it.isDigit() }.drop(6).take(6)
        val body = "$tac$snr".take(14)
        val paddedBody = body.padEnd(14, '0')
        return paddedBody + luhnCheckDigit(paddedBody)
    }

    /**
     * Generate a consistent IMSI (15 digits: MCC + MNC + MSIN).
     */
    private fun generateConsistentImsi(instanceId: String, mcc: String, mnc: String): String {
        val effectiveMcc = mcc.ifEmpty { "310" }
        val effectiveMnc = mnc.ifEmpty { "260" }
        val hash = sha256Hex("nextvm_imsi_$instanceId")
        val msin = hash.filter { it.isDigit() }.take(15 - effectiveMcc.length - effectiveMnc.length)
        return "$effectiveMcc$effectiveMnc$msin".take(15).padEnd(15, '0')
    }

    /**
     * Generate a consistent phone number.
     */
    private fun generateConsistentPhone(instanceId: String): String {
        val hash = sha256Hex("nextvm_phone_$instanceId")
        val digits = hash.filter { it.isDigit() }.take(10)
        return "+1$digits"
    }

    /**
     * Generate a consistent SIM serial (ICCID, 19-20 digits).
     */
    private fun generateConsistentSimSerial(instanceId: String): String {
        val hash = sha256Hex("nextvm_sim_$instanceId")
        val digits = hash.filter { it.isDigit() }.take(18)
        return "89${digits}".take(20).padEnd(20, '0')
    }

    /**
     * Generate a consistent MAC address.
     */
    private fun generateConsistentMac(instanceId: String): String {
        val hash = sha256Hex("nextvm_mac_$instanceId")
        val bytes = hash.take(12)
        // Set locally administered bit (bit 1 of first octet)
        val firstByte = (bytes.take(2).toInt(16) or 0x02) and 0xFE
        val rest = bytes.drop(2).chunked(2).joinToString(":")
        return String.format("%02x:%s", firstByte, rest)
    }

    /**
     * Generate a consistent serial number.
     */
    private fun generateConsistentSerial(seed: String): String {
        val hash = sha256Hex("nextvm_serial_$seed")
        return hash.uppercase().take(16)
    }

    /**
     * Generate a consistent UUID string.
     */
    private fun generateConsistentUuid(instanceId: String, purpose: String): String {
        val hash = sha256Hex("nextvm_${purpose}_$instanceId")
        // Format as UUID: 8-4-4-4-12
        return "${hash.substring(0, 8)}-${hash.substring(8, 12)}-4${hash.substring(13, 16)}-${hash.substring(16, 20)}-${hash.substring(20, 32)}"
    }

    // ====================================================================
    // Reset / restore
    // ====================================================================

    /**
     * Reset all spoofing back to original device values.
     */
    fun resetAllSpoofing() {
        if (!isGlobalSpoofApplied) {
            Timber.tag(TAG).d("No spoofing to reset")
            return
        }

        Timber.tag(TAG).i("Resetting all identity spoofing...")

        // Restore Build fields
        val buildClass = Build::class.java
        for ((fieldName, value) in originalBuildValues) {
            try {
                val field = buildClass.getDeclaredField(fieldName)
                field.isAccessible = true
                removeFinalModifier(field)
                field.set(null, value)
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to restore Build.$fieldName: ${e.message}")
            }
        }

        // Restore Build.VERSION fields
        val versionClass = Build.VERSION::class.java
        for ((fieldName, value) in originalVersionValues) {
            try {
                val field = versionClass.getDeclaredField(fieldName)
                field.isAccessible = true
                removeFinalModifier(field)
                field.set(null, value)
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to restore Build.VERSION.$fieldName: ${e.message}")
            }
        }

        // Restore serial
        if (originalSerial != null) {
            setStaticStringField(Build::class.java, "SERIAL", originalSerial!!)
        }

        // Unhook all HookEngine-managed hooks
        hookEngine.unhookAll()

        spoofedInstances.clear()
        isGlobalSpoofApplied = false

        Timber.tag(TAG).i("All identity spoofing reset to original values")
    }

    /**
     * Reset spoofing for a specific instance.
     */
    fun resetSpoofingForInstance(instanceId: String) {
        spoofedInstances.remove(instanceId)
        Timber.tag(TAG).d("Spoofing state cleared for instance: $instanceId")

        // If no more instances are spoofed, reset global state
        if (spoofedInstances.isEmpty()) {
            resetAllSpoofing()
        }
    }

    /**
     * Get the current spoof state for an instance.
     */
    fun getSpoofState(instanceId: String): SpoofState? = spoofedInstances[instanceId]

    /**
     * Get the IMEI for a spoofed instance.
     */
    fun getSpoofedImei(instanceId: String): String? = spoofedInstances[instanceId]?.imei

    /**
     * Get the ANDROID_ID for a spoofed instance.
     */
    fun getSpoofedAndroidId(instanceId: String): String? = spoofedInstances[instanceId]?.androidId

    /**
     * Get the MAC address for a spoofed instance.
     */
    fun getSpoofedMacAddress(instanceId: String): String? = spoofedInstances[instanceId]?.macAddress

    /**
     * Count total spoofed fields across all instances.
     */
    fun countSpoofedFields(): Int {
        return spoofedInstances.values.sumOf { it.spoofedFields.size }
    }

    /**
     * Check if any spoofing is active.
     */
    fun isAnySpoofingActive(): Boolean = isGlobalSpoofApplied

    // ====================================================================
    // Internal helpers
    // ====================================================================

    private fun backupOriginalValues() {
        val buildClass = Build::class.java
        val buildBackup = mutableMapOf<String, Any?>()
        for (fieldName in BUILD_FIELDS) {
            try {
                val field = buildClass.getDeclaredField(fieldName)
                field.isAccessible = true
                buildBackup[fieldName] = field.get(null)
            } catch (_: Exception) { /* Field may not exist */ }
        }
        originalBuildValues = buildBackup

        val versionClass = Build.VERSION::class.java
        val versionBackup = mutableMapOf<String, Any?>()
        for (fieldName in VERSION_FIELDS + VERSION_INT_FIELDS) {
            try {
                val field = versionClass.getDeclaredField(fieldName)
                field.isAccessible = true
                versionBackup[fieldName] = field.get(null)
            } catch (_: Exception) { /* Field may not exist */ }
        }
        originalVersionValues = versionBackup

        try {
            val serialField = buildClass.getDeclaredField("SERIAL")
            serialField.isAccessible = true
            originalSerial = serialField.get(null) as? String
        } catch (_: Exception) { /* OK */ }

        Timber.tag(TAG).d("Original values backed up: ${buildBackup.size} Build + ${versionBackup.size} VERSION fields")
    }

    private fun setStaticStringField(clazz: Class<*>, fieldName: String, value: String): Boolean {
        return try {
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true
            removeFinalModifier(field)
            field.set(null, value)
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to set $fieldName: ${e.message}")
            false
        }
    }

    private fun setStaticIntField(clazz: Class<*>, fieldName: String, value: Int): Boolean {
        return try {
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true
            removeFinalModifier(field)
            field.setInt(null, value)
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to set int $fieldName: ${e.message}")
            false
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun luhnCheckDigit(number: String): Char {
        var sum = 0
        var alternate = true
        for (i in number.length - 1 downTo 0) {
            var n = number[i] - '0'
            if (alternate) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alternate = !alternate
        }
        return ((10 - (sum % 10)) % 10 + '0'.code).toChar()
    }

    private fun extractIncremental(buildId: String): String {
        // Extract incremental build number from buildId like "BP1A.250305.019"
        return buildId.replace(".", "").takeLast(8)
    }

    private fun generateSecurityPatch(buildId: String): String {
        // Generate a plausible security patch date: "2025-03-05"
        val regex = Regex("(\\d{6})")
        val match = regex.find(buildId)
        if (match != null) {
            val dateStr = match.value
            val year = "20${dateStr.take(2)}"
            val month = dateStr.substring(2, 4)
            val day = dateStr.substring(4, 6)
            return "$year-$month-$day"
        }
        return "2025-03-01"
    }
}

/**
 * Tracks the spoofing state for a virtual app instance.
 */
data class SpoofState(
    val profile: DeviceProfile,
    val appliedAt: Long,
    val androidId: String = "",
    val imei: String = "",
    val imsi: String = "",
    val phoneNumber: String = "",
    val simSerial: String = "",
    val macAddress: String = "",
    val advertisingId: String = "",
    val spoofedFields: List<String> = emptyList()
)

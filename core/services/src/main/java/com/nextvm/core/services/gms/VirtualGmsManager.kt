package com.nextvm.core.services.gms

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import com.nextvm.core.common.findField
import com.nextvm.core.model.GmsServiceRouter
import com.nextvm.core.model.VmResult
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VirtualGmsManager — Hybrid Google Mobile Services integration.
 *
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║                    HYBRID GMS ARCHITECTURE                      ║
 * ╠══════════════════════════════════════════════════════════════════╣
 * ║                                                                  ║
 * ║  Layer 1: GMS Runtime (Engine)                                   ║
 * ║    → Uses HOST device's real GMS process                         ║
 * ║    → Never copied/installed inside the VM                        ║
 * ║    → Bridged via GmsBinderBridge                                 ║
 * ║                                                                  ║
 * ║  Layer 2: Service API Bridge                                     ║
 * ║    → Firebase, Play Billing, Maps, Analytics, FCM                ║
 * ║    → Intercepts guest app calls → forwards to real GMS           ║
 * ║    → Package identity spoofed via GmsPackageIdentitySpoofer      ║
 * ║                                                                  ║
 * ║  Layer 3: Account Identity Layer (Hybrid Core)                   ║
 * ║    → GMS engine is REAL, but account identity is VIRTUAL         ║
 * ║    → Each VM instance has its own isolated Google account         ║
 * ║    → Host device's account is NOT auto-shared                    ║
 * ║    → OAuth tokens obtained through real GMS (genuine tokens)     ║
 * ║    → Encrypted local storage via VirtualAccountManager           ║
 * ║                                                                  ║
 * ╚══════════════════════════════════════════════════════════════════╝
 *
 * Sub-components:
 *   → GmsBinderBridge: Binder-level tunnel to host GMS
 *   → GmsPackageIdentitySpoofer: callingPackage rewriting
 *   → VirtualAccountManager: Encrypted isolated account store
 *   → VirtualGoogleSignInManager: Google Sign-In per-instance
 *   → VirtualFirebaseAuthProxy: Firebase Auth per-instance
 *   → GmsFcmProxy: FCM push notification routing
 *   → GmsPlayStoreProxy: Play Store billing & licensing
 */
@Singleton
class VirtualGmsManager @Inject constructor(
    val googleSignInManager: VirtualGoogleSignInManager,
    val firebaseAuthProxy: VirtualFirebaseAuthProxy,
    val binderBridge: GmsBinderBridge,
    val accountManager: VirtualAccountManager,
    val identitySpoofer: GmsPackageIdentitySpoofer,
    val fcmProxy: GmsFcmProxy,
    val playStoreProxy: GmsPlayStoreProxy
) : GmsServiceRouter {

    companion object {
        private const val TAG = "VGmsMgr"

        // GMS package identifiers
        const val GMS_PACKAGE = "com.google.android.gms"
        const val GSF_PACKAGE = "com.google.android.gsf"
        const val PLAY_STORE_PACKAGE = "com.android.vending"

        // Well-known GMS service actions
        const val ACTION_ACCOUNT_AUTH = "com.google.android.gms.auth.GOOGLE_SIGN_IN"
        const val ACTION_FCM = "com.google.firebase.MESSAGING_EVENT"
        const val ACTION_AUTH_API = "com.google.android.gms.auth.api.signin.internal.SIGN_IN"
        const val ACTION_PLAY_BILLING = "com.android.vending.billing.InAppBillingService.BIND"
        const val ACTION_MAPS = "com.google.android.gms.maps.internal.ICreator"
        const val ACTION_LOCATION = "com.google.android.gms.location.internal.IGoogleLocationManagerService"
        const val ACTION_FITNESS = "com.google.android.gms.fitness.internal.IGoogleFitService"
        const val ACTION_DRIVE = "com.google.android.gms.drive.internal.IDriveService"
        const val ACTION_GAMES = "com.google.android.gms.games.internal.IGamesService"
        const val ACTION_CAST = "com.google.android.gms.cast.internal.ICastDeviceControllerService"
        const val ACTION_ANALYTICS = "com.google.android.gms.analytics.internal.IAnalyticsService"
        const val ACTION_ADS = "com.google.android.gms.ads.identifier.service.START"
        const val ACTION_SAFETYNET = "com.google.android.gms.safetynet.internal.ISafetyNetService"

        // GMS availability result codes (matches GoogleApiAvailability)
        const val GMS_AVAILABLE = 0
        const val GMS_MISSING = 1
        const val GMS_UPDATE_REQUIRED = 2
        const val GMS_DISABLED = 3
        const val GMS_INVALID = 9

        // Play Store signing certificate SHA-256 fingerprint
        private const val PLAY_STORE_CERT_SHA256 = "F0FD6C5B410F25CB25C3B53346C8972FAE30F8EE7411DF910480AD6B2D60DB83"
        private const val GMS_CERT_SHA256 = "F0FD6C5B410F25CB25C3B53346C8972FAE30F8EE7411DF910480AD6B2D60DB83"
    }

    // Application context reference
    private var appContext: Context? = null

    // Cached GMS availability info
    private var gmsAvailable: Boolean? = null
    private var gmsVersionCode: Int = -1
    private var gmsVersionName: String = ""

    // Spoofed GMS signatures
    private val spoofedSignatures = ConcurrentHashMap<String, ByteArray>()

    // GMS service availability cache
    private val serviceAvailability = ConcurrentHashMap<String, Boolean>()

    // Initialized flag
    private var initialized = false

    // ====================================================================
    // Initialization
    // ====================================================================

    /**
     * Initialize the Hybrid GMS manager and all sub-components.
     *
     * Order matters:
     * 1. Detect GMS availability on host
     * 2. Initialize identity spoofer (needed by all other components)
     * 3. Initialize account manager (needed for auth flows)
     * 4. Initialize Binder bridge (core tunnel)
     * 5. Initialize auth sub-services (Google Sign-In, Firebase Auth)
     * 6. Initialize FCM proxy
     * 7. Initialize Play Store proxy
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        detectGmsAvailability(context)

        // 0. Install GmsCore_OpenSSL security provider
        installGmsSecurityProvider(context)

        // 1. Identity spoofer — needed by bridge and all proxies
        try {
            identitySpoofer.initialize(context)
            Timber.tag(TAG).d("  > GmsPackageIdentitySpoofer: READY")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "  > GmsPackageIdentitySpoofer: FAILED")
        }

        // 2. Account manager — encrypted local account store
        try {
            accountManager.initialize(context)
            Timber.tag(TAG).d("  > VirtualAccountManager: READY")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "  > VirtualAccountManager: FAILED")
        }

        // 3. Binder bridge — core tunnel to host GMS
        try {
            binderBridge.initialize(context, identitySpoofer)
            Timber.tag(TAG).d("  > GmsBinderBridge: READY")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "  > GmsBinderBridge: FAILED")
        }

        // 4. Google Sign-In manager
        try {
            googleSignInManager.initialize(context)
            Timber.tag(TAG).d("  > VirtualGoogleSignInManager: READY")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "  > VirtualGoogleSignInManager: FAILED")
        }

        // 5. Firebase Auth proxy
        try {
            firebaseAuthProxy.initialize(context)
            Timber.tag(TAG).d("  > VirtualFirebaseAuthProxy: READY")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "  > VirtualFirebaseAuthProxy: FAILED")
        }

        // 6. FCM proxy
        try {
            fcmProxy.initialize(context)
            Timber.tag(TAG).d("  > GmsFcmProxy: READY")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "  > GmsFcmProxy: FAILED")
        }

        // 7. Play Store proxy
        try {
            playStoreProxy.initialize(context, identitySpoofer)
            Timber.tag(TAG).d("  > GmsPlayStoreProxy: READY")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "  > GmsPlayStoreProxy: FAILED")
        }

        initialized = true
        Timber.tag(TAG).i("Hybrid GMS Manager initialized (gms=$gmsAvailable, version=$gmsVersionName)")
    }

    // ====================================================================
    // GMS Service Routing (Hybrid Bridge)
    // ====================================================================

    /**
     * Route a GMS service call from a virtual app through the Hybrid bridge.
     *
     * This is the main entry point for all GMS API calls from guest apps.
     * Instead of installing GMS inside the VM, we tunnel to the host's real GMS.
     *
     * @param instanceId Virtual app instance
     * @param serviceAction GMS service action string
     * @param guestPackageName Guest app's real package name
     * @return Proxied IBinder for the GMS service
     */
    fun routeGmsServiceCall(
        instanceId: String,
        serviceAction: String,
        guestPackageName: String
    ): VmResult<IBinder?> {
        if (!isGmsAvailable()) {
            return VmResult.Error("Google Play Services not available on host device")
        }

        // Register identity mapping
        identitySpoofer.registerMapping(guestPackageName, instanceId)

        // Resolve service key
        val serviceKey = binderBridge.resolveServiceKey(serviceAction)
        if (serviceKey == null) {
            Timber.tag(TAG).w("Unknown GMS service action: $serviceAction")
            return VmResult.Error("Unknown GMS service: $serviceAction")
        }

        // Connect through the Hybrid Binder Bridge
        val binder = binderBridge.connectService(serviceKey, guestPackageName, instanceId)
        return if (binder != null) {
            Timber.tag(TAG).d("GMS service routed: $serviceAction -> $serviceKey (instance=$instanceId)")
            VmResult.Success(binder)
        } else {
            VmResult.Error("Failed to bridge GMS service: $serviceAction")
        }
    }

    // ====================================================================
    // Auth Intent Routing
    // ====================================================================

    /**
     * Route a Firebase Auth API call.
     */
    fun routeFirebaseAuthCall(
        instanceId: String,
        method: String,
        args: Bundle?
    ): Bundle? {
        return firebaseAuthProxy.interceptAuthCall(instanceId, method, args)
    }

    /**
     * Check if an intent is a GMS auth intent.
     */
    fun isAuthIntent(intent: Intent): Boolean {
        val action = intent.action ?: return false
        return action == ACTION_ACCOUNT_AUTH ||
                action == ACTION_AUTH_API ||
                googleSignInManager.isVirtualSignInIntent(intent)
    }

    // ====================================================================
    // GMS Availability
    // ====================================================================

    /**
     * Install the GmsCore_OpenSSL security provider.
     * Tries ProviderInstaller first (if guest app includes play-services-base),
     * then falls back to promoting the system Conscrypt provider to position 1.
     */
    private fun installGmsSecurityProvider(context: Context) {
        try {
            // Try Google's ProviderInstaller via reflection (no compile-time dependency)
            val installerClass = Class.forName("com.google.android.gms.security.ProviderInstaller")
            val installMethod = installerClass.getMethod("installIfNeeded", Context::class.java)
            installMethod.invoke(null, context.applicationContext)
            Timber.tag(TAG).d("  > GmsCore_OpenSSL: installed via ProviderInstaller")
        } catch (_: ClassNotFoundException) {
            // ProviderInstaller not in classpath — promote system Conscrypt
            promoteConscryptProvider()
        } catch (e: Exception) {
            // ProviderInstaller failed (GMS not responding, etc.) — promote Conscrypt
            Timber.tag(TAG).w("  > GmsCore_OpenSSL: ProviderInstaller failed (${e.cause?.message ?: e.message})")
            promoteConscryptProvider()
        }
    }

    private fun promoteConscryptProvider() {
        try {
            val conscrypt = java.security.Security.getProvider("Conscrypt")
            if (conscrypt != null) {
                // Move Conscrypt to position 1 for best SSL/TLS performance
                java.security.Security.removeProvider("Conscrypt")
                java.security.Security.insertProviderAt(conscrypt, 1)
                Timber.tag(TAG).d("  > GmsCore_OpenSSL: system Conscrypt promoted to primary")
            } else {
                Timber.tag(TAG).w("  > GmsCore_OpenSSL: not available (using system defaults)")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("  > GmsCore_OpenSSL: Conscrypt fallback failed (${e.message})")
        }
    }

    /**
     * Check if Google Play Services is available on the HOST device.
     * Note: GMS is never installed inside the VM — we always check the host.
     */
    fun isGmsAvailable(): Boolean {
        if (gmsAvailable != null) return gmsAvailable!!
        val context = appContext ?: return false
        detectGmsAvailability(context)
        return gmsAvailable ?: false
    }

    fun getGmsVersion(): Int {
        if (gmsVersionCode > 0) return gmsVersionCode
        val context = appContext ?: return -1
        detectGmsAvailability(context)
        return gmsVersionCode
    }

    fun getGmsVersionName(): String = gmsVersionName

    fun getGmsAvailabilityCode(): Int {
        val context = appContext ?: return GMS_MISSING
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(GMS_PACKAGE, 0)
            if (info == null) return GMS_MISSING
            @Suppress("DEPRECATION")
            val isEnabled = pm.getApplicationEnabledSetting(GMS_PACKAGE) !=
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (!isEnabled) return GMS_DISABLED
            GMS_AVAILABLE
        } catch (e: PackageManager.NameNotFoundException) {
            GMS_MISSING
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error checking GMS availability")
            GMS_INVALID
        }
    }

    // ====================================================================
    // Account Management (Hybrid — Virtual identity, Real GMS engine)
    // ====================================================================

    /**
     * Route an AccountManager.getAccountsByType() call from a guest app.
     * Returns only the virtual account for this instance.
     */
    fun routeAccountRequest(instanceId: String, accountType: String): GoogleAccountInfo? {
        val virtualAccount = accountManager.getAccount(instanceId) ?: return null
        if (accountType.isNotEmpty() && accountType != "com.google") return null

        return GoogleAccountInfo(
            email = virtualAccount.email,
            displayName = virtualAccount.displayName,
            photoUrl = virtualAccount.photoUrl,
            accountType = "com.google"
        )
    }

    /**
     * Set the Google Account for a virtual instance.
     * Called after successful Google Sign-In inside the VM.
     */
    fun setGoogleAccount(instanceId: String, accountInfo: GoogleAccountInfo) {
        accountManager.addAccount(
            instanceId = instanceId,
            email = accountInfo.email,
            displayName = accountInfo.displayName,
            photoUrl = accountInfo.photoUrl
        )

        // Store auth tokens if available
        if (accountInfo.authToken.isNotEmpty()) {
            accountManager.storeToken(instanceId, "oauth2", accountInfo.authToken)
        }
        if (accountInfo.idToken.isNotEmpty()) {
            accountManager.storeToken(instanceId, "id_token", accountInfo.idToken)
        }

        Timber.tag(TAG).d("Google account set for $instanceId: ${accountInfo.email}")
    }

    fun removeGoogleAccount(instanceId: String) {
        accountManager.removeAccount(instanceId)
        Timber.tag(TAG).d("Google account removed for $instanceId")
    }

    fun getAllGoogleAccounts(): Map<String, GoogleAccountInfo> {
        return accountManager.getAllAccounts().mapValues { (_, account) ->
            GoogleAccountInfo(
                email = account.email,
                displayName = account.displayName,
                photoUrl = account.photoUrl,
                accountType = "com.google"
            )
        }
    }

    // ====================================================================
    // FCM Push Notification Management
    // ====================================================================

    fun registerFcmToken(instanceId: String, guestPackageName: String, senderId: String): String? {
        return fcmProxy.registerToken(instanceId, guestPackageName, senderId)
    }

    fun getFcmToken(instanceId: String): String? = fcmProxy.getToken(instanceId)

    fun removeFcmToken(instanceId: String) {
        fcmProxy.unregisterToken(instanceId)
    }

    fun routeFcmMessage(data: Bundle): String? {
        return fcmProxy.routeIncomingMessage(data)
    }

    // ====================================================================
    // Play Billing
    // ====================================================================

    fun handlePlayBillingRequest(
        instanceId: String,
        method: String,
        args: Bundle?,
        guestPackageName: String
    ): Bundle {
        return playStoreProxy.executeBillingCall(method, args, guestPackageName, instanceId)
    }

    // ====================================================================
    // GMS Signature Spoofing
    // ====================================================================

    fun spoofGmsSignature(packageName: String): ByteArray {
        val cached = spoofedSignatures[packageName]
        if (cached != null) return cached

        val signature = generateGmsSignatureBytes()
        spoofedSignatures[packageName] = signature
        return signature
    }

    override fun isGmsPackage(packageName: String): Boolean {
        return packageName == GMS_PACKAGE ||
                packageName == GSF_PACKAGE ||
                packageName == PLAY_STORE_PACKAGE ||
                packageName.startsWith("com.google.android.gms")
    }

    // ====================================================================
    // GmsServiceRouter interface — connects ActivityManagerProxy to GMS bridge
    // ====================================================================

    override fun isGmsServiceIntent(intent: Intent): Boolean {
        val targetPkg = intent.component?.packageName ?: intent.`package`
        if (targetPkg != null && isGmsPackage(targetPkg)) return true
        val action = intent.action ?: return false
        return action == ACTION_PLAY_BILLING ||
                action == ACTION_AUTH_API ||
                action == ACTION_ACCOUNT_AUTH ||
                action == ACTION_MAPS ||
                action == ACTION_LOCATION ||
                action == ACTION_FITNESS ||
                action == ACTION_DRIVE ||
                action == ACTION_GAMES ||
                action == ACTION_CAST ||
                action == ACTION_ANALYTICS ||
                action == ACTION_ADS ||
                action == ACTION_SAFETYNET ||
                action == ACTION_FCM ||
                action.startsWith("com.google.android.gms.") ||
                action.startsWith("com.android.vending.") ||
                action.startsWith("com.google.firebase.") ||
                action.startsWith("com.google.android.chimera.") ||
                action.startsWith("com.google.android.finsky.") ||
                action.startsWith("com.google.android.c2dm.")
    }

    override fun routeGmsBindIntent(
        intent: Intent,
        instanceId: String,
        guestPackageName: String
    ): IBinder? {
        val action = intent.action

        // Play Billing — dedicated proxy
        if (action == ACTION_PLAY_BILLING) {
            return playStoreProxy.connectBillingService(guestPackageName, instanceId)
        }

        // Known GMS service action — route through Binder bridge
        if (action != null) {
            val serviceKey = binderBridge.resolveServiceKey(action)
            if (serviceKey != null) {
                identitySpoofer.registerMapping(guestPackageName, instanceId)
                return binderBridge.connectService(serviceKey, guestPackageName, instanceId)
            }
        }

        // Unknown GMS action — try direct action-based connection
        val targetPkg = intent.component?.packageName ?: intent.`package`
        if (targetPkg != null && isGmsPackage(targetPkg) && action != null) {
            Timber.tag(TAG).d("Generic GMS bind: $action (instance=$instanceId)")
            identitySpoofer.registerMapping(guestPackageName, instanceId)
            // Pass the original intent's component so explicit-component services
            // (like GMS chimera BoundBrokerService) can be bound directly
            return binderBridge.connectServiceByAction(
                action, guestPackageName, instanceId, intent.component
            )
        }

        return null
    }

    override fun interceptAuthIntent(intent: Intent, instanceId: String): Intent? {
        val signInResult = googleSignInManager.interceptSignInIntent(intent, instanceId)
        if (signInResult != null) {
            val virtualAccount = accountManager.getAccount(instanceId)
            if (virtualAccount != null) {
                val realToken = accountManager.handleGetAuthToken(
                    instanceId, virtualAccount.email, "oauth2:email profile"
                )
                if (realToken != null) {
                    signInResult.putExtra("auth_token", realToken)
                    signInResult.putExtra("_nextvm_has_real_token", true)
                }
            }
            return signInResult
        }
        return null
    }

    override fun getVirtualGoogleAccounts(instanceId: String): List<android.accounts.Account>? {
        val accounts = mutableListOf<android.accounts.Account>()

        // 1. Instance's own signed-in account (full isolation per instance)
        val instanceAccount = accountManager.getAccount(instanceId)
        if (instanceAccount != null) {
            accounts.add(android.accounts.Account(instanceAccount.email, instanceAccount.accountType))
        }

        // 2. Global accounts added via Settings (available to ALL virtual apps)
        val allAccounts = accountManager.getAllAccounts()
        for ((key, globalAccount) in allAccounts) {
            if (key.startsWith("global_") && accounts.none { it.name == globalAccount.email }) {
                accounts.add(android.accounts.Account(globalAccount.email, globalAccount.accountType))
            }
        }

        // Non-empty → return isolated+global accounts; null → fall back to host for sign-in flow
        return accounts.ifEmpty { null }
    }

    override fun synthesizeGmsPackageInfo(packageName: String): PackageInfo? {
        if (!isGmsPackage(packageName)) return null
        val context = appContext ?: return null

        return try {
            val pm = context.packageManager
            @Suppress("DEPRECATION", "PackageManagerGetSignatures")
            val realInfo = pm.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNATURES or PackageManager.GET_META_DATA
            )
            realInfo
        } catch (e: PackageManager.NameNotFoundException) {
            Timber.tag(TAG).d("GMS package $packageName not on host — synthesizing minimal PackageInfo")
            PackageInfo().apply {
                this.packageName = packageName
                versionName = "24.34.31"
                @Suppress("DEPRECATION")
                versionCode = 243431006
                applicationInfo = android.content.pm.ApplicationInfo().apply {
                    this.packageName = packageName
                    enabled = true
                    flags = android.content.pm.ApplicationInfo.FLAG_INSTALLED or
                            android.content.pm.ApplicationInfo.FLAG_SYSTEM
                    metaData = android.os.Bundle()
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error synthesizing GMS PackageInfo for $packageName")
            null
        }
    }

    fun applyGmsSignatureSpoof(packageInfo: Any, packageName: String): Boolean {
        if (!isGmsPackage(packageName)) return false
        return try {
            val signatureBytes = spoofGmsSignature(packageName)
            val signatureClass = Class.forName("android.content.pm.Signature")
            val constructor = signatureClass.getConstructor(ByteArray::class.java)
            val signature = constructor.newInstance(signatureBytes)

            val sigField = findField(packageInfo::class.java, "signatures")
            if (sigField != null) {
                sigField.isAccessible = true
                val sigArray = java.lang.reflect.Array.newInstance(signatureClass, 1)
                java.lang.reflect.Array.set(sigArray, 0, signature)
                sigField.set(packageInfo, sigArray)
                true
            } else false
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to apply GMS signature for $packageName")
            false
        }
    }

    // ====================================================================
    // Maps SDK
    // ====================================================================

    fun isMapsAvailable(): Boolean {
        val context = appContext ?: return false
        return try {
            val intent = Intent(ACTION_MAPS).apply { setPackage(GMS_PACKAGE) }
            context.packageManager.queryIntentServices(intent, 0).isNotEmpty()
        } catch (e: Exception) { false }
    }

    fun getHostMapsApiKey(): String? {
        val context = appContext ?: return null
        return try {
            val appInfo = context.packageManager.getApplicationInfo(
                context.packageName, PackageManager.GET_META_DATA
            )
            appInfo.metaData?.getString("com.google.android.geo.API_KEY")
                ?: appInfo.metaData?.getString("com.google.android.maps.v2.API_KEY")
        } catch (e: Exception) { null }
    }

    // ====================================================================
    // Service Discovery
    // ====================================================================

    fun isServiceAvailable(serviceAction: String): Boolean {
        val cached = serviceAvailability[serviceAction]
        if (cached != null) return cached

        val context = appContext ?: return false
        val available = try {
            val intent = Intent(serviceAction).apply { setPackage(GMS_PACKAGE) }
            context.packageManager.queryIntentServices(intent, 0).isNotEmpty()
        } catch (e: Exception) { false }

        serviceAvailability[serviceAction] = available
        return available
    }

    fun getServiceReport(): Map<String, Boolean> {
        val services = listOf(
            "FCM" to ACTION_FCM,
            "Auth" to ACTION_AUTH_API,
            "Location" to ACTION_LOCATION,
            "Maps" to ACTION_MAPS,
            "Billing" to ACTION_PLAY_BILLING,
            "Analytics" to ACTION_ANALYTICS,
            "Ads" to ACTION_ADS,
            "SafetyNet" to ACTION_SAFETYNET,
            "Drive" to ACTION_DRIVE,
            "Games" to ACTION_GAMES,
            "Cast" to ACTION_CAST,
            "Fitness" to ACTION_FITNESS
        )
        return services.associate { (name, action) -> name to isServiceAvailable(action) }
    }

    /**
     * Get a comprehensive Hybrid GMS status report.
     */
    fun getHybridStatusReport(): Map<String, Any> {
        return mapOf(
            "gms_available" to (gmsAvailable == true),
            "gms_version" to gmsVersionName,
            "bridge_initialized" to binderBridge.isInitialized(),
            "bridge_connected_services" to binderBridge.getConnectedServices(),
            "virtual_accounts" to accountManager.getAllAccounts().size,
            "host_google_accounts" to accountManager.getHostGoogleAccounts().size,
            "play_store_available" to playStoreProxy.isPlayStoreAvailable(),
            "play_store_version" to (playStoreProxy.getPlayStoreVersion() ?: "N/A"),
            "service_availability" to getServiceReport()
        )
    }

    // ====================================================================
    // Cleanup
    // ====================================================================

    fun cleanupInstance(instanceId: String) {
        binderBridge.disconnectAllForInstance(instanceId)
        accountManager.cleanup(instanceId)
        fcmProxy.cleanup(instanceId)
        playStoreProxy.cleanup(instanceId)
        googleSignInManager.cleanup(instanceId)
        firebaseAuthProxy.cleanup(instanceId)
        identitySpoofer.cleanup(instanceId.substringBefore("_"))
        Timber.tag(TAG).d("Hybrid GMS state cleaned up for $instanceId")
    }

    fun cleanupAll() {
        binderBridge.disconnectAll()
        accountManager.cleanupAll()
        fcmProxy.cleanupAll()
        playStoreProxy.cleanupAll()
        spoofedSignatures.clear()
        serviceAvailability.clear()
        initialized = false
        Timber.tag(TAG).i("Hybrid GMS Manager cleaned up")
    }

    fun shutdown() {
        binderBridge.shutdown()
        accountManager.shutdown()
        fcmProxy.shutdown()
        playStoreProxy.shutdown()
        identitySpoofer.shutdown()
        cleanupAll()
        Timber.tag(TAG).i("Hybrid GMS Manager shut down")
    }

    // ====================================================================
    // Internal helpers
    // ====================================================================

    private fun detectGmsAvailability(context: Context) {
        try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(GMS_PACKAGE, 0)
            if (info != null) {
                gmsAvailable = true
                gmsVersionName = info.versionName ?: "unknown"
                @Suppress("DEPRECATION")
                gmsVersionCode = info.versionCode
                Timber.tag(TAG).d("Host GMS detected: version=$gmsVersionName")
            } else {
                gmsAvailable = false
            }
        } catch (e: PackageManager.NameNotFoundException) {
            gmsAvailable = false
            Timber.tag(TAG).d("GMS not installed on host device")
        } catch (e: Exception) {
            gmsAvailable = false
            Timber.tag(TAG).e(e, "Error detecting host GMS")
        }
    }

    private fun generateGmsSignatureBytes(): ByteArray {
        return try {
            val context = appContext ?: return ByteArray(0)
            val pm = context.packageManager
            @Suppress("DEPRECATION", "PackageManagerGetSignatures")
            val info = pm.getPackageInfo(GMS_PACKAGE, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info?.signatures?.firstOrNull()?.toByteArray() ?: ByteArray(0)
        } catch (e: Exception) {
            Timber.tag(TAG).w("Could not extract GMS signature: ${e.message}")
            ByteArray(0)
        }
    }
}

// ========================================================================
// Data classes
// ========================================================================

data class GoogleAccountInfo(
    val email: String,
    val displayName: String = "",
    val photoUrl: String = "",
    val accountType: String = "com.google",
    val authToken: String = "",
    val idToken: String = ""
)

data class BillingResult(
    val responseCode: Int,
    val debugMessage: String = ""
) {
    companion object {
        const val OK = 0
        const val USER_CANCELED = 1
        const val SERVICE_UNAVAILABLE = 2
        const val BILLING_UNAVAILABLE = 3
        const val ITEM_UNAVAILABLE = 4
        const val DEVELOPER_ERROR = 5
        const val ERROR = 6
        const val ITEM_ALREADY_OWNED = 7
        const val ITEM_NOT_OWNED = 8
    }

    val isSuccess: Boolean get() = responseCode == OK
}

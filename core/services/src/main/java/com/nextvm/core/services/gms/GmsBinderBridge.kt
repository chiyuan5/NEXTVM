package com.nextvm.core.services.gms

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.IInterface
import android.os.Message
import android.os.Parcel
import com.nextvm.core.common.runSafe
import timber.log.Timber
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GmsBinderBridge — The core Binder-level tunnel between guest apps and host GMS.
 *
 * HYBRID GMS ARCHITECTURE:
 * ┌─────────────┐     ┌──────────────────┐     ┌─────────────────┐
 * │ Guest App   │ ──> │ GmsBinderBridge  │ ──> │ Host GMS        │
 * │ (in VM)     │     │ (proxy layer)    │     │ (real process)  │
 * └─────────────┘     └──────────────────┘     └─────────────────┘
 *
 * When a guest app calls any GMS API (GoogleApiClient.connect(), Firebase, etc.),
 * the call goes through Binder IPC. We intercept this at the Binder level:
 *
 * 1. Guest app calls GMS service via Binder
 * 2. GmsBinderBridge intercepts the call
 * 3. Rewrites callingPackage to host app's package (so GMS trusts us)
 * 4. Forwards to host device's real GMS process
 * 5. Receives response from real GMS
 * 6. Rewrites response (restore guest package identity where needed)
 * 7. Returns to guest app
 *
 * This means GMS is NEVER installed inside the VM — all GMS power
 * comes from the host device's already-installed Google Play Services.
 */
@Singleton
class GmsBinderBridge @Inject constructor() {

    companion object {
        private const val TAG = "GmsBridge"

        const val GMS_PACKAGE = "com.google.android.gms"
        const val GSF_PACKAGE = "com.google.android.gsf"
        const val PLAY_STORE_PACKAGE = "com.android.vending"

        // Well-known GMS Binder service actions
        val GMS_SERVICE_ACTIONS = mapOf(
            "auth" to "com.google.android.gms.auth.service.START",
            "auth_api" to "com.google.android.gms.auth.api.signin.internal.SIGN_IN",
            "auth_api_signin" to "com.google.android.gms.auth.api.signin.service.START",
            "account" to "com.google.android.gms.auth.account.WorkAccountService.LOGIN",
            "firebase_auth" to "com.google.firebase.auth.api.gms.service.START",
            "firebase_iid" to "com.google.firebase.iid.FirebaseInstanceIdService",
            "firebase_auth_interop" to "com.google.android.gms.auth.api.phone.SMS_RETRIEVER",
            "play_billing" to "com.android.vending.billing.InAppBillingService.BIND",
            "fcm" to "com.google.firebase.MESSAGING_EVENT",
            "location" to "com.google.android.gms.location.internal.IGoogleLocationManagerService",
            "maps" to "com.google.android.gms.maps.internal.ICreator",
            "analytics" to "com.google.android.gms.analytics.internal.IAnalyticsService",
            "ads" to "com.google.android.gms.ads.identifier.service.START",
            "safetynet" to "com.google.android.gms.safetynet.internal.ISafetyNetService",
            "safetynet_start" to "com.google.android.gms.safetynet.service.START",
            "play_integrity" to "com.google.android.play.core.integrity.BIND",
            "drive" to "com.google.android.gms.drive.internal.IDriveService",
            "games" to "com.google.android.gms.games.internal.IGamesService",
            "games_start" to "com.google.android.gms.games.service.START",
            "games_connect" to "com.google.android.gms.games.internal.connect.service.START",
            "games_appshortcuts" to "com.google.android.gms.games.internal.appshortcuts.service.START",
            "cast" to "com.google.android.gms.cast.internal.ICastDeviceControllerService",
            "fitness" to "com.google.android.gms.fitness.internal.IGoogleFitService",
            "people" to "com.google.android.gms.people.internal.IPeopleService",
            "phenotype" to "com.google.android.gms.phenotype.internal.IPhenotypeService",
            "phenotype_start" to "com.google.android.gms.phenotype.service.START",
            "clearcut" to "com.google.android.gms.clearcut.internal.IClearcutLoggerService",
            "gass" to "com.google.android.gms.gass.START",
            "measurement" to "com.google.android.gms.measurement.internal.IMeasurementService",
            "measurement_start" to "com.google.android.gms.measurement.START",
            "c2dm_register" to "com.google.android.c2dm.intent.REGISTER",
            "usage_reporting" to "com.google.android.gms.usagereporting.service.START",
            "install_referrer" to "com.google.android.finsky.BIND_GET_INSTALL_REFERRER_SERVICE"
        )

        private const val BIND_TIMEOUT_MS = 8000L

        // Chimera meta-actions that don't have intent-filters and require
        // explicit component binding to GmsBoundBrokerService
        val CHIMERA_ACTIONS = setOf(
            "com.google.android.chimera.BoundService.START",
            "com.google.android.chimera.container.FileApkService.START",
            "com.google.android.chimera.PersistentBoundBrokerService.START"
        )
    }

    private var appContext: Context? = null
    private var hostPackageName: String = ""
    private var initialized = false

    // Active GMS service connections: serviceKey -> BridgedService
    private val bridgedServices = ConcurrentHashMap<String, BridgedService>()

    // Package identity spoofer reference (set during initialization)
    private var identitySpoofer: GmsPackageIdentitySpoofer? = null

    /**
     * Initialize the Binder bridge.
     */
    fun initialize(context: Context, spoofer: GmsPackageIdentitySpoofer) {
        appContext = context.applicationContext
        hostPackageName = context.packageName
        identitySpoofer = spoofer
        initialized = true
        Timber.tag(TAG).i("GmsBinderBridge initialized (host=$hostPackageName)")
    }

    /**
     * Connect to a GMS service and return a proxied Binder that guest apps can use.
     *
     * @param serviceKey Service identifier (e.g., "auth", "location", "maps")
     * @param guestPackageName The guest app's real package name
     * @param instanceId The virtual app instance ID
     * @return Proxied IBinder, or null if connection failed
     */
    fun connectService(
        serviceKey: String,
        guestPackageName: String,
        instanceId: String
    ): IBinder? {
        val context = appContext ?: return null

        val action = GMS_SERVICE_ACTIONS[serviceKey]
        if (action == null) {
            Timber.tag(TAG).w("Unknown GMS service key: $serviceKey")
            return null
        }

        // Check if already connected
        val existing = bridgedServices[serviceKey]
        if (existing != null && existing.isConnected()) {
            Timber.tag(TAG).d("Reusing existing bridge for: $serviceKey")
            return existing.getProxiedBinder(guestPackageName, instanceId)
        }

        // Bind to the real GMS service on the host device
        val bridged = BridgedService(serviceKey, action, guestPackageName, instanceId)

        val intent = Intent(action).apply {
            setPackage(GMS_PACKAGE)
        }

        // Verify the service exists before binding
        val resolved = context.packageManager.queryIntentServices(intent, 0)
        if (resolved.isEmpty()) {
            // Some services target the Play Store
            intent.setPackage(PLAY_STORE_PACKAGE)
            val playResolved = context.packageManager.queryIntentServices(intent, 0)
            if (playResolved.isEmpty()) {
                Timber.tag(TAG).w("GMS service not available on host: $serviceKey ($action)")
                return null
            }
        }

        return try {
            val bound = context.bindService(intent, bridged.connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                Timber.tag(TAG).w("Failed to bind GMS service: $serviceKey")
                return null
            }

            // Wait for connection
            val binder = bridged.waitForBinder(BIND_TIMEOUT_MS)
            if (binder == null) {
                Timber.tag(TAG).w("GMS service connection timeout: $serviceKey")
                try { context.unbindService(bridged.connection) } catch (_: Exception) {}
                return null
            }

            bridgedServices[serviceKey] = bridged
            Timber.tag(TAG).i("GMS service bridged: $serviceKey")

            bridged.getProxiedBinder(guestPackageName, instanceId)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error connecting GMS service: $serviceKey")
            null
        }
    }

    /**
     * Connect to a GMS service using a direct action string (for dynamic/unknown services).
     * Unlike connectService() which looks up the action from a key, this uses the action directly.
     *
     * @param component Optional explicit ComponentName for services that don't have
     *                  intent-filters (e.g., GMS chimera BoundBrokerService).
     */
    fun connectServiceByAction(
        action: String,
        guestPackageName: String,
        instanceId: String,
        component: ComponentName? = null
    ): IBinder? {
        val context = appContext ?: return null

        // Use component or action hash as cache key
        val cacheKey = if (component != null) {
            "component_${component.flattenToShortString().hashCode()}"
        } else {
            "dynamic_${action.hashCode()}"
        }

        // Check if already connected
        val existing = bridgedServices[cacheKey]
        if (existing != null && existing.isConnected()) {
            Timber.tag(TAG).d("Reusing existing bridge for: $cacheKey")
            return existing.getProxiedBinder(guestPackageName, instanceId)
        }

        val intent = Intent(action)
        if (component != null) {
            // Explicit component binding (e.g., chimera GmsBoundBrokerService)
            intent.component = component
        } else {
            intent.setPackage(GMS_PACKAGE)
        }

        // If using explicit component, skip intent-filter resolution check
        if (component == null) {
            val resolved = context.packageManager.queryIntentServices(intent, 0)
            if (resolved.isEmpty()) {
                intent.setPackage(PLAY_STORE_PACKAGE)
                val playResolved = context.packageManager.queryIntentServices(intent, 0)
                if (playResolved.isEmpty()) {
                    // Chimera fallback: services using BoundService.START don't have
                    // intent-filters — they must be bound via explicit component
                    if (CHIMERA_ACTIONS.contains(action)) {
                        Timber.tag(TAG).d("Chimera fallback for action: $action")
                        val chimeraComponent = ComponentName(
                            GMS_PACKAGE,
                            "com.google.android.chimera.GmsBoundBrokerService"
                        )
                        return connectServiceByAction(
                            action, guestPackageName, instanceId, chimeraComponent
                        )
                    }
                    Timber.tag(TAG).w("GMS service not available for action: $action")
                    return null
                }
            }
        }

        return try {
            val bridged = BridgedService(cacheKey, action, guestPackageName, instanceId)
            val bound = context.bindService(intent, bridged.connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                Timber.tag(TAG).w("Failed to bind GMS action: $action")
                return null
            }

            val binder = bridged.waitForBinder(BIND_TIMEOUT_MS)
            if (binder == null) {
                Timber.tag(TAG).w("GMS action connection timeout: $action")
                try { context.unbindService(bridged.connection) } catch (_: Exception) {}
                return null
            }

            bridgedServices[cacheKey] = bridged
            Timber.tag(TAG).i("GMS service bridged by action: $action")

            bridged.getProxiedBinder(guestPackageName, instanceId)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error connecting GMS action: $action")
            null
        }
    }

    /**
     * Disconnect a specific GMS service.
     */
    fun disconnectService(serviceKey: String) {
        val context = appContext ?: return
        val bridged = bridgedServices.remove(serviceKey) ?: return
        try {
            context.unbindService(bridged.connection)
            Timber.tag(TAG).d("GMS service disconnected: $serviceKey")
        } catch (_: Exception) {}
    }

    /**
     * Disconnect all GMS services for a specific instance.
     */
    fun disconnectAllForInstance(instanceId: String) {
        val context = appContext ?: return
        val toRemove = bridgedServices.entries.filter { it.value.instanceId == instanceId }
        toRemove.forEach { (key, bridged) ->
            try {
                context.unbindService(bridged.connection)
                bridgedServices.remove(key)
            } catch (_: Exception) {}
        }
        Timber.tag(TAG).d("All GMS services disconnected for instance: $instanceId")
    }

    /**
     * Disconnect all GMS services.
     */
    fun disconnectAll() {
        val context = appContext ?: return
        bridgedServices.forEach { (key, bridged) ->
            try {
                context.unbindService(bridged.connection)
            } catch (_: Exception) {}
        }
        bridgedServices.clear()
        Timber.tag(TAG).d("All GMS services disconnected")
    }

    /**
     * Check if a specific GMS service is currently connected.
     */
    fun isServiceConnected(serviceKey: String): Boolean {
        return bridgedServices[serviceKey]?.isConnected() == true
    }

    /**
     * Get the list of currently bridged services.
     */
    fun getConnectedServices(): Set<String> {
        return bridgedServices.keys.toSet()
    }

    /**
     * Check if a service action belongs to GMS.
     */
    fun isGmsServiceAction(action: String?): Boolean {
        if (action == null) return false
        return GMS_SERVICE_ACTIONS.values.any { it == action } ||
                action.contains("com.google.android.gms") ||
                action.contains("com.google.firebase")
    }

    /**
     * Resolve a GMS service action to a service key.
     */
    fun resolveServiceKey(action: String): String? {
        return GMS_SERVICE_ACTIONS.entries.firstOrNull { it.value == action }?.key
    }

    fun isInitialized(): Boolean = initialized

    fun shutdown() {
        disconnectAll()
        initialized = false
        Timber.tag(TAG).i("GmsBinderBridge shut down")
    }

    // ========================================================================
    // BridgedService — manages a single GMS service connection + proxy
    // ========================================================================

    /**
     * Wraps a bound GMS service connection with Binder-level proxying.
     * Intercepts all IPC transactions to rewrite package identity.
     */
    inner class BridgedService(
        val serviceKey: String,
        val action: String,
        val guestPackageName: String,
        val instanceId: String
    ) {
        @Volatile private var realBinder: IBinder? = null
        private val latch = CountDownLatch(1)

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                realBinder = service
                latch.countDown()
                Timber.tag(TAG).d("Real GMS connected: $serviceKey (${name?.className})")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                realBinder = null
                Timber.tag(TAG).d("Real GMS disconnected: $serviceKey")
            }
        }

        fun waitForBinder(timeoutMs: Long): IBinder? {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return realBinder
        }

        fun isConnected(): Boolean = realBinder != null && realBinder!!.isBinderAlive

        /**
         * Create a proxy IBinder that intercepts transactions to rewrite
         * package identity before forwarding to the real GMS binder.
         */
        fun getProxiedBinder(guestPkg: String, instId: String): IBinder? {
            val real = realBinder ?: return null
            return GmsBinderProxy(real, guestPkg, instId, this@GmsBinderBridge)
        }
    }

    // ========================================================================
    // GmsBinderProxy — intercepts Binder transactions at the IPC level
    // ========================================================================

    /**
     * Proxy IBinder that wraps a real GMS service Binder.
     * Intercepts transact() calls to rewrite package identity fields.
     */
    class GmsBinderProxy(
        private val realBinder: IBinder,
        private val guestPackageName: String,
        private val instanceId: String,
        private val bridge: GmsBinderBridge
    ) : IBinder {

        override fun getInterfaceDescriptor(): String? = realBinder.interfaceDescriptor

        override fun pingBinder(): Boolean = realBinder.pingBinder()

        override fun isBinderAlive(): Boolean = realBinder.isBinderAlive

        override fun queryLocalInterface(descriptor: String): IInterface? {
            // Return null to force remote Binder path — this ensures our
            // transact() proxy is always invoked
            return null
        }

        override fun dump(fd: java.io.FileDescriptor, args: Array<out String>?) {
            realBinder.dump(fd, args)
        }

        override fun dumpAsync(fd: java.io.FileDescriptor, args: Array<out String>?) {
            realBinder.dumpAsync(fd, args)
        }

        /**
         * The core interception point — all Binder IPC goes through here.
         *
         * We rewrite the Parcel data to replace the guest app's package name
         * with the host app's package name, so GMS thinks the call is from
         * our NEXTVM app (which it trusts because it's actually installed).
         *
         * We also rewrite ANY other registered virtual package names found in
         * the data, since shared GMS broker connections may carry package names
         * from different virtual apps than the one this proxy was configured for.
         */
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val spoofer = bridge.identitySpoofer
            if (spoofer != null) {
                // Messenger IPC (used by FCM): rewrite at Message/Bundle level
                // to avoid corrupting Bundle length headers in raw Parcel bytes
                val descriptor = try { realBinder.interfaceDescriptor } catch (_: Exception) { null }
                if (descriptor == "android.os.IMessenger") {
                    return handleMessengerTransact(code, data, reply, flags, spoofer)
                }

                // Regular Binder IPC — use Parcel-level rewriting
                spoofer.rewriteParcelPackageName(data, guestPackageName, bridge.hostPackageName)
                for (otherPkg in spoofer.getRegisteredGuestPackages()) {
                    if (otherPkg != guestPackageName) {
                        spoofer.rewriteParcelPackageName(data, otherPkg, bridge.hostPackageName)
                    }
                }
            }

            // Forward to real GMS
            val result = realBinder.transact(code, data, reply, flags)

            // Rewrite response to restore guest package identity where needed
            if (reply != null && spoofer != null) {
                spoofer.restoreParcelPackageName(reply, bridge.hostPackageName, guestPackageName)
            }

            return result
        }

        /**
         * Handle Messenger IPC by deserializing the Message, rewriting package
         * names in the Bundle at Java object level, then re-serializing.
         * This preserves Bundle length headers that raw Parcel byte replacement breaks.
         */
        private fun handleMessengerTransact(
            code: Int, data: Parcel, reply: Parcel?, flags: Int,
            spoofer: GmsPackageIdentitySpoofer
        ): Boolean {
            try {
                data.setDataPosition(0)
                data.enforceInterface("android.os.IMessenger")
                val msg = Message.CREATOR.createFromParcel(data)

                // Rewrite both msg.peekData() (the data Bundle) and obj if Bundle
                msg.peekData()?.let { bundle ->
                    bundle.keySet() // force unparcel
                    spoofer.rewriteBundlePackageName(bundle, guestPackageName, bridge.hostPackageName)
                    for (otherPkg in spoofer.getRegisteredGuestPackages()) {
                        if (otherPkg != guestPackageName) {
                            spoofer.rewriteBundlePackageName(bundle, otherPkg, bridge.hostPackageName)
                        }
                    }
                }

                // Rebuild Parcel with properly serialized Message
                val newData = Parcel.obtain()
                try {
                    newData.writeInterfaceToken("android.os.IMessenger")
                    msg.writeToParcel(newData, 0)
                    newData.setDataPosition(0)

                    Timber.tag(TAG).d("Messenger IPC rewrite: $guestPackageName -> ${bridge.hostPackageName}")
                    val result = realBinder.transact(code, newData, reply, flags)
                    msg.recycle()
                    return result
                } finally {
                    newData.recycle()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).d("Messenger rewrite failed, Parcel fallback: ${e.message}")
                // Fallback to regular Parcel-level rewriting
                data.setDataPosition(0)
                spoofer.rewriteParcelPackageName(data, guestPackageName, bridge.hostPackageName)
                for (otherPkg in spoofer.getRegisteredGuestPackages()) {
                    if (otherPkg != guestPackageName) {
                        spoofer.rewriteParcelPackageName(data, otherPkg, bridge.hostPackageName)
                    }
                }
                return realBinder.transact(code, data, reply, flags)
            }
        }

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            realBinder.linkToDeath(recipient, flags)
        }

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
            return realBinder.unlinkToDeath(recipient, flags)
        }
    }
}

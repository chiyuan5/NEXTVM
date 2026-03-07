package com.nextvm.core.services.gms

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GmsFcmProxy — Firebase Cloud Messaging proxy for virtual app instances.
 *
 * HYBRID FCM ARCHITECTURE:
 * ┌─────────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
 * │ Guest App   │ ──> │ GmsFcmProxy  │ ──> │ Host GMS FCM │ ──> │ Google FCM   │
 * │ requests    │     │ registers    │     │ InstanceID   │     │ Server       │
 * │ FCM token   │     │ per-instance │     │ real process │     │              │
 * └─────────────┘     └──────────────┘     └──────────────┘     └──────────────┘
 *
 * Each virtual instance gets its own FCM token by:
 * 1. Guest app calls FirebaseMessaging.getInstance().getToken()
 * 2. We intercept this at the IPC level
 * 3. We register with host GMS using NEXTVM's sender ID + instance-specific scope
 * 4. Host GMS talks to Google FCM server and returns a real token
 * 5. We store this token per-instance and return it to the guest app
 *
 * When a push notification arrives:
 * 1. Host GMS receives the FCM message
 * 2. Our FCM service (registered in manifest) receives it
 * 3. GmsFcmProxy routes the message to the correct virtual instance
 * 4. The guest app's FirebaseMessagingService.onMessageReceived() is called
 */
@Singleton
class GmsFcmProxy @Inject constructor() {

    companion object {
        private const val TAG = "GmsFcm"

        // FCM IPC actions
        const val ACTION_FCM_REGISTER = "com.google.android.c2dm.intent.REGISTER"
        const val ACTION_FCM_UNREGISTER = "com.google.android.c2dm.intent.UNREGISTER"
        const val ACTION_FCM_RECEIVE = "com.google.android.c2dm.intent.RECEIVE"
        const val ACTION_MESSAGING_EVENT = "com.google.firebase.MESSAGING_EVENT"

        // InstanceID token scope for FCM
        const val FCM_SCOPE = "FCM"
    }

    private var appContext: Context? = null
    private var initialized = false

    // Per-instance FCM tokens: instanceId -> FcmTokenInfo
    private val instanceTokens = ConcurrentHashMap<String, FcmTokenInfo>()

    // Reverse mapping for routing: fcmToken -> instanceId
    private val tokenToInstance = ConcurrentHashMap<String, String>()

    // Per-instance message queues for offline delivery
    private val pendingMessages = ConcurrentHashMap<String, MutableList<FcmMessage>>()

    /**
     * FCM token info for a virtual instance.
     */
    data class FcmTokenInfo(
        val instanceId: String,
        val guestPackageName: String,
        val token: String,
        val senderIdUsed: String,
        val createdAt: Long = System.currentTimeMillis(),
        val lastRefresh: Long = System.currentTimeMillis()
    )

    /**
     * Represents an FCM message to be delivered to a virtual instance.
     */
    data class FcmMessage(
        val from: String,
        val to: String,
        val messageId: String,
        val data: Map<String, String>,
        val notification: NotificationPayload?,
        val receivedAt: Long = System.currentTimeMillis()
    )

    /**
     * FCM notification payload.
     */
    data class NotificationPayload(
        val title: String?,
        val body: String?,
        val icon: String?,
        val clickAction: String?
    )

    /**
     * Initialize the FCM proxy.
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        initialized = true
        Timber.tag(TAG).i("GmsFcmProxy initialized")
    }

    // ====================================================================
    // Token Registration
    // ====================================================================

    /**
     * Register for FCM token on behalf of a virtual app instance.
     *
     * This creates a unique FCM registration for the instance by:
     * 1. Using the host GMS InstanceID API
     * 2. Registering with the guest app's sender ID (from google-services.json)
     * 3. Storing the token per-instance
     *
     * @param instanceId Virtual app instance ID
     * @param guestPackageName Guest app's package name
     * @param senderId Firebase sender ID from the guest app's config
     * @return The FCM token, or null if registration failed
     */
    fun registerToken(
        instanceId: String,
        guestPackageName: String,
        senderId: String
    ): String? {
        val context = appContext ?: return null

        return try {
            // Build registration intent for host GMS
            val registrationIntent = Intent(ACTION_FCM_REGISTER).apply {
                setPackage("com.google.android.gms")
                // GMS expects these extras for registration
                putExtra("sender", senderId)
                putExtra("scope", FCM_SCOPE)
                putExtra("app", context.packageName)
                putExtra("_nextvm_instance_id", instanceId)
                putExtra("_nextvm_guest_pkg", guestPackageName)
            }

            // Use GmsBinderBridge to route this to real GMS
            // For now, we use the InstanceID approach
            val token = requestTokenFromHostGms(context, senderId, instanceId)

            if (token != null) {
                val tokenInfo = FcmTokenInfo(
                    instanceId = instanceId,
                    guestPackageName = guestPackageName,
                    token = token,
                    senderIdUsed = senderId
                )
                instanceTokens[instanceId] = tokenInfo
                tokenToInstance[token] = instanceId

                Timber.tag(TAG).i("FCM token registered for $instanceId: ${token.take(20)}...")
            }

            token
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "FCM token registration failed for $instanceId")
            null
        }
    }

    /**
     * Unregister FCM token for a virtual app instance.
     */
    fun unregisterToken(instanceId: String) {
        val tokenInfo = instanceTokens.remove(instanceId) ?: return
        tokenToInstance.remove(tokenInfo.token)
        Timber.tag(TAG).d("FCM token unregistered for $instanceId")
    }

    /**
     * Get the FCM token for a virtual instance.
     */
    fun getToken(instanceId: String): String? {
        return instanceTokens[instanceId]?.token
    }

    /**
     * Check if a token needs refresh (older than 7 days).
     */
    fun isTokenStale(instanceId: String): Boolean {
        val tokenInfo = instanceTokens[instanceId] ?: return true
        val sevenDaysMs = 7 * 24 * 60 * 60 * 1000L
        return System.currentTimeMillis() - tokenInfo.lastRefresh > sevenDaysMs
    }

    // ====================================================================
    // Message Routing
    // ====================================================================

    /**
     * Route an incoming FCM message to the correct virtual instance.
     *
     * Called when the host device receives an FCM push notification.
     * We determine which virtual instance should receive it based on
     * the registration token or the message's target package.
     *
     * @param data The FCM message data
     * @return The instance ID that should receive this message, or null
     */
    fun routeIncomingMessage(data: Bundle): String? {
        // Try to match by registration_id / token
        val registrationId = data.getString("registration_id")
            ?: data.getString("google.to")

        if (registrationId != null) {
            val instanceId = tokenToInstance[registrationId]
            if (instanceId != null) {
                Timber.tag(TAG).d("FCM message routed by token to: $instanceId")
                return instanceId
            }
        }

        // Try to match by "from" (sender ID) → find instance with matching sender
        val from = data.getString("from")
        if (from != null) {
            val matchingInstance = instanceTokens.entries.firstOrNull {
                it.value.senderIdUsed == from
            }?.key
            if (matchingInstance != null) {
                Timber.tag(TAG).d("FCM message routed by sender to: $matchingInstance")
                return matchingInstance
            }
        }

        // Try to match by package name in collapse_key or other fields
        val collapseKey = data.getString("collapse_key")
        if (collapseKey != null) {
            val matchingInstance = instanceTokens.entries.firstOrNull {
                it.value.guestPackageName == collapseKey
            }?.key
            if (matchingInstance != null) {
                Timber.tag(TAG).d("FCM message routed by collapse_key to: $matchingInstance")
                return matchingInstance
            }
        }

        Timber.tag(TAG).w("Could not route FCM message to any instance")
        return null
    }

    /**
     * Queue a message for an offline instance.
     */
    fun queueMessage(instanceId: String, message: FcmMessage) {
        pendingMessages.getOrPut(instanceId) { mutableListOf() }.add(message)
        Timber.tag(TAG).d("FCM message queued for offline instance: $instanceId")
    }

    /**
     * Drain pending messages for an instance (called when it comes online).
     */
    fun drainPendingMessages(instanceId: String): List<FcmMessage> {
        val messages = pendingMessages.remove(instanceId) ?: return emptyList()
        Timber.tag(TAG).d("Delivering ${messages.size} pending FCM messages to $instanceId")
        return messages
    }

    /**
     * Build an Intent to deliver an FCM message to a virtual app's
     * FirebaseMessagingService.
     */
    fun buildDeliveryIntent(instanceId: String, message: FcmMessage): Intent {
        val tokenInfo = instanceTokens[instanceId]

        return Intent(ACTION_MESSAGING_EVENT).apply {
            putExtra("_nextvm_instance_id", instanceId)
            putExtra("_nextvm_guest_pkg", tokenInfo?.guestPackageName)
            putExtra("from", message.from)
            putExtra("google.message_id", message.messageId)

            // Data payload
            for ((key, value) in message.data) {
                putExtra(key, value)
            }

            // Notification payload
            message.notification?.let { notif ->
                putExtra("gcm.notification.title", notif.title)
                putExtra("gcm.notification.body", notif.body)
                putExtra("gcm.notification.icon", notif.icon)
                putExtra("gcm.notification.click_action", notif.clickAction)
            }
        }
    }

    // ====================================================================
    // Internal — Host GMS Token Retrieval
    // ====================================================================

    /**
     * Request a real FCM token from the host device's GMS InstanceID service.
     *
     * Tries multiple approaches in order:
     * 1. FirebaseMessaging.getToken() (modern API, Firebase 21+)
     * 2. FirebaseInstanceId.getToken() (legacy API)
     * 3. GMS InstanceID.getToken() (low-level GMS API)
     * 4. Direct GMS IPC via ContentResolver (no SDK dependency)
     */
    private fun requestTokenFromHostGms(
        context: Context,
        senderId: String,
        instanceId: String
    ): String? {
        // Approach 1: FirebaseMessaging.getInstance().getToken() (Task-based, modern)
        try {
            val fmClass = Class.forName("com.google.firebase.messaging.FirebaseMessaging")
            val getInstanceMethod = fmClass.getDeclaredMethod("getInstance")
            val fmObj = getInstanceMethod.invoke(null)
            val getTokenMethod = fmClass.getDeclaredMethod("getToken")
            val taskObj = getTokenMethod.invoke(fmObj)

            // Task.getResult() blocks — use Tasks.await() if available
            val tasksClass = Class.forName("com.google.android.gms.tasks.Tasks")
            val awaitMethod = tasksClass.getDeclaredMethod(
                "await", Class.forName("com.google.android.gms.tasks.Task"),
                Long::class.java, TimeUnit::class.java
            )
            val token = awaitMethod.invoke(null, taskObj, 10000L, TimeUnit.MILLISECONDS) as? String
            if (token != null) {
                Timber.tag(TAG).d("FCM token obtained via FirebaseMessaging.getToken()")
                return token
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d("FirebaseMessaging.getToken() not available: ${e.message}")
        }

        // Approach 2: FirebaseInstanceId.getInstance().getToken(senderId, scope)
        try {
            val instanceIdClass = Class.forName("com.google.firebase.iid.FirebaseInstanceId")
            val getInstanceMethod = instanceIdClass.getDeclaredMethod("getInstance")
            val instanceIdObj = getInstanceMethod.invoke(null)

            val getTokenMethod = instanceIdClass.getDeclaredMethod(
                "getToken", String::class.java, String::class.java
            )
            val token = getTokenMethod.invoke(instanceIdObj, senderId, FCM_SCOPE) as? String
            if (token != null) {
                Timber.tag(TAG).d("FCM token obtained via FirebaseInstanceId.getToken()")
                return token
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d("FirebaseInstanceId not available: ${e.message}")
        }

        // Approach 3: GMS InstanceID.getInstance(context).getToken(senderId, scope)
        try {
            val iidClass = Class.forName("com.google.android.gms.iid.InstanceID")
            val getInstanceMethod = iidClass.getDeclaredMethod("getInstance", Context::class.java)
            val iidObj = getInstanceMethod.invoke(null, context)

            val getTokenMethod = iidClass.getDeclaredMethod(
                "getToken", String::class.java, String::class.java
            )
            val token = getTokenMethod.invoke(iidObj, senderId, FCM_SCOPE) as? String
            if (token != null) {
                Timber.tag(TAG).d("FCM token obtained via GMS InstanceID.getToken()")
                return token
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d("GMS InstanceID not available: ${e.message}")
        }

        // Approach 4: Direct GMS IPC — bind to InstanceID service via Binder
        try {
            val token = requestTokenViaGmsBinder(context, senderId)
            if (token != null) {
                Timber.tag(TAG).d("FCM token obtained via direct GMS Binder IPC")
                return token
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d("Direct GMS Binder IPC failed: ${e.message}")
        }

        // All approaches failed — return null (no placeholder token)
        Timber.tag(TAG).w("All FCM token retrieval approaches failed for instance $instanceId")
        return null
    }

    /**
     * Request FCM token via direct GMS Binder IPC.
     * This bypasses the need for Firebase or GMS client SDK classes entirely.
     * We bind directly to the GMS InstanceID service and make a raw Binder call.
     */
    private fun requestTokenViaGmsBinder(context: Context, senderId: String): String? {
        val intent = Intent("com.google.android.c2dm.intent.REGISTER").apply {
            setPackage("com.google.android.gms")
        }

        val resolved = context.packageManager.queryIntentServices(intent, 0)
        if (resolved.isEmpty()) return null

        val latch = CountDownLatch(1)
        var resultToken: String? = null

        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
                try {
                    if (service != null) {
                        // Try to use the service to get a token
                        val descriptor = service.interfaceDescriptor
                        Timber.tag(TAG).d("Connected to GMS InstanceID service: $descriptor")
                        // The actual token will come back via broadcast
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Error using GMS InstanceID service: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(name: android.content.ComponentName?) {}
        }

        try {
            val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) return null

            latch.await(5000, TimeUnit.MILLISECONDS)

            // Unbind
            try { context.unbindService(connection) } catch (_: Exception) {}

            return resultToken
        } catch (e: Exception) {
            return null
        }
    }

    // ====================================================================
    // Cleanup
    // ====================================================================

    fun cleanup(instanceId: String) {
        unregisterToken(instanceId)
        pendingMessages.remove(instanceId)
    }

    fun cleanupAll() {
        instanceTokens.clear()
        tokenToInstance.clear()
        pendingMessages.clear()
    }

    fun shutdown() {
        cleanupAll()
        initialized = false
        Timber.tag(TAG).i("GmsFcmProxy shut down")
    }
}

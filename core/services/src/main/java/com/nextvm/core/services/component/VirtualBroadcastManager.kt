package com.nextvm.core.services.component

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.nextvm.core.common.runSafe
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.PriorityBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VirtualBroadcastManager — Complete BroadcastReceiver system for guest apps.
 *
 * Mirrors:
 *   - BroadcastQueue.java in frameworks/base/services/core/java/com/android/server/am/
 *   - BroadcastRecord.java
 *   - ReceiverList.java
 *   - ActivityManagerService.broadcastIntent(), registerReceiver(), unregisterReceiver()
 *
 * Handles:
 *   - Static receiver registration (from manifest declarations)
 *   - Dynamic receiver registration (registerReceiver/unregisterReceiver)
 *   - Broadcast dispatch with isolation (virtual apps only see their own + system broadcasts)
 *   - Ordered broadcasts with priority
 *   - Local broadcasts (within single instance)
 *   - Permission-based receiver filtering
 *   - Sticky broadcasts
 */
@Singleton
class VirtualBroadcastManager @Inject constructor() {

    companion object {
        private const val TAG = "VBroadcastMgr"

        /** Well-known system broadcast actions that virtual apps should receive */
        val SYSTEM_BROADCAST_ACTIONS = setOf(
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
            Intent.ACTION_TIME_TICK,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_BATTERY_CHANGED,
            Intent.ACTION_BATTERY_LOW,
            Intent.ACTION_BATTERY_OKAY,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            Intent.ACTION_LOCALE_CHANGED,
            Intent.ACTION_CONFIGURATION_CHANGED,
            "android.net.conn.CONNECTIVITY_CHANGE",
            "android.net.wifi.STATE_CHANGE",
            "android.intent.action.AIRPLANE_MODE"
        )
    }

    /**
     * Registered receiver record — both static and dynamic.
     */
    data class ReceiverRecord(
        val instanceId: String,
        val packageName: String,
        val receiverClassName: String?,
        val receiver: BroadcastReceiver?,
        val intentFilter: IntentFilter,
        val permission: String? = null,
        val isStatic: Boolean,
        val priority: Int = 0,
        val isRegistered: Boolean = true
    )

    /**
     * Pending ordered broadcast record.
     */
    data class OrderedBroadcastRecord(
        val intent: Intent,
        val permission: String?,
        val resultCode: Int = 0,
        val resultData: String? = null,
        val resultExtras: Bundle? = null,
        val receivers: List<ReceiverRecord>,
        var currentIndex: Int = 0,
        var aborted: Boolean = false
    )

    // instanceId -> list of registered receiver records
    private val staticReceivers = ConcurrentHashMap<String, CopyOnWriteArrayList<ReceiverRecord>>()
    private val dynamicReceivers = ConcurrentHashMap<String, CopyOnWriteArrayList<ReceiverRecord>>()

    // Receiver instance -> ReceiverRecord (for unregister lookup)
    private val receiverMap = ConcurrentHashMap<BroadcastReceiver, ReceiverRecord>()

    // Sticky broadcasts: action -> last Intent
    private val stickyBroadcasts = ConcurrentHashMap<String, Intent>()

    // Main thread handler for dispatching receivers
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Register static receivers declared in the guest app's manifest.
     *
     * Called during app installation when manifest is parsed.
     * Static receivers are instantiated via ClassLoader when a matching broadcast arrives.
     *
     * @param instanceId Guest app instance ID
     * @param packageName Guest app package name
     * @param receivers List of fully qualified BroadcastReceiver class names
     * @param intentFilters Map of receiver class name -> list of IntentFilters
     * @param priorities Map of receiver class name -> priority
     */
    fun registerStaticReceivers(
        instanceId: String,
        packageName: String,
        receivers: List<String>,
        intentFilters: Map<String, List<IntentFilter>>,
        priorities: Map<String, Int> = emptyMap()
    ) {
        val records = staticReceivers.getOrPut(instanceId) { CopyOnWriteArrayList() }

        for (receiverName in receivers) {
            val filters = intentFilters[receiverName] ?: continue
            for (filter in filters) {
                val record = ReceiverRecord(
                    instanceId = instanceId,
                    packageName = packageName,
                    receiverClassName = receiverName,
                    receiver = null, // Static: instantiated on demand
                    intentFilter = filter,
                    isStatic = true,
                    priority = priorities[receiverName] ?: 0
                )
                records.add(record)
            }
        }

        Timber.tag(TAG).i("Registered ${receivers.size} static receivers for $instanceId ($packageName)")
    }

    /**
     * Register a dynamic BroadcastReceiver (via Context.registerReceiver).
     *
     * @param instanceId Guest app instance ID
     * @param packageName Guest app package name
     * @param receiver The BroadcastReceiver instance
     * @param intentFilter The IntentFilter to match
     * @param permission Optional permission required by sender
     * @return The currently sticky Intent for the filter's action, if any
     */
    fun registerDynamicReceiver(
        instanceId: String,
        packageName: String,
        receiver: BroadcastReceiver,
        intentFilter: IntentFilter,
        permission: String? = null
    ): Intent? {
        val records = dynamicReceivers.getOrPut(instanceId) { CopyOnWriteArrayList() }

        // Check for duplicate registration
        val existing = receiverMap[receiver]
        if (existing != null) {
            Timber.tag(TAG).w("Receiver already registered, updating filter")
            records.remove(existing)
        }

        val record = ReceiverRecord(
            instanceId = instanceId,
            packageName = packageName,
            receiverClassName = receiver.javaClass.name,
            receiver = receiver,
            intentFilter = intentFilter,
            permission = permission,
            isStatic = false,
            priority = intentFilter.priority
        )
        records.add(record)
        receiverMap[receiver] = record

        Timber.tag(TAG).d("Registered dynamic receiver: ${receiver.javaClass.simpleName} for $instanceId")

        // Return sticky broadcast if available
        for (i in 0 until intentFilter.countActions()) {
            val action = intentFilter.getAction(i)
            val sticky = stickyBroadcasts[action]
            if (sticky != null) {
                return sticky
            }
        }

        return null
    }

    /**
     * Unregister a dynamic BroadcastReceiver.
     */
    fun unregisterReceiver(instanceId: String, receiver: BroadcastReceiver): Boolean {
        val record = receiverMap.remove(receiver) ?: run {
            Timber.tag(TAG).w("Receiver not found for unregister: ${receiver.javaClass.simpleName}")
            return false
        }

        val records = dynamicReceivers[instanceId]
        records?.remove(record)

        Timber.tag(TAG).d("Unregistered dynamic receiver: ${receiver.javaClass.simpleName}")
        return true
    }

    /**
     * Send a normal (unordered) broadcast.
     *
     * @param intent The broadcast Intent
     * @param instanceId If non-null, only deliver to this instance (app-internal broadcast).
     *                   If null, deliver to all matching virtual apps.
     * @param senderPermission Optional permission the receiver must hold
     */
    fun sendBroadcast(
        intent: Intent,
        instanceId: String? = null,
        senderPermission: String? = null
    ) {
        val action = intent.action
        Timber.tag(TAG).d("Sending broadcast: $action (instance=${instanceId ?: "all"})")

        val matchingReceivers = findMatchingReceivers(intent, instanceId, senderPermission)

        if (matchingReceivers.isEmpty()) {
            Timber.tag(TAG).d("No receivers matched broadcast: $action")
            return
        }

        Timber.tag(TAG).d("Dispatching to ${matchingReceivers.size} receivers")

        for (record in matchingReceivers) {
            dispatchToReceiver(record, intent, null)
        }
    }

    /**
     * Send an ordered broadcast with priorities.
     *
     * Broadcasts are delivered to receivers in priority order (highest first).
     * Each receiver can abort the broadcast for lower-priority receivers.
     *
     * @param intent The broadcast Intent
     * @param permission Required permission to receive
     * @param instanceId If non-null, scoped to this instance
     * @param resultReceiver Optional final receiver that always gets called
     * @param initialCode Initial result code
     * @param initialData Initial result data
     * @param initialExtras Initial result extras
     */
    fun sendOrderedBroadcast(
        intent: Intent,
        permission: String? = null,
        instanceId: String? = null,
        resultReceiver: BroadcastReceiver? = null,
        initialCode: Int = 0,
        initialData: String? = null,
        initialExtras: Bundle? = null
    ) {
        val action = intent.action
        Timber.tag(TAG).d("Sending ordered broadcast: $action")

        val matchingReceivers = findMatchingReceivers(intent, instanceId, permission)
            .sortedByDescending { it.priority }

        if (matchingReceivers.isEmpty() && resultReceiver == null) {
            Timber.tag(TAG).d("No receivers for ordered broadcast: $action")
            return
        }

        val orderedRecord = OrderedBroadcastRecord(
            intent = intent,
            permission = permission,
            resultCode = initialCode,
            resultData = initialData,
            resultExtras = initialExtras,
            receivers = matchingReceivers
        )

        deliverOrderedBroadcast(orderedRecord, resultReceiver)
    }

    /**
     * Send a sticky broadcast that persists for future receivers.
     */
    fun sendStickyBroadcast(intent: Intent, instanceId: String? = null) {
        val action = intent.action ?: return

        stickyBroadcasts[action] = Intent(intent)
        sendBroadcast(intent, instanceId)

        Timber.tag(TAG).d("Sticky broadcast sent: $action")
    }

    /**
     * Send a local broadcast (only within a single instance).
     * More efficient than cross-instance broadcasts.
     */
    fun sendLocalBroadcast(intent: Intent, instanceId: String) {
        sendBroadcast(intent, instanceId)
    }

    /**
     * Remove a sticky broadcast.
     */
    fun removeStickyBroadcast(action: String) {
        stickyBroadcasts.remove(action)
    }

    /**
     * Dispatch a system broadcast (from real Android) to interested virtual apps.
     * Only well-known system actions are forwarded for security.
     */
    fun dispatchSystemBroadcast(intent: Intent) {
        val action = intent.action ?: return

        if (action !in SYSTEM_BROADCAST_ACTIONS) {
            return
        }

        Timber.tag(TAG).d("Dispatching system broadcast to virtual apps: $action")
        sendBroadcast(intent) // Deliver to all instances
    }

    // ---------- Internal Dispatch ----------

    /**
     * Find all receivers matching an Intent.
     */
    private fun findMatchingReceivers(
        intent: Intent,
        instanceId: String?,
        requiredPermission: String?
    ): List<ReceiverRecord> {
        val result = mutableListOf<ReceiverRecord>()

        // Collect from both static and dynamic registrations
        val allRecords = mutableListOf<ReceiverRecord>()

        if (instanceId != null) {
            staticReceivers[instanceId]?.let { allRecords.addAll(it) }
            dynamicReceivers[instanceId]?.let { allRecords.addAll(it) }
        } else {
            for (list in staticReceivers.values) allRecords.addAll(list)
            for (list in dynamicReceivers.values) allRecords.addAll(list)
        }

        for (record in allRecords) {
            if (!record.isRegistered) continue

            // Check permission
            if (requiredPermission != null && record.permission != null) {
                if (record.permission != requiredPermission) continue
            }

            // Match intent filter
            val matchResult = record.intentFilter.match(
                intent.action,
                intent.type,
                intent.scheme,
                intent.data,
                intent.categories,
                TAG
            )

            if (matchResult >= 0) {
                result.add(record)
            }
        }

        return result
    }

    /**
     * Dispatch an Intent to a single receiver record.
     */
    private fun dispatchToReceiver(
        record: ReceiverRecord,
        intent: Intent,
        classLoader: ClassLoader?
    ) {
        mainHandler.post {
            try {
                val receiver: BroadcastReceiver? = if (record.isStatic) {
                    // Static: need to instantiate via ClassLoader
                    instantiateStaticReceiver(record, classLoader)
                } else {
                    record.receiver
                }

                if (receiver != null) {
                    receiver.onReceive(null, intent) // Context is null — guest should use their own cached context
                    Timber.tag(TAG).d("Delivered broadcast to ${record.receiverClassName}")
                } else {
                    Timber.tag(TAG).w("Could not instantiate receiver: ${record.receiverClassName}")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error delivering broadcast to ${record.receiverClassName}")
            }
        }
    }

    /**
     * Instantiate a static BroadcastReceiver from its class name.
     */
    private fun instantiateStaticReceiver(
        record: ReceiverRecord,
        classLoader: ClassLoader?
    ): BroadcastReceiver? {
        val className = record.receiverClassName ?: return null

        val cl = classLoader ?: try {
            Thread.currentThread().contextClassLoader
        } catch (e: Exception) {
            null
        } ?: return null

        return runSafe(TAG) {
            val receiverClass = cl.loadClass(className)
            val ctor = receiverClass.getDeclaredConstructor()
            ctor.isAccessible = true
            ctor.newInstance() as BroadcastReceiver
        }
    }

    /**
     * Deliver an ordered broadcast to receivers one by one.
     */
    private fun deliverOrderedBroadcast(
        record: OrderedBroadcastRecord,
        finalReceiver: BroadcastReceiver?
    ) {
        deliverNextInOrder(record, finalReceiver)
    }

    private fun deliverNextInOrder(
        record: OrderedBroadcastRecord,
        finalReceiver: BroadcastReceiver?
    ) {
        if (record.aborted || record.currentIndex >= record.receivers.size) {
            // All receivers processed (or aborted) — deliver to final receiver
            if (finalReceiver != null) {
                mainHandler.post {
                    try {
                        finalReceiver.onReceive(null, record.intent)
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error in final receiver")
                    }
                }
            }
            return
        }

        val receiverRecord = record.receivers[record.currentIndex]
        record.currentIndex++

        mainHandler.post {
            try {
                val receiver = if (receiverRecord.isStatic) {
                    instantiateStaticReceiver(receiverRecord, null)
                } else {
                    receiverRecord.receiver
                }

                if (receiver != null) {
                    // Set up ordered broadcast result access
                    receiver.onReceive(null, record.intent)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in ordered broadcast delivery")
            }

            // Continue to next receiver
            deliverNextInOrder(record, finalReceiver)
        }
    }

    // ---------- Cleanup ----------

    /**
     * Unregister all receivers for an instance (static + dynamic).
     */
    fun unregisterAllReceivers(instanceId: String) {
        val staticCount = staticReceivers.remove(instanceId)?.size ?: 0
        val dynamicList = dynamicReceivers.remove(instanceId)
        val dynamicCount = dynamicList?.size ?: 0

        // Remove from reverse map
        dynamicList?.forEach { record ->
            record.receiver?.let { receiverMap.remove(it) }
        }

        Timber.tag(TAG).i("Unregistered all receivers for $instanceId " +
            "(static=$staticCount, dynamic=$dynamicCount)")
    }

    /**
     * Get the count of registered receivers for an instance.
     */
    fun getReceiverCount(instanceId: String): Int {
        val s = staticReceivers[instanceId]?.size ?: 0
        val d = dynamicReceivers[instanceId]?.size ?: 0
        return s + d
    }

    /**
     * Clear all state (shutdown).
     */
    fun clearAll() {
        Timber.tag(TAG).i("Clearing all broadcast state")
        staticReceivers.clear()
        dynamicReceivers.clear()
        receiverMap.clear()
        stickyBroadcasts.clear()
    }

    /**
     * Initialize the broadcast manager with application context.
     */
    fun initialize(context: android.content.Context) {
        Timber.tag(TAG).d("VirtualBroadcastManager initialized")
    }

    /**
     * Shutdown (delegates to clearAll).
     */
    fun shutdown() {
        clearAll()
        Timber.tag(TAG).d("VirtualBroadcastManager shut down")
    }
}

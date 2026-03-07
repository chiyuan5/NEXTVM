package com.nextvm.core.services.component

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.nextvm.core.common.findField
import com.nextvm.core.common.findMethod
import com.nextvm.core.common.runSafe
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VirtualServiceLifecycleManager — Complete Service lifecycle management for guest apps.
 *
 * Mirrors:
 *   - ActiveServices.java in frameworks/base/services/core/java/com/android/server/am/
 *   - ActivityThread.handleCreateService()
 *   - ActivityThread.handleServiceArgs()
 *   - ActivityThread.handleBindService()
 *   - ActivityThread.handleUnbindService()
 *   - ActivityThread.handleStopService()
 *
 * Handles:
 *   - Service class instantiation via guest app's ClassLoader
 *   - Service.attachBaseContext() + Service.onCreate()
 *   - onStartCommand(), onBind(), onUnbind(), onDestroy()
 *   - Foreground service handling (startForeground/stopForeground)
 *   - Service binding with IBinder return
 *   - Tracks active services per instance
 */
@Singleton
class VirtualServiceLifecycleManager @Inject constructor() {

    companion object {
        private const val TAG = "VServiceMgr"

        /** Maximum active services per instance (mirrors system limit) */
        const val MAX_SERVICES_PER_INSTANCE = 100

        /** Service start IDs (mimics real Android behavior) */
        private var nextStartId: Int = 1
    }

    /**
     * Full state record for a running virtual Service.
     */
    data class ServiceRecord(
        val instanceId: String,
        val packageName: String,
        val serviceName: String,
        val service: Service,
        var state: ServiceState = ServiceState.CREATED,
        val boundClients: MutableSet<String> = mutableSetOf(),
        var isForeground: Boolean = false,
        var foregroundId: Int = 0,
        var binder: IBinder? = null,
        val startIds: MutableList<Int> = mutableListOf(),
        var lastStartId: Int = 0,
        val createdAt: Long = System.currentTimeMillis()
    )

    enum class ServiceState {
        CREATING,
        CREATED,
        STARTED,
        BOUND,
        STARTED_AND_BOUND,
        DESTROYING,
        DESTROYED
    }

    // instanceId -> (serviceName -> ServiceRecord)
    private val services = ConcurrentHashMap<String, ConcurrentHashMap<String, ServiceRecord>>()

    // Reverse lookup: Service instance -> (instanceId, serviceName)
    private val serviceInstances = ConcurrentHashMap<Service, Pair<String, String>>()

    /**
     * Create and start a Service.
     *
     * Mirrors ActivityThread.handleCreateService():
     *   1. Load Service class via ClassLoader
     *   2. Create instance
     *   3. Call Service.attach()
     *   4. Call Service.onCreate()
     *
     * @param instanceId Guest app instance ID
     * @param packageName Guest app package name
     * @param serviceName Fully qualified Service class name
     * @param classLoader Guest app's DexClassLoader
     * @param virtualContext Sandboxed context for the guest app
     * @return The created Service or null on failure
     */
    fun createService(
        instanceId: String,
        packageName: String,
        serviceName: String,
        classLoader: ClassLoader,
        virtualContext: Context
    ): Service? {
        // Check if already running
        val existing = getServiceRecord(instanceId, serviceName)
        if (existing != null && existing.state != ServiceState.DESTROYED) {
            Timber.tag(TAG).d("Service already running: $serviceName for $instanceId")
            return existing.service
        }

        // Check service count limit
        val instanceServices = services.getOrPut(instanceId) { ConcurrentHashMap() }
        if (instanceServices.size >= MAX_SERVICES_PER_INSTANCE) {
            Timber.tag(TAG).e("Max services reached for $instanceId ($MAX_SERVICES_PER_INSTANCE)")
            return null
        }

        Timber.tag(TAG).i("Creating service: $serviceName for $instanceId")

        try {
            // Step 1: Load Service class
            val serviceClass = classLoader.loadClass(serviceName)
            if (!Service::class.java.isAssignableFrom(serviceClass)) {
                Timber.tag(TAG).e("$serviceName is not a Service subclass")
                return null
            }

            // Step 2: Create instance
            val service = serviceClass.getDeclaredConstructor().let { ctor ->
                ctor.isAccessible = true
                ctor.newInstance()
            } as Service

            // Step 3: Attach context
            attachServiceContext(service, virtualContext, serviceName, packageName)

            // Step 4: Create record
            val record = ServiceRecord(
                instanceId = instanceId,
                packageName = packageName,
                serviceName = serviceName,
                service = service,
                state = ServiceState.CREATING
            )
            instanceServices[serviceName] = record
            serviceInstances[service] = Pair(instanceId, serviceName)

            // Step 5: Call onCreate()
            try {
                service.onCreate()
                record.state = ServiceState.CREATED
                Timber.tag(TAG).d("Service.onCreate() succeeded: $serviceName")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Service.onCreate() failed: $serviceName")
                instanceServices.remove(serviceName)
                serviceInstances.remove(service)
                return null
            }

            return service

        } catch (e: ClassNotFoundException) {
            Timber.tag(TAG).e("Service class not found: $serviceName")
            return null
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to create service: $serviceName")
            return null
        }
    }

    /**
     * Attach context to a Service.
     *
     * Mirrors ActivityThread.handleCreateService() which calls service.attach():
     *   service.attach(context, activityThread, className, token, application, activityManager)
     *
     * The attach() method is a hidden API that sets up the Service's internal fields.
     * We fall back to attachBaseContext() if attach() is not available.
     */
    private fun attachServiceContext(
        service: Service,
        virtualContext: Context,
        serviceName: String,
        packageName: String
    ) {
        try {
            // Try Service.attach() — the full hidden method
            val attachMethod = findMethod(
                Service::class.java,
                "attach",
                arrayOf(
                    Context::class.java,                           // context
                    Class.forName("android.app.ActivityThread"),    // thread
                    String::class.java,                            // className
                    android.os.IBinder::class.java,                // token
                    android.app.Application::class.java,           // application
                    Any::class.java                                // activityManager
                )
            )

            if (attachMethod != null) {
                attachMethod.isAccessible = true
                // Get current ActivityThread
                val atClass = Class.forName("android.app.ActivityThread")
                val currentAT = atClass.getDeclaredMethod("currentActivityThread")
                currentAT.isAccessible = true
                val activityThread = currentAT.invoke(null)

                attachMethod.invoke(
                    service,
                    virtualContext,     // context
                    activityThread,     // thread
                    serviceName,        // className
                    null,               // token (IBinder)
                    null,               // application
                    null                // activityManager
                )
                Timber.tag(TAG).d("Service.attach() succeeded")
                return
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Service.attach() failed, using fallback")
        }

        // Fallback: use attachBaseContext via reflection
        try {
            val attachBase = findMethod(
                android.content.ContextWrapper::class.java,
                "attachBaseContext",
                arrayOf(Context::class.java)
            )
            attachBase?.isAccessible = true
            attachBase?.invoke(service, virtualContext)
            Timber.tag(TAG).d("Service.attachBaseContext() fallback succeeded")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "attachBaseContext fallback failed, setting mBase directly")
            try {
                val mBase = findField(android.content.ContextWrapper::class.java, "mBase")
                mBase?.isAccessible = true
                mBase?.set(service, virtualContext)
            } catch (e2: Exception) {
                Timber.tag(TAG).e(e2, "All service context attach methods failed")
            }
        }
    }

    /**
     * Deliver a start command to a Service.
     *
     * Mirrors ActivityThread.handleServiceArgs() which calls service.onStartCommand().
     *
     * @return The start result code (START_STICKY, START_NOT_STICKY, etc.)
     */
    fun onStartCommand(
        instanceId: String,
        serviceName: String,
        intent: Intent?,
        flags: Int = 0
    ): Int {
        val record = getServiceRecord(instanceId, serviceName) ?: run {
            Timber.tag(TAG).e("Service not found for onStartCommand: $serviceName")
            return Service.START_NOT_STICKY
        }

        try {
            val startId = nextStartId++
            record.startIds.add(startId)
            record.lastStartId = startId

            val result = record.service.onStartCommand(intent, flags, startId)
            record.state = when (record.state) {
                ServiceState.BOUND -> ServiceState.STARTED_AND_BOUND
                else -> ServiceState.STARTED
            }

            Timber.tag(TAG).d("onStartCommand: $serviceName, startId=$startId, result=$result")
            return result

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "onStartCommand failed: $serviceName")
            return Service.START_NOT_STICKY
        }
    }

    /**
     * Bind to a Service and get its IBinder.
     *
     * Mirrors ActivityThread.handleBindService() which calls service.onBind().
     *
     * @param clientId Identifier for the binding client (for tracking)
     * @return IBinder returned by Service.onBind(), or null
     */
    fun bindService(
        instanceId: String,
        serviceName: String,
        intent: Intent,
        clientId: String
    ): IBinder? {
        val record = getServiceRecord(instanceId, serviceName) ?: run {
            Timber.tag(TAG).e("Service not found for bind: $serviceName")
            return null
        }

        try {
            // If already bound, we may return the cached binder
            if (record.binder != null && record.boundClients.isNotEmpty()) {
                record.boundClients.add(clientId)
                Timber.tag(TAG).d("Returning cached binder for $serviceName, client=$clientId")
                return record.binder
            }

            // Call onBind()
            val binder = record.service.onBind(intent)
            record.binder = binder
            record.boundClients.add(clientId)
            record.state = when (record.state) {
                ServiceState.STARTED -> ServiceState.STARTED_AND_BOUND
                else -> ServiceState.BOUND
            }

            Timber.tag(TAG).d("Service bound: $serviceName, client=$clientId, binder=${binder != null}")
            return binder

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "bindService failed: $serviceName")
            return null
        }
    }

    /**
     * Unbind a client from a Service.
     *
     * Mirrors ActivityThread.handleUnbindService().
     * Calls onUnbind() when the last client unbinds.
     *
     * @return true if the service should be auto-rebound (onRebind supported)
     */
    fun unbindService(
        instanceId: String,
        serviceName: String,
        intent: Intent,
        clientId: String
    ): Boolean {
        val record = getServiceRecord(instanceId, serviceName) ?: run {
            Timber.tag(TAG).w("Service not found for unbind: $serviceName")
            return false
        }

        record.boundClients.remove(clientId)

        if (record.boundClients.isEmpty()) {
            try {
                val rebind = record.service.onUnbind(intent)
                record.binder = null
                record.state = when (record.state) {
                    ServiceState.STARTED_AND_BOUND -> ServiceState.STARTED
                    else -> {
                        // If not started and no clients, may need to destroy
                        if (record.startIds.isEmpty()) {
                            destroyService(instanceId, serviceName)
                        }
                        ServiceState.CREATED
                    }
                }
                Timber.tag(TAG).d("Service unbound: $serviceName, rebind=$rebind")
                return rebind
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "onUnbind failed: $serviceName")
            }
        } else {
            Timber.tag(TAG).d("Client unbound from $serviceName, ${record.boundClients.size} remaining")
        }

        return false
    }

    /**
     * Handle startForeground() call from a guest Service.
     */
    fun setForeground(
        instanceId: String,
        serviceName: String,
        notificationId: Int,
        isForeground: Boolean
    ) {
        val record = getServiceRecord(instanceId, serviceName) ?: return

        record.isForeground = isForeground
        record.foregroundId = if (isForeground) notificationId else 0

        Timber.tag(TAG).d("Service foreground state: $serviceName, fg=$isForeground, notifId=$notificationId")
    }

    /**
     * Stop and destroy a Service.
     *
     * Mirrors ActivityThread.handleStopService() which calls service.onDestroy().
     */
    fun destroyService(instanceId: String, serviceName: String): Boolean {
        val instanceServices = services[instanceId] ?: return false
        val record = instanceServices[serviceName] ?: return false

        if (record.state == ServiceState.DESTROYED) {
            return true
        }

        Timber.tag(TAG).i("Destroying service: $serviceName for $instanceId")
        record.state = ServiceState.DESTROYING

        try {
            // Stop foreground if active
            if (record.isForeground) {
                try {
                    record.service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "stopForeground failed for $serviceName")
                }
            }

            // Unbind remaining clients
            if (record.boundClients.isNotEmpty()) {
                try {
                    record.service.onUnbind(Intent())
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Final onUnbind failed for $serviceName")
                }
                record.boundClients.clear()
            }

            // Call onDestroy()
            record.service.onDestroy()
            Timber.tag(TAG).d("Service.onDestroy() succeeded: $serviceName")

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Service.onDestroy() failed: $serviceName")
        } finally {
            record.state = ServiceState.DESTROYED
            record.binder = null
            instanceServices.remove(serviceName)
            serviceInstances.remove(record.service)

            // Clean up empty instance map
            if (instanceServices.isEmpty()) {
                services.remove(instanceId)
            }
        }

        return true
    }

    /**
     * Stop a service by start ID (mirrors Service.stopSelfResult()).
     */
    fun stopServiceByStartId(instanceId: String, serviceName: String, startId: Int): Boolean {
        val record = getServiceRecord(instanceId, serviceName) ?: return false

        // Only stop if this is the latest startId
        if (record.lastStartId == startId) {
            return destroyService(instanceId, serviceName)
        }

        Timber.tag(TAG).d("stopSelfResult($startId) ignored, latest=$record.lastStartId")
        return false
    }

    /**
     * Destroy all services for an instance.
     */
    fun destroyAllServices(instanceId: String) {
        val instanceServices = services[instanceId] ?: return
        val serviceNames = instanceServices.keys.toList()

        Timber.tag(TAG).i("Destroying all ${serviceNames.size} services for $instanceId")

        for (name in serviceNames) {
            destroyService(instanceId, name)
        }

        services.remove(instanceId)
    }

    /**
     * Called when a guest app calls Service.stopSelf().
     */
    fun stopSelf(service: Service): Boolean {
        val pair = serviceInstances[service] ?: return false
        return destroyService(pair.first, pair.second)
    }

    // ---------- Query Methods ----------

    fun getServiceRecord(instanceId: String, serviceName: String): ServiceRecord? =
        services[instanceId]?.get(serviceName)

    fun getService(instanceId: String, serviceName: String): Service? =
        getServiceRecord(instanceId, serviceName)?.service

    fun isServiceRunning(instanceId: String, serviceName: String): Boolean {
        val record = getServiceRecord(instanceId, serviceName) ?: return false
        return record.state != ServiceState.DESTROYED && record.state != ServiceState.DESTROYING
    }

    fun getRunningServices(instanceId: String): List<ServiceRecord> =
        services[instanceId]?.values?.filter {
            it.state != ServiceState.DESTROYED && it.state != ServiceState.DESTROYING
        } ?: emptyList()

    fun getRunningServiceCount(instanceId: String): Int =
        services[instanceId]?.values?.count {
            it.state != ServiceState.DESTROYED && it.state != ServiceState.DESTROYING
        } ?: 0

    fun getForegroundServices(instanceId: String): List<ServiceRecord> =
        getRunningServices(instanceId).filter { it.isForeground }

    fun getAllRunningServiceCount(): Int =
        services.values.sumOf { instanceMap ->
            instanceMap.values.count {
                it.state != ServiceState.DESTROYED && it.state != ServiceState.DESTROYING
            }
        }

    /**
     * Look up which instance a Service instance belongs to.
     */
    fun findInstanceForService(service: Service): String? =
        serviceInstances[service]?.first

    /**
     * Destroy all services across all instances (shutdown).
     */
    fun destroyAll() {
        Timber.tag(TAG).i("Destroying all services across all instances")
        val instanceIds = services.keys.toList()
        for (id in instanceIds) {
            destroyAllServices(id)
        }
    }

    /**
     * Initialize the service lifecycle manager with application context.
     */
    fun initialize(context: android.content.Context) {
        Timber.tag(TAG).d("VirtualServiceLifecycleManager initialized")
    }

    /**
     * Shutdown (delegates to destroyAll).
     */
    fun shutdown() {
        destroyAll()
        Timber.tag(TAG).d("VirtualServiceLifecycleManager shut down")
    }
}

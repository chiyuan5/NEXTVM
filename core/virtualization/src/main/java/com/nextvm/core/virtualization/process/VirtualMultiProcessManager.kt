package com.nextvm.core.virtualization.process

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import com.nextvm.core.common.AndroidCompat
import com.nextvm.core.common.findField
import com.nextvm.core.common.runSafe
import com.nextvm.core.model.VmResult
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VirtualMultiProcessManager — Multi-process support for virtual apps.
 *
 * Android apps can declare multiple processes via android:process attributes
 * on Activities, Services, ContentProviders, and BroadcastReceivers.
 * This manager handles:
 *
 * - Process creation and lifecycle for guest app sub-processes
 * - Process naming (e.g., ":remote", ":pushservice")
 * - Multi-process IPC routing (AIDL, Messenger, Binder)
 * - Process priority and OOM adjustment tracking
 * - Process death handling and restart logic
 * - Process slot allocation within NEXTVM's stub process pool
 *
 * NEXTVM's process model:
 *   Main process (com.nextvm.app) — UI, launcher
 *   Service process (:x) — Engine daemon, BinderProvider
 *   Guest process :p0 — Guest app #1 main process
 *   Guest process :p1 — Guest app #2 main process  
 *   Guest process :p2..N — Additional apps or sub-processes
 *
 * When a guest app declares android:process=":remote", we allocate
 * an additional stub process slot for that sub-process. Components
 * in the same declared process name share a stub process.
 */
@Singleton
class VirtualMultiProcessManager @Inject constructor() {

    companion object {
        private const val TAG = "VMProcMgr"

        // Maximum process slots available (matches AndroidManifest stubs)
        const val MAX_PROCESS_SLOTS = 10

        // OOM adjustment levels (matching Android ActivityManager)
        const val OOM_ADJ_FOREGROUND = 0
        const val OOM_ADJ_VISIBLE = 100
        const val OOM_ADJ_PERCEPTIBLE = 200
        const val OOM_ADJ_BACKUP = 300
        const val OOM_ADJ_SERVICE = 500
        const val OOM_ADJ_HOME = 600
        const val OOM_ADJ_PREVIOUS = 700
        const val OOM_ADJ_SERVICE_B = 800
        const val OOM_ADJ_CACHED_APP = 900
        const val OOM_ADJ_CACHED_APP_MAX = 999

        // Process states
        const val PROCESS_STATE_TOP = 2
        const val PROCESS_STATE_FOREGROUND_SERVICE = 4
        const val PROCESS_STATE_BOUND_FOREGROUND = 3
        const val PROCESS_STATE_SERVICE = 10
        const val PROCESS_STATE_CACHED = 16
    }

    // All process records: key = "instanceId:processName"
    private val processRecords = ConcurrentHashMap<String, ProcessRecord>()

    // Instance to main process mapping: instanceId -> ProcessRecord
    private val mainProcesses = ConcurrentHashMap<String, ProcessRecord>()

    // Slot allocation: slot index -> instanceId:processName key
    private val slotAllocation = ConcurrentHashMap<Int, String>()

    // Reverse mapping: PID -> process record key
    private val pidToProcess = ConcurrentHashMap<Int, String>()

    // Instance's declared processes: instanceId -> set of process names
    private val declaredProcesses = ConcurrentHashMap<String, MutableSet<String>>()

    // Component to process mapping: instanceId:componentName -> processName
    private val componentProcessMap = ConcurrentHashMap<String, String>()

    // Process death listeners
    private val deathListeners = mutableListOf<ProcessDeathListener>()

    // Next available PID for virtual processes
    private val nextVirtualPid = AtomicInteger(10000)

    // === Per-process ClassLoader isolation ===
    // Each process gets its own ClassLoader — prevents shared state leaks
    // key = processKey ("instanceId:processName") -> ClassLoader
    private val processClassLoaders = ConcurrentHashMap<String, ClassLoader>()

    // === Per-process Application instance ===
    // Each process creates its own Application — matches real Android behavior
    // key = processKey -> Application instance
    private val processApplications = ConcurrentHashMap<String, android.app.Application>()

    // === Cross-process Binder IPC routing ===
    // Virtual Binder registry: "instanceId:interfaceName" -> IBinder
    // Allows guest app sub-processes to bind to each other's services
    private val binderRegistry = ConcurrentHashMap<String, android.os.IBinder>()

    // Pending bind requests: key = "instanceId:interfaceName" -> list of callbacks
    private val pendingBindRequests = ConcurrentHashMap<String, MutableList<(android.os.IBinder) -> Unit>>()

    // Application context
    private var appContext: Context? = null
    private var activityManager: ActivityManager? = null
    private var initialized = false

    /**
     * Initialize the process manager.
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        initialized = true
        Timber.tag(TAG).i("VirtualMultiProcessManager initialized")
    }

    /**
     * Register process declarations from a guest app's manifest.
     * Call this during app installation to record which components
     * run in which processes.
     *
     * @param instanceId The virtual app instance
     * @param components Map of component class names to process names
     */
    fun registerProcessDeclarations(
        instanceId: String,
        components: Map<String, String>
    ) {
        val processes = mutableSetOf<String>()
        for ((componentName, processName) in components) {
            val key = "$instanceId:$componentName"
            componentProcessMap[key] = processName
            processes.add(processName)
        }
        declaredProcesses[instanceId] = processes
        Timber.tag(TAG).d("Registered ${processes.size} processes for instance $instanceId: $processes")
    }

    /**
     * Create a process for a virtual app.
     *
     * @param instanceId The virtual app instance
     * @param processName The process name (e.g., main package name, ":remote", ":pushservice")
     * @return The created ProcessRecord
     */
    fun createProcess(instanceId: String, processName: String): VmResult<ProcessRecord> {
        val key = processKey(instanceId, processName)

        // Check if process already exists
        val existing = processRecords[key]
        if (existing != null && existing.state != ProcessState.DEAD) {
            Timber.tag(TAG).d("Process already exists: $key (state=${existing.state})")
            return VmResult.Success(existing)
        }

        // Allocate a process slot
        val slot = allocateSlot(key)
        if (slot < 0) {
            Timber.tag(TAG).e("No available process slots (max=$MAX_PROCESS_SLOTS)")
            return VmResult.Error("No available process slots. Maximum $MAX_PROCESS_SLOTS reached.")
        }

        // Generate a virtual PID
        val virtualPid = nextVirtualPid.getAndIncrement()

        // Create the process record
        val record = ProcessRecord(
            instanceId = instanceId,
            processName = processName,
            processSlot = slot,
            pid = virtualPid,
            uid = generateVirtualUid(instanceId, slot),
            state = ProcessState.CREATED,
            oomAdj = OOM_ADJ_CACHED_APP,
            processState = PROCESS_STATE_CACHED,
            isMainProcess = isMainProcess(instanceId, processName),
            createdAt = System.currentTimeMillis(),
            lastActivityAt = System.currentTimeMillis()
        )

        processRecords[key] = record
        pidToProcess[virtualPid] = key

        // Track main process
        if (record.isMainProcess) {
            mainProcesses[instanceId] = record
        }

        Timber.tag(TAG).i("Process created: $key (slot=$slot, pid=$virtualPid)")
        return VmResult.Success(record)
    }

    /**
     * Get or create a process for a specific component.
     *
     * @param instanceId The virtual app instance
     * @param componentName The fully qualified component class name
     * @return The process record for this component
     */
    fun getProcessForComponent(instanceId: String, componentName: String): VmResult<ProcessRecord> {
        val key = "$instanceId:$componentName"
        val processName = componentProcessMap[key]
            ?: getDefaultProcessName(instanceId)

        return getOrCreateProcess(instanceId, processName)
    }

    /**
     * Get or create a process by name.
     */
    fun getOrCreateProcess(instanceId: String, processName: String): VmResult<ProcessRecord> {
        val key = processKey(instanceId, processName)
        val existing = processRecords[key]
        if (existing != null && existing.state != ProcessState.DEAD) {
            return VmResult.Success(existing)
        }
        return createProcess(instanceId, processName)
    }

    /**
     * Handle process death.
     * Called when a stub process (:pN) dies or is killed.
     *
     * @param instanceId The virtual app instance
     * @param processName The process that died
     */
    fun handleProcessDeath(instanceId: String, processName: String) {
        val key = processKey(instanceId, processName)
        val record = processRecords[key] ?: return

        Timber.tag(TAG).w("Process died: $key (pid=${record.pid})")

        // Update state
        record.state = ProcessState.DEAD
        record.diedAt = System.currentTimeMillis()
        record.deathCount++

        // Remove PID mapping
        pidToProcess.remove(record.pid)

        // Free the slot
        releaseSlot(record.processSlot)

        // Notify listeners
        deathListeners.forEach { listener ->
            try {
                listener.onProcessDied(instanceId, processName, record)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in process death listener")
            }
        }

        // If main process died, kill all sub-processes
        if (record.isMainProcess) {
            killAllSubProcesses(instanceId)
        }

        // Remove from main process tracking
        if (record.isMainProcess) {
            mainProcesses.remove(instanceId)
        }
    }

    /**
     * Handle process death by PID.
     */
    fun handleProcessDeathByPid(pid: Int) {
        val key = pidToProcess[pid] ?: return
        val parts = key.split(":", limit = 2)
        if (parts.size == 2) {
            handleProcessDeath(parts[0], parts[1])
        }
    }

    /**
     * Update the OOM adjustment for a process.
     * Higher adj = more likely to be killed by the system.
     *
     * @param instanceId The virtual app instance
     * @param processName The process name
     * @param adj The new OOM adjustment value
     */
    fun updateOomAdj(instanceId: String, processName: String, adj: Int) {
        val key = processKey(instanceId, processName)
        val record = processRecords[key] ?: return

        val previousAdj = record.oomAdj
        record.oomAdj = adj
        record.lastActivityAt = System.currentTimeMillis()

        // Update process state based on adj
        record.processState = when {
            adj <= OOM_ADJ_FOREGROUND -> PROCESS_STATE_TOP
            adj <= OOM_ADJ_VISIBLE -> PROCESS_STATE_BOUND_FOREGROUND
            adj <= OOM_ADJ_PERCEPTIBLE -> PROCESS_STATE_FOREGROUND_SERVICE
            adj <= OOM_ADJ_SERVICE -> PROCESS_STATE_SERVICE
            else -> PROCESS_STATE_CACHED
        }

        // Update running state
        if (record.state == ProcessState.CREATED) {
            record.state = ProcessState.RUNNING
        }

        if (previousAdj != adj) {
            Timber.tag(TAG).d("OOM adj updated: $key $previousAdj -> $adj (state=${record.processState})")
        }
    }

    /**
     * Mark a process as in the foreground.
     */
    fun bringToForeground(instanceId: String, processName: String) {
        updateOomAdj(instanceId, processName, OOM_ADJ_FOREGROUND)
    }

    /**
     * Mark a process as in the background.
     */
    fun sendToBackground(instanceId: String, processName: String) {
        updateOomAdj(instanceId, processName, OOM_ADJ_CACHED_APP)
    }

    /**
     * Get all processes for an instance.
     */
    fun getProcessList(instanceId: String): List<ProcessRecord> {
        return processRecords.values.filter {
            it.instanceId == instanceId && it.state != ProcessState.DEAD
        }.sortedBy { it.processSlot }
    }

    /**
     * Get a specific process record.
     */
    fun getProcess(instanceId: String, processName: String): ProcessRecord? {
        return processRecords[processKey(instanceId, processName)]
    }

    /**
     * Get the main process for an instance.
     */
    fun getMainProcess(instanceId: String): ProcessRecord? {
        return mainProcesses[instanceId]
    }

    /**
     * Get a process record by PID.
     */
    fun getProcessByPid(pid: Int): ProcessRecord? {
        val key = pidToProcess[pid] ?: return null
        return processRecords[key]
    }

    /**
     * Get the instance ID for a PID.
     */
    fun getInstanceForPid(pid: Int): String? {
        return getProcessByPid(pid)?.instanceId
    }

    /**
     * Get the process slot for a PID.
     */
    fun getSlotForPid(pid: Int): Int {
        return getProcessByPid(pid)?.processSlot ?: -1
    }

    /**
     * Check if any processes are running for an instance.
     */
    fun hasRunningProcesses(instanceId: String): Boolean {
        return processRecords.values.any {
            it.instanceId == instanceId && it.state == ProcessState.RUNNING
        }
    }

    /**
     * Get the count of running processes for an instance.
     */
    fun getRunningProcessCount(instanceId: String): Int {
        return processRecords.values.count {
            it.instanceId == instanceId && it.state != ProcessState.DEAD
        }
    }

    /**
     * Get total running processes across all instances.
     */
    fun getTotalRunningProcesses(): Int {
        return processRecords.values.count { it.state != ProcessState.DEAD }
    }

    /**
     * Get the number of available process slots.
     */
    fun getAvailableSlots(): Int {
        return MAX_PROCESS_SLOTS - slotAllocation.size
    }

    /**
     * Kill all processes for an instance.
     * Used when stopping or uninstalling a virtual app.
     */
    fun killAllProcesses(instanceId: String) {
        Timber.tag(TAG).i("Killing all processes for instance: $instanceId")

        val processes = processRecords.values.filter {
            it.instanceId == instanceId && it.state != ProcessState.DEAD
        }

        for (record in processes) {
            killProcess(record)
        }

        mainProcesses.remove(instanceId)
        declaredProcesses.remove(instanceId)

        // Remove component mappings
        val keysToRemove = componentProcessMap.keys.filter { it.startsWith("$instanceId:") }
        keysToRemove.forEach { componentProcessMap.remove(it) }

        Timber.tag(TAG).i("Killed ${processes.size} processes for $instanceId")
    }

    /**
     * Kill a specific process.
     */
    fun killProcess(instanceId: String, processName: String): Boolean {
        val key = processKey(instanceId, processName)
        val record = processRecords[key] ?: return false
        return killProcess(record)
    }

    /**
     * Register a process death listener.
     */
    fun addProcessDeathListener(listener: ProcessDeathListener) {
        deathListeners.add(listener)
    }

    /**
     * Remove a process death listener.
     */
    fun removeProcessDeathListener(listener: ProcessDeathListener) {
        deathListeners.remove(listener)
    }

    /**
     * Record a component being started in a process.
     */
    fun recordComponentStart(
        instanceId: String,
        processName: String,
        componentName: String,
        componentType: ComponentType
    ) {
        val key = processKey(instanceId, processName)
        val record = processRecords[key] ?: return

        record.activeComponents.add(ActiveComponent(componentName, componentType))
        record.lastActivityAt = System.currentTimeMillis()

        if (record.state == ProcessState.CREATED) {
            record.state = ProcessState.RUNNING
        }

        // Auto-adjust OOM based on component type
        when (componentType) {
            ComponentType.ACTIVITY -> updateOomAdj(instanceId, processName, OOM_ADJ_FOREGROUND)
            ComponentType.SERVICE -> {
                if (record.oomAdj > OOM_ADJ_SERVICE) {
                    updateOomAdj(instanceId, processName, OOM_ADJ_SERVICE)
                }
            }
            ComponentType.CONTENT_PROVIDER -> {
                if (record.oomAdj > OOM_ADJ_PERCEPTIBLE) {
                    updateOomAdj(instanceId, processName, OOM_ADJ_PERCEPTIBLE)
                }
            }
            ComponentType.BROADCAST_RECEIVER -> {
                // Receivers don't keep processes alive long-term
            }
        }

        Timber.tag(TAG).d("Component started: $componentName in $key (type=$componentType)")
    }

    /**
     * Record a component being stopped in a process.
     */
    fun recordComponentStop(
        instanceId: String,
        processName: String,
        componentName: String
    ) {
        val key = processKey(instanceId, processName)
        val record = processRecords[key] ?: return

        record.activeComponents.removeAll { it.name == componentName }
        record.lastActivityAt = System.currentTimeMillis()

        // If no more active components, set to cached
        if (record.activeComponents.isEmpty()) {
            updateOomAdj(instanceId, processName, OOM_ADJ_CACHED_APP)
        }

        Timber.tag(TAG).d("Component stopped: $componentName in $key (remaining=${record.activeComponents.size})")
    }

    /**
     * Get a dump of all process state for debugging.
     */
    fun dumpProcesses(): String {
        val sb = StringBuilder()
        sb.appendLine("=== VirtualMultiProcessManager Dump ===")
        sb.appendLine("Total records: ${processRecords.size}")
        sb.appendLine("Active slots: ${slotAllocation.size}/$MAX_PROCESS_SLOTS")
        sb.appendLine("ClassLoaders: ${processClassLoaders.size}")
        sb.appendLine("Applications: ${processApplications.size}")
        sb.appendLine("Binder registrations: ${binderRegistry.size}")
        sb.appendLine()

        for ((key, record) in processRecords) {
            sb.appendLine("[$key]")
            sb.appendLine("  PID: ${record.pid}, UID: ${record.uid}, Slot: ${record.processSlot}")
            sb.appendLine("  State: ${record.state}, OOM: ${record.oomAdj}")
            sb.appendLine("  Main: ${record.isMainProcess}")
            sb.appendLine("  ClassLoader: ${processClassLoaders.containsKey(key)}")
            sb.appendLine("  Application: ${processApplications.containsKey(key)}")
            sb.appendLine("  Components: ${record.activeComponents.size}")
            record.activeComponents.forEach { comp ->
                sb.appendLine("    - ${comp.name} (${comp.type})")
            }
            sb.appendLine("  Deaths: ${record.deathCount}")
            sb.appendLine()
        }

        return sb.toString()
    }

    // ==================== Per-Process ClassLoader Isolation ====================

    /**
     * Set the ClassLoader for a specific process.
     * Each guest app process should have its own DexClassLoader to prevent
     * class sharing between processes (matching real Android behavior).
     *
     * @param instanceId The virtual app instance
     * @param processName The android:process name (or default)
     * @param classLoader The DexClassLoader for this process
     */
    fun setClassLoaderForProcess(
        instanceId: String,
        processName: String,
        classLoader: ClassLoader
    ) {
        val key = processKey(instanceId, processName)
        processClassLoaders[key] = classLoader
        Timber.tag(TAG).d("ClassLoader set for process $key")
    }

    /**
     * Get the ClassLoader for a specific process.
     * Falls back to the main process ClassLoader if the sub-process doesn't have one.
     */
    fun getClassLoaderForProcess(instanceId: String, processName: String): ClassLoader? {
        val key = processKey(instanceId, processName)
        return processClassLoaders[key]
            ?: mainProcesses[instanceId]?.let { processClassLoaders[processKey(instanceId, it.processName)] }
    }

    /**
     * Get the ClassLoader for a component by looking up its declared process.
     */
    fun getClassLoaderForComponent(instanceId: String, componentName: String): ClassLoader? {
        val result = getProcessForComponent(instanceId, componentName)
        val processName = (result as? VmResult.Success)?.data?.processName ?: return null
        return getClassLoaderForProcess(instanceId, processName)
    }

    // ==================== Per-Process Application Instance ====================

    /**
     * Set the Application instance for a specific process.
     * In real Android, each process creates its own Application instance.
     */
    fun setApplicationForProcess(
        instanceId: String,
        processName: String,
        application: android.app.Application
    ) {
        val key = processKey(instanceId, processName)
        processApplications[key] = application
        Timber.tag(TAG).d("Application set for process $key: ${application::class.java.name}")
    }

    /**
     * Get the Application instance for a specific process.
     */
    fun getApplicationForProcess(instanceId: String, processName: String): android.app.Application? {
        val key = processKey(instanceId, processName)
        return processApplications[key]
    }

    /**
     * Create and initialize an Application instance for a guest process.
     * Mirrors real Android's LoadedApk.makeApplication() behavior.
     *
     * @return The created Application instance, or null on failure
     */
    fun createApplicationForProcess(
        instanceId: String,
        processName: String,
        applicationClassName: String?,
        context: Context
    ): android.app.Application? {
        val key = processKey(instanceId, processName)

        // Return existing if already created
        processApplications[key]?.let { return it }

        val cl = getClassLoaderForProcess(instanceId, processName) ?: return null
        val appClass = applicationClassName ?: "android.app.Application"

        return try {
            val clazz = cl.loadClass(appClass)
            val app = clazz.getDeclaredConstructor().newInstance() as android.app.Application

            // Attach base context
            val attachMethod = android.app.Application::class.java.getDeclaredMethod(
                "attach", Context::class.java
            )
            attachMethod.isAccessible = true
            attachMethod.invoke(app, context)

            processApplications[key] = app
            Timber.tag(TAG).i("Application created for $key: $appClass")
            app
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to create Application for $key ($appClass)")
            null
        }
    }

    // ==================== Cross-Process Binder IPC ====================

    /**
     * Register a Binder service for cross-process IPC.
     * When a guest app's service publishes a Binder, other processes
     * of the same app can bind to it.
     *
     * @param instanceId The virtual app instance
     * @param interfaceName The Binder interface descriptor (e.g., AIDL interface name)
     * @param binder The IBinder to register
     */
    fun registerBinder(instanceId: String, interfaceName: String, binder: android.os.IBinder) {
        val key = "$instanceId:$interfaceName"
        binderRegistry[key] = binder
        Timber.tag(TAG).d("Binder registered: $key")

        // Fulfill any pending bind requests
        pendingBindRequests.remove(key)?.forEach { callback ->
            try {
                callback(binder)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error fulfilling pending bind for $key")
            }
        }
    }

    /**
     * Unregister a Binder service.
     */
    fun unregisterBinder(instanceId: String, interfaceName: String) {
        val key = "$instanceId:$interfaceName"
        binderRegistry.remove(key)
        pendingBindRequests.remove(key)
        Timber.tag(TAG).d("Binder unregistered: $key")
    }

    /**
     * Look up a registered Binder for cross-process IPC.
     * Returns null if the service hasn't published its Binder yet.
     */
    fun lookupBinder(instanceId: String, interfaceName: String): android.os.IBinder? {
        val key = "$instanceId:$interfaceName"
        return binderRegistry[key]
    }

    /**
     * Request to bind to a cross-process Binder.
     * If the Binder isn't available yet, the callback is queued.
     *
     * @param instanceId The virtual app instance
     * @param interfaceName The Binder interface descriptor
     * @param callback Called when the Binder becomes available
     */
    fun requestBind(
        instanceId: String,
        interfaceName: String,
        callback: (android.os.IBinder) -> Unit
    ) {
        val key = "$instanceId:$interfaceName"

        // Check if already available
        val existing = binderRegistry[key]
        if (existing != null) {
            callback(existing)
            return
        }

        // Queue for later
        pendingBindRequests.getOrPut(key) { mutableListOf() }.add(callback)
        Timber.tag(TAG).d("Bind request queued: $key")
    }

    /**
     * Get all registered Binder interfaces for an instance.
     */
    fun getRegisteredBinders(instanceId: String): Map<String, android.os.IBinder> {
        return binderRegistry
            .filter { it.key.startsWith("$instanceId:") }
            .mapKeys { it.key.removePrefix("$instanceId:") }
    }

    /**
     * Clean up all process state.
     */
    fun cleanupAll() {
        val instances = mainProcesses.keys.toList()
        instances.forEach { killAllProcesses(it) }
        processRecords.clear()
        slotAllocation.clear()
        pidToProcess.clear()
        mainProcesses.clear()
        declaredProcesses.clear()
        componentProcessMap.clear()
        processClassLoaders.clear()
        processApplications.clear()
        binderRegistry.clear()
        pendingBindRequests.clear()
        initialized = false
        Timber.tag(TAG).i("VirtualMultiProcessManager cleaned up")
    }

    // ====================================================================
    // Internal helpers
    // ====================================================================

    private fun processKey(instanceId: String, processName: String): String {
        return "$instanceId:$processName"
    }

    private fun isMainProcess(instanceId: String, processName: String): Boolean {
        // Main process has the same name as the package or is the first process
        val packageName = instanceId.substringBefore("_")
        return processName == packageName ||
                processName.isEmpty() ||
                processName == "main" ||
                !processName.startsWith(":")
    }

    private fun getDefaultProcessName(instanceId: String): String {
        return instanceId.substringBefore("_")
    }

    private fun allocateSlot(processKey: String): Int {
        // Check if already allocated
        for ((slot, key) in slotAllocation) {
            if (key == processKey) return slot
        }

        // Find first available slot
        for (i in 0 until MAX_PROCESS_SLOTS) {
            if (!slotAllocation.containsKey(i)) {
                slotAllocation[i] = processKey
                Timber.tag(TAG).d("Slot $i allocated for $processKey")
                return i
            }
        }

        return -1 // No slots available
    }

    private fun releaseSlot(slot: Int) {
        val key = slotAllocation.remove(slot)
        if (key != null) {
            Timber.tag(TAG).d("Slot $slot released (was $key)")
        }
    }

    private fun generateVirtualUid(instanceId: String, slot: Int): Int {
        // Generate a deterministic UID based on instance and slot
        // Real Android UIDs are 10000+ for apps
        val hash = instanceId.hashCode().let { if (it < 0) -it else it }
        return 10000 + (hash % 90000) + slot
    }

    private fun killProcess(record: ProcessRecord): Boolean {
        return try {
            val key = processKey(record.instanceId, record.processName)
            Timber.tag(TAG).d("Killing process: $key (pid=${record.pid})")

            record.state = ProcessState.DEAD
            record.diedAt = System.currentTimeMillis()
            record.activeComponents.clear()

            // Remove PID mapping
            pidToProcess.remove(record.pid)

            // Release slot
            releaseSlot(record.processSlot)

            // Clean up per-process ClassLoader
            processClassLoaders.remove(key)

            // Clean up per-process Application instance
            processApplications.remove(key)

            // Clean up Binder registrations for this process
            val binderPrefix = "${record.instanceId}:"
            binderRegistry.keys.filter { it.startsWith(binderPrefix) }.forEach {
                binderRegistry.remove(it)
                pendingBindRequests.remove(it)
            }

            // Remove from records
            processRecords.remove(key)

            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to kill process: ${record.processName}")
            false
        }
    }

    private fun killAllSubProcesses(instanceId: String) {
        val subProcesses = processRecords.values.filter {
            it.instanceId == instanceId && !it.isMainProcess && it.state != ProcessState.DEAD
        }

        for (record in subProcesses) {
            Timber.tag(TAG).d("Killing sub-process: ${record.processName} (due to main process death)")
            killProcess(record)
        }
    }
}

// ========================================================================
// Data classes
// ========================================================================

/**
 * Represents a virtual app process.
 */
data class ProcessRecord(
    val instanceId: String,
    val processName: String,
    val processSlot: Int,
    val pid: Int,
    val uid: Int,
    var state: ProcessState = ProcessState.CREATED,
    var oomAdj: Int = VirtualMultiProcessManager.OOM_ADJ_CACHED_APP,
    var processState: Int = VirtualMultiProcessManager.PROCESS_STATE_CACHED,
    val isMainProcess: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    var lastActivityAt: Long = System.currentTimeMillis(),
    var diedAt: Long = 0,
    var deathCount: Int = 0,
    val activeComponents: MutableList<ActiveComponent> = mutableListOf()
) {
    /** Check if this process is in the foreground. */
    val isForeground: Boolean
        get() = oomAdj <= VirtualMultiProcessManager.OOM_ADJ_FOREGROUND

    /** Check if this process is visible to the user. */
    val isVisible: Boolean
        get() = oomAdj <= VirtualMultiProcessManager.OOM_ADJ_VISIBLE

    /** Check if this process is alive. */
    val isAlive: Boolean
        get() = state != ProcessState.DEAD

    /** Check if this process is running. */
    val isRunning: Boolean
        get() = state == ProcessState.RUNNING

    /** Get the stub process name (e.g., ":p0"). */
    val stubProcessName: String
        get() = ":p$processSlot"

    /** Uptime in milliseconds. */
    val uptimeMs: Long
        get() = if (isAlive) System.currentTimeMillis() - createdAt else (diedAt - createdAt)
}

/**
 * Process lifecycle state.
 */
enum class ProcessState {
    /** Process record created, not yet started */
    CREATED,
    /** Process is running */
    RUNNING,
    /** Process is being killed */
    DYING,
    /** Process has died */
    DEAD
}

/**
 * An active component in a process.
 */
data class ActiveComponent(
    val name: String,
    val type: ComponentType,
    val startedAt: Long = System.currentTimeMillis()
)

/**
 * Android component types.
 */
enum class ComponentType {
    ACTIVITY,
    SERVICE,
    CONTENT_PROVIDER,
    BROADCAST_RECEIVER
}

/**
 * Listener for process death events.
 */
interface ProcessDeathListener {
    fun onProcessDied(instanceId: String, processName: String, record: ProcessRecord)
}

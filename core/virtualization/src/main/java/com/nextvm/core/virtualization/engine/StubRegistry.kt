package com.nextvm.core.virtualization.engine

import com.nextvm.core.model.StubMapping
import com.nextvm.core.model.VirtualConstants
import timber.log.Timber

/**
 * StubRegistry manages the mapping between process slots and
 * pre-declared stub components in AndroidManifest.xml.
 *
 * Each process slot (e.g., :p0, :p1) has a set of stub Activities,
 * Services, ContentProviders, and BroadcastReceivers.
 *
 * When a guest app needs to start a component, we resolve it to
 * the appropriate stub, and in ActivityThread.mH hook, we swap
 * the class back to the guest's real component.
 */
class StubRegistry {

    companion object {
        private const val TAG = "StubRegistry"
        private const val STUB_BASE = VirtualConstants.STUB_PREFIX
    }

    // Pre-built mappings for each process slot
    private val slots = mutableMapOf<Int, StubMapping>()

    // Track which stubs are currently in use (class name -> true)
    private val inUseStubs = mutableSetOf<String>()

    init {
        buildSlotMappings()
    }

    /**
     * Resolve a stub Activity for the given process slot and launch mode.
     *
     * @param slot Process slot index (0, 1, 2...)
     * @param launchMode Android launch mode: "standard", "singleTop", "singleTask", "singleInstance"
     * @return Fully qualified stub class name, or null if no slot available
     */
    fun resolveStub(slot: Int, launchMode: String): String? {
        val mapping = slots[slot]
        if (mapping == null) {
            Timber.tag(TAG).e("No stub mapping for slot $slot")
            return null
        }

        val candidates = when (launchMode) {
            "singleTop" -> mapping.stubActivitySingleTop
            "singleTask" -> mapping.stubActivitySingleTask
            "singleInstance" -> mapping.stubActivitySingleInstance
            else -> mapping.stubActivityStandard // "standard" is the default
        }

        val available = candidates.firstOrNull { it !in inUseStubs }
        if (available != null) {
            inUseStubs.add(available)
            Timber.tag(TAG).d("Resolved stub: $available (slot=$slot, mode=$launchMode)")
        } else {
            Timber.tag(TAG).w("No available $launchMode stubs for slot $slot")
        }
        return available
    }

    /**
     * Resolve a stub Service for the given process slot.
     */
    fun resolveServiceStub(slot: Int): String? {
        val mapping = slots[slot] ?: return null
        return mapping.stubServices.firstOrNull { it !in inUseStubs }?.also {
            inUseStubs.add(it)
        }
    }

    /**
     * Resolve a stub ContentProvider for the given process slot.
     */
    fun resolveProviderStub(slot: Int): String? {
        val mapping = slots[slot] ?: return null
        return mapping.stubProviders.firstOrNull { it !in inUseStubs }?.also {
            inUseStubs.add(it)
        }
    }

    /**
     * Release a stub back to the pool.
     */
    fun releaseStub(stubClassName: String) {
        inUseStubs.remove(stubClassName)
        Timber.tag(TAG).d("Released stub: $stubClassName")
    }

    /**
     * Release all stubs for a given process slot.
     */
    fun releaseAllStubsForSlot(slot: Int) {
        val mapping = slots[slot] ?: return
        mapping.allComponents().forEach { inUseStubs.remove(it) }
        Timber.tag(TAG).d("Released all stubs for slot $slot")
    }

    /**
     * Check if a class name is one of our stubs.
     */
    fun isStubComponent(className: String): Boolean {
        return className.startsWith(STUB_BASE)
    }

    /**
     * Get the process slot for a stub class name.
     */
    fun getSlotForStub(className: String): Int {
        for ((slot, mapping) in slots) {
            if (className in mapping.allComponents()) return slot
        }
        return -1
    }

    /**
     * Get the stub mapping for a process slot.
     */
    fun getMapping(slot: Int): StubMapping? = slots[slot]

    /**
     * Build stub mappings for all process slots.
     * Generates class names matching what's declared in AndroidManifest.xml.
     */
    private fun buildSlotMappings() {
        for (slot in 0 until VirtualConstants.MAX_PROCESS_SLOTS) {
            val prefix = "$STUB_BASE.P${slot}"

            val standardActivities = (0 until VirtualConstants.STUB_ACTIVITIES_STANDARD).map {
                "${prefix}\$Standard${String.format("%02d", it)}"
            }
            val singleTopActivities = (0 until VirtualConstants.STUB_ACTIVITIES_SINGLE_TOP).map {
                "${prefix}\$SingleTop${String.format("%02d", it)}"
            }
            val singleTaskActivities = (0 until VirtualConstants.STUB_ACTIVITIES_SINGLE_TASK).map {
                "${prefix}\$SingleTask${String.format("%02d", it)}"
            }
            val singleInstanceActivities = (0 until VirtualConstants.STUB_ACTIVITIES_SINGLE_INSTANCE).map {
                "${prefix}\$SingleInstance${String.format("%02d", it)}"
            }

            val services = (0 until VirtualConstants.STUB_SERVICES).map {
                "$STUB_BASE.P${slot}\$S${String.format("%02d", it)}"
            }
            val providers = (0 until VirtualConstants.STUB_PROVIDERS).map {
                "$STUB_BASE.P${slot}\$CP${String.format("%02d", it)}"
            }
            val receivers = (0 until VirtualConstants.STUB_RECEIVERS).map {
                "$STUB_BASE.P${slot}\$R${String.format("%02d", it)}"
            }

            slots[slot] = StubMapping(
                processSlot = slot,
                stubActivityStandard = standardActivities,
                stubActivitySingleTop = singleTopActivities,
                stubActivitySingleTask = singleTaskActivities,
                stubActivitySingleInstance = singleInstanceActivities,
                stubServices = services,
                stubProviders = providers,
                stubReceivers = receivers
            )
        }

        Timber.tag(TAG).i(
            "Built stub mappings: ${VirtualConstants.MAX_PROCESS_SLOTS} slots, " +
            "${slots[0]?.allActivities()?.size ?: 0} activities/slot"
        )
    }
}

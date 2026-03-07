package com.nextvm.core.virtualization.engine

import com.nextvm.core.model.ProcessSlot
import com.nextvm.core.model.VirtualConstants
import timber.log.Timber

/**
 * VirtualProcessManager manages the allocation and lifecycle 
 * of virtual process slots.
 *
 * Each guest app runs in its own process slot (:p0, :p1, :p2...),
 * which maps to a set of pre-declared stub components in the manifest.
 *
 * Max slots = VirtualConstants.MAX_PROCESS_SLOTS
 */
class VirtualProcessManager(
    private val maxSlots: Int = VirtualConstants.MAX_PROCESS_SLOTS
) {
    companion object {
        private const val TAG = "VProcessManager"
    }

    // Slot index -> ProcessSlot info
    private val slots = Array(maxSlots) { index ->
        ProcessSlot(
            slotIndex = index,
            processName = ":p$index"
        )
    }

    /**
     * Allocate a free process slot for an app instance.
     *
     * @param instanceId The app instance to assign
     * @return Slot index (0+) on success, -1 if no free slots
     */
    fun allocateSlot(instanceId: String): Int {
        // Check if already allocated
        val existing = slots.indexOfFirst { it.assignedInstanceId == instanceId }
        if (existing >= 0) {
            Timber.tag(TAG).d("Slot $existing already allocated for $instanceId")
            return existing
        }

        // Find first free slot
        val freeIndex = slots.indexOfFirst { !it.isOccupied }
        if (freeIndex < 0) {
            Timber.tag(TAG).e("No free process slots (max: $maxSlots)")
            return -1
        }

        slots[freeIndex] = slots[freeIndex].copy(
            assignedInstanceId = instanceId,
            isOccupied = true
        )

        Timber.tag(TAG).i("Allocated slot $freeIndex (${slots[freeIndex].processName}) for $instanceId")
        return freeIndex
    }

    /**
     * Release a process slot.
     */
    fun releaseSlot(slotIndex: Int) {
        if (slotIndex < 0 || slotIndex >= maxSlots) return
        val old = slots[slotIndex]
        slots[slotIndex] = ProcessSlot(
            slotIndex = slotIndex,
            processName = ":p$slotIndex"
        )
        Timber.tag(TAG).i("Released slot $slotIndex (was: ${old.assignedInstanceId})")
    }

    /**
     * Release a slot by instance ID.
     */
    fun releaseSlotForInstance(instanceId: String) {
        val index = slots.indexOfFirst { it.assignedInstanceId == instanceId }
        if (index >= 0) releaseSlot(index)
    }

    /**
     * Get the slot index for an instance ID.
     */
    fun getSlotForInstance(instanceId: String): Int =
        slots.indexOfFirst { it.assignedInstanceId == instanceId }

    /**
     * Get the instance ID for a slot.
     */
    fun getInstanceForSlot(slotIndex: Int): String? =
        if (slotIndex in 0 until maxSlots) slots[slotIndex].assignedInstanceId else null

    /**
     * Set the PID for a slot (when process actually starts).
     */
    fun setSlotPid(slotIndex: Int, pid: Int) {
        if (slotIndex in 0 until maxSlots) {
            slots[slotIndex] = slots[slotIndex].copy(pid = pid)
        }
    }

    /**
     * Get the slot for a PID.
     */
    fun getSlotForPid(pid: Int): Int =
        slots.indexOfFirst { it.pid == pid }

    /**
     * Get count of occupied slots.
     */
    fun getOccupiedCount(): Int = slots.count { it.isOccupied }

    /**
     * Get count of free slots.
     */
    fun getFreeCount(): Int = slots.count { !it.isOccupied }

    /**
     * Get all slot info.
     */
    fun getAllSlots(): List<ProcessSlot> = slots.toList()
}

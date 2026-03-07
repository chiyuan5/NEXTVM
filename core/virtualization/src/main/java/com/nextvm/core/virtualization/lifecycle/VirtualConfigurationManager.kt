package com.nextvm.core.virtualization.lifecycle

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.view.Surface
import com.nextvm.core.common.AndroidCompat
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VirtualConfigurationManager — Configuration change handling for guest apps.
 *
 * Mirrors:
 *   - ActivityTaskManagerService.updateConfigurationLocked() in frameworks/base/services/core
 *   - ActivityRecord.shouldRelaunchLocked() — determines destroy+recreate vs onConfigChanged
 *   - Configuration.diff() — computes config change flags
 *   - ActivityInfo.CONFIG_* flags
 *
 * Handles:
 *   - Tracking configuration per instance
 *   - Determining if activity should be recreated or receive onConfigurationChanged()
 *     based on android:configChanges declared in manifest
 *   - Orientation changes
 *   - Dark mode / night mode changes
 *   - Locale changes
 *   - Screen density changes
 *   - Keyboard visibility changes
 *   - Multi-window mode changes
 *   - Font scale changes
 */
@Singleton
class VirtualConfigurationManager @Inject constructor() {

    companion object {
        private const val TAG = "VConfigMgr"

        /**
         * Config change flags mapping — mirrors ActivityInfo.CONFIG_* constants.
         *
         * From ActivityInfo.java in frameworks/base/core/java/android/content/pm/ActivityInfo.java:
         *   CONFIG_MCC = 0x0001
         *   CONFIG_MNC = 0x0002
         *   CONFIG_LOCALE = 0x0004
         *   CONFIG_TOUCHSCREEN = 0x0008
         *   CONFIG_KEYBOARD = 0x0010
         *   CONFIG_KEYBOARD_HIDDEN = 0x0020
         *   CONFIG_NAVIGATION = 0x0040
         *   CONFIG_ORIENTATION = 0x0080
         *   CONFIG_SCREEN_LAYOUT = 0x0100
         *   CONFIG_UI_MODE = 0x0200
         *   CONFIG_SCREEN_SIZE = 0x0400
         *   CONFIG_SMALLEST_SCREEN_SIZE = 0x0800
         *   CONFIG_DENSITY = 0x1000
         *   CONFIG_LAYOUT_DIRECTION = 0x2000
         *   CONFIG_COLOR_MODE = 0x4000 (API 26+)
         *   CONFIG_FONT_SCALE = 0x40000000
         */
        const val CONFIG_MCC = 0x0001
        const val CONFIG_MNC = 0x0002
        const val CONFIG_LOCALE = 0x0004
        const val CONFIG_TOUCHSCREEN = 0x0008
        const val CONFIG_KEYBOARD = 0x0010
        const val CONFIG_KEYBOARD_HIDDEN = 0x0020
        const val CONFIG_NAVIGATION = 0x0040
        const val CONFIG_ORIENTATION = 0x0080
        const val CONFIG_SCREEN_LAYOUT = 0x0100
        const val CONFIG_UI_MODE = 0x0200
        const val CONFIG_SCREEN_SIZE = 0x0400
        const val CONFIG_SMALLEST_SCREEN_SIZE = 0x0800
        const val CONFIG_DENSITY = 0x1000
        const val CONFIG_LAYOUT_DIRECTION = 0x2000
        const val CONFIG_COLOR_MODE = 0x4000
        const val CONFIG_FONT_SCALE = 0x40000000.toInt()

        /**
         * The default configChanges value for NEXTVM stub activities.
         * Includes ALL flags to prevent unwanted restarts.
         */
        const val STUB_DEFAULT_CONFIG_CHANGES =
            CONFIG_MCC or CONFIG_MNC or CONFIG_LOCALE or CONFIG_TOUCHSCREEN or
            CONFIG_KEYBOARD or CONFIG_KEYBOARD_HIDDEN or CONFIG_NAVIGATION or
            CONFIG_ORIENTATION or CONFIG_SCREEN_LAYOUT or CONFIG_UI_MODE or
            CONFIG_SCREEN_SIZE or CONFIG_SMALLEST_SCREEN_SIZE or
            CONFIG_DENSITY or CONFIG_LAYOUT_DIRECTION or CONFIG_FONT_SCALE or
            CONFIG_COLOR_MODE
    }

    private var initialized = false
    private lateinit var appContext: android.content.Context

    fun initialize(context: android.content.Context) {
        if (initialized) return
        appContext = context.applicationContext
        initialized = true
        Timber.tag(TAG).i("VirtualConfigurationManager initialized — config change handling ready")
    }

    /**
     * Tracks configuration state for a guest app instance.
     */
    data class InstanceConfigState(
        val instanceId: String,
        var currentConfig: Configuration,
        /** configChanges declared per activity in the manifest */
        val activityConfigChanges: MutableMap<String, Int> = mutableMapOf()
    )

    /**
     * Result of a config change evaluation.
     */
    data class ConfigChangeResult(
        val activityClassName: String,
        val changeFlags: Int,
        val shouldRecreate: Boolean,
        val handledFlags: Int,
        val unhandledFlags: Int
    )

    // instanceId -> InstanceConfigState
    private val configStates = ConcurrentHashMap<String, InstanceConfigState>()

    /**
     * Register an instance's initial configuration.
     *
     * @param instanceId Guest app instance ID
     * @param config Current system configuration
     */
    fun registerInstance(instanceId: String, config: Configuration) {
        configStates[instanceId] = InstanceConfigState(
            instanceId = instanceId,
            currentConfig = Configuration(config)
        )
        Timber.tag(TAG).d("Configuration registered for $instanceId")
    }

    /**
     * Register the configChanges flags for a guest activity.
     *
     * This comes from parsing the android:configChanges attribute in the guest app's
     * AndroidManifest.xml. Activities that declare a config change flag will receive
     * onConfigurationChanged() instead of being destroyed and recreated.
     *
     * @param instanceId Guest app instance ID
     * @param activityClassName Fully qualified Activity class name
     * @param configChanges Bitmask of CONFIG_* flags the activity handles
     */
    fun registerActivityConfigChanges(
        instanceId: String,
        activityClassName: String,
        configChanges: Int
    ) {
        val state = configStates.getOrPut(instanceId) {
            InstanceConfigState(instanceId, Configuration())
        }
        state.activityConfigChanges[activityClassName] = configChanges

        Timber.tag(TAG).d("Registered configChanges for $activityClassName: 0x${configChanges.toString(16)}")
    }

    /**
     * Process a system configuration change.
     *
     * Determines for each running activity whether it should:
     *   1. Be destroyed and recreated (activity didn't declare the changed config)
     *   2. Receive onConfigurationChanged() (activity declared it handles this config)
     *
     * Mirrors ActivityRecord.shouldRelaunchLocked() logic:
     *   diff = oldConfig.diff(newConfig)
     *   unhandled = diff & ~activity.configChanges
     *   if (unhandled != 0) → recreate
     *   else → onConfigurationChanged()
     *
     * @param instanceId Guest app instance ID
     * @param newConfig The new system configuration
     * @return List of ConfigChangeResult, one per registered activity
     */
    fun onConfigurationChanged(
        instanceId: String,
        newConfig: Configuration
    ): List<ConfigChangeResult> {
        val state = configStates[instanceId] ?: run {
            Timber.tag(TAG).w("No config state for $instanceId")
            return emptyList()
        }

        val oldConfig = state.currentConfig
        val diff = computeConfigDiff(oldConfig, newConfig)

        if (diff == 0) {
            Timber.tag(TAG).d("No config diff for $instanceId")
            return emptyList()
        }

        Timber.tag(TAG).i("Config changed for $instanceId: diff=0x${diff.toString(16)} " +
            "(${describeConfigFlags(diff)})")

        // Update stored config
        state.currentConfig = Configuration(newConfig)

        // Evaluate each registered activity
        val results = mutableListOf<ConfigChangeResult>()
        for ((activityName, handlesFlags) in state.activityConfigChanges) {
            val handledFlags = diff and handlesFlags
            val unhandledFlags = diff and handlesFlags.inv()
            val shouldRecreate = unhandledFlags != 0

            results.add(
                ConfigChangeResult(
                    activityClassName = activityName,
                    changeFlags = diff,
                    shouldRecreate = shouldRecreate,
                    handledFlags = handledFlags,
                    unhandledFlags = unhandledFlags
                )
            )

            Timber.tag(TAG).d("  $activityName: recreate=$shouldRecreate " +
                "(handled=0x${handledFlags.toString(16)}, unhandled=0x${unhandledFlags.toString(16)})")
        }

        return results
    }

    /**
     * Dispatch onConfigurationChanged to an Activity if it handles the change.
     *
     * @param activity The running Activity
     * @param newConfig New configuration
     * @param activityClassName The guest app's original Activity class name
     * @param instanceId Guest app instance ID
     * @return true if the activity handled the change, false if it should be recreated
     */
    fun dispatchConfigChange(
        activity: Activity,
        newConfig: Configuration,
        activityClassName: String,
        instanceId: String
    ): Boolean {
        val state = configStates[instanceId]
        val handlesFlags = state?.activityConfigChanges?.get(activityClassName)
            ?: STUB_DEFAULT_CONFIG_CHANGES // Stubs handle everything by default

        val oldConfig = state?.currentConfig ?: activity.resources.configuration
        val diff = computeConfigDiff(oldConfig, newConfig)

        if (diff == 0) return true

        val unhandled = diff and handlesFlags.inv()

        if (unhandled != 0) {
            Timber.tag(TAG).d("$activityClassName must be recreated (unhandled: 0x${unhandled.toString(16)})")
            return false
        }

        // Activity handles this change — dispatch directly
        try {
            activity.onConfigurationChanged(newConfig)
            Timber.tag(TAG).d("Dispatched config change to $activityClassName")
            return true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error dispatching config change to $activityClassName")
            return false
        }
    }

    /**
     * Compute the diff flags between two Configurations.
     *
     * Mirrors Configuration.diff() but also handles custom comparisons.
     */
    fun computeConfigDiff(oldConfig: Configuration, newConfig: Configuration): Int {
        var diff = 0

        // Use Configuration.diff() first (handles most checks)
        try {
            diff = oldConfig.diff(newConfig)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Configuration.diff() failed, computing manually")
            diff = computeManualDiff(oldConfig, newConfig)
        }

        return diff
    }

    /**
     * Manual diff computation fallback.
     */
    private fun computeManualDiff(old: Configuration, new: Configuration): Int {
        var diff = 0

        if (old.orientation != new.orientation) diff = diff or CONFIG_ORIENTATION
        if (old.screenHeightDp != new.screenHeightDp || old.screenWidthDp != new.screenWidthDp) {
            diff = diff or CONFIG_SCREEN_SIZE
        }
        if (old.smallestScreenWidthDp != new.smallestScreenWidthDp) {
            diff = diff or CONFIG_SMALLEST_SCREEN_SIZE
        }
        if (old.densityDpi != new.densityDpi) diff = diff or CONFIG_DENSITY
        if (old.keyboard != new.keyboard) diff = diff or CONFIG_KEYBOARD
        if (old.keyboardHidden != new.keyboardHidden || old.hardKeyboardHidden != new.hardKeyboardHidden) {
            diff = diff or CONFIG_KEYBOARD_HIDDEN
        }
        if (old.navigation != new.navigation) diff = diff or CONFIG_NAVIGATION
        if (old.touchscreen != new.touchscreen) diff = diff or CONFIG_TOUCHSCREEN
        if (old.screenLayout != new.screenLayout) diff = diff or CONFIG_SCREEN_LAYOUT
        if (old.uiMode != new.uiMode) diff = diff or CONFIG_UI_MODE
        if (old.fontScale != new.fontScale) diff = diff or CONFIG_FONT_SCALE
        if (old.mcc != new.mcc) diff = diff or CONFIG_MCC
        if (old.mnc != new.mnc) diff = diff or CONFIG_MNC

        // Locale
        @Suppress("DEPRECATION")
        if (old.locale != new.locale) diff = diff or CONFIG_LOCALE

        // Layout direction
        if (old.layoutDirection != new.layoutDirection) diff = diff or CONFIG_LAYOUT_DIRECTION

        // Color mode (API 26+)
        if (old.colorMode != new.colorMode) diff = diff or CONFIG_COLOR_MODE

        return diff
    }

    // ---------- Orientation Helpers ----------

    /**
     * Check if the config change includes an orientation change.
     */
    fun isOrientationChange(changeFlags: Int): Boolean =
        (changeFlags and CONFIG_ORIENTATION) != 0

    /**
     * Check if the config change includes a dark mode change.
     */
    fun isDarkModeChange(changeFlags: Int): Boolean {
        // UI_MODE includes night mode (dark mode)
        return (changeFlags and CONFIG_UI_MODE) != 0
    }

    /**
     * Check if the config change includes a locale change.
     */
    fun isLocaleChange(changeFlags: Int): Boolean =
        (changeFlags and CONFIG_LOCALE) != 0

    /**
     * Check if the config change includes a density change.
     */
    fun isDensityChange(changeFlags: Int): Boolean =
        (changeFlags and CONFIG_DENSITY) != 0

    /**
     * Check if the config change includes a keyboard visibility change.
     */
    fun isKeyboardChange(changeFlags: Int): Boolean =
        (changeFlags and CONFIG_KEYBOARD_HIDDEN) != 0

    /**
     * Check if the config change represents entering/exiting multi-window mode.
     * Multi-window changes manifest as screen size + smallest screen size changes.
     */
    fun isMultiWindowChange(changeFlags: Int): Boolean {
        return (changeFlags and (CONFIG_SCREEN_SIZE or CONFIG_SMALLEST_SCREEN_SIZE)) ==
            (CONFIG_SCREEN_SIZE or CONFIG_SMALLEST_SCREEN_SIZE)
    }

    /**
     * Check if the config change includes a font scale change.
     */
    fun isFontScaleChange(changeFlags: Int): Boolean =
        (changeFlags and CONFIG_FONT_SCALE) != 0

    // ---------- Configuration Builders ----------

    /**
     * Create a Configuration for a specific orientation.
     */
    fun buildOrientationConfig(
        baseConfig: Configuration,
        orientation: Int
    ): Configuration {
        return Configuration(baseConfig).apply {
            this.orientation = orientation
        }
    }

    /**
     * Create a Configuration for dark/light mode.
     *
     * @param nightMode true for dark mode, false for light mode
     */
    fun buildDarkModeConfig(
        baseConfig: Configuration,
        nightMode: Boolean
    ): Configuration {
        return Configuration(baseConfig).apply {
            val current = this.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()
            this.uiMode = current or if (nightMode) {
                Configuration.UI_MODE_NIGHT_YES
            } else {
                Configuration.UI_MODE_NIGHT_NO
            }
        }
    }

    /**
     * Check if current configuration is in dark mode.
     */
    fun isDarkMode(config: Configuration): Boolean {
        return (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Get the current orientation from a Configuration.
     */
    fun getOrientation(config: Configuration): Int = config.orientation

    // ---------- Query & Cleanup ----------

    /**
     * Get the current configuration for an instance.
     */
    fun getCurrentConfig(instanceId: String): Configuration? =
        configStates[instanceId]?.currentConfig

    /**
     * Get the configChanges flags for a specific activity.
     */
    fun getActivityConfigChanges(instanceId: String, activityClassName: String): Int? =
        configStates[instanceId]?.activityConfigChanges?.get(activityClassName)

    /**
     * Remove configuration state for an instance.
     */
    fun unregisterInstance(instanceId: String) {
        configStates.remove(instanceId)
        Timber.tag(TAG).d("Configuration state removed for $instanceId")
    }

    /**
     * Clear all state.
     */
    fun clearAll() {
        configStates.clear()
        Timber.tag(TAG).i("All configuration state cleared")
    }

    // ---------- Debug Helpers ----------

    /**
     * Describe config change flags as a human-readable string.
     */
    fun describeConfigFlags(flags: Int): String {
        val parts = mutableListOf<String>()
        if (flags and CONFIG_MCC != 0) parts.add("MCC")
        if (flags and CONFIG_MNC != 0) parts.add("MNC")
        if (flags and CONFIG_LOCALE != 0) parts.add("LOCALE")
        if (flags and CONFIG_TOUCHSCREEN != 0) parts.add("TOUCHSCREEN")
        if (flags and CONFIG_KEYBOARD != 0) parts.add("KEYBOARD")
        if (flags and CONFIG_KEYBOARD_HIDDEN != 0) parts.add("KEYBOARD_HIDDEN")
        if (flags and CONFIG_NAVIGATION != 0) parts.add("NAVIGATION")
        if (flags and CONFIG_ORIENTATION != 0) parts.add("ORIENTATION")
        if (flags and CONFIG_SCREEN_LAYOUT != 0) parts.add("SCREEN_LAYOUT")
        if (flags and CONFIG_UI_MODE != 0) parts.add("UI_MODE")
        if (flags and CONFIG_SCREEN_SIZE != 0) parts.add("SCREEN_SIZE")
        if (flags and CONFIG_SMALLEST_SCREEN_SIZE != 0) parts.add("SMALLEST_SCREEN_SIZE")
        if (flags and CONFIG_DENSITY != 0) parts.add("DENSITY")
        if (flags and CONFIG_LAYOUT_DIRECTION != 0) parts.add("LAYOUT_DIRECTION")
        if (flags and CONFIG_COLOR_MODE != 0) parts.add("COLOR_MODE")
        if (flags and CONFIG_FONT_SCALE != 0) parts.add("FONT_SCALE")
        return if (parts.isEmpty()) "NONE" else parts.joinToString("|")
    }

    /**
     * Parse the android:configChanges string from manifest into a bitmask.
     * Used during APK manifest parsing.
     *
     * Example input: "orientation|screenSize|keyboardHidden"
     * Returns: CONFIG_ORIENTATION | CONFIG_SCREEN_SIZE | CONFIG_KEYBOARD_HIDDEN
     */
    fun parseConfigChangesString(configChangesStr: String): Int {
        var result = 0

        for (part in configChangesStr.split("|")) {
            result = result or when (part.trim().lowercase()) {
                "mcc" -> CONFIG_MCC
                "mnc" -> CONFIG_MNC
                "locale" -> CONFIG_LOCALE
                "touchscreen" -> CONFIG_TOUCHSCREEN
                "keyboard" -> CONFIG_KEYBOARD
                "keyboardhidden" -> CONFIG_KEYBOARD_HIDDEN
                "navigation" -> CONFIG_NAVIGATION
                "orientation" -> CONFIG_ORIENTATION
                "screenlayout" -> CONFIG_SCREEN_LAYOUT
                "uimode" -> CONFIG_UI_MODE
                "screensize" -> CONFIG_SCREEN_SIZE
                "smallestscreensize" -> CONFIG_SMALLEST_SCREEN_SIZE
                "density" -> CONFIG_DENSITY
                "layoutdirection" -> CONFIG_LAYOUT_DIRECTION
                "colormode" -> CONFIG_COLOR_MODE
                "fontscale" -> CONFIG_FONT_SCALE
                else -> {
                    Timber.tag(TAG).w("Unknown configChanges flag: $part")
                    0
                }
            }
        }

        return result
    }
}

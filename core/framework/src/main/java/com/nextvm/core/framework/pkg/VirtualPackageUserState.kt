package com.nextvm.core.framework.pkg

/**
 * Virtual package user state, adapted from Android 16's FrameworkPackageUserState.java.
 *
 * Source: android16-frameworks-base/core/java/android/content/pm/pkg/
 *   - FrameworkPackageUserState.java (89 lines — interface)
 *   - FrameworkPackageUserStateDefault.java (153 lines — default implementation)
 *
 * In the real Android OS, this tracks per-user state for each installed package:
 * whether it's installed, enabled, hidden, suspended, etc.
 *
 * NEXTVM uses this to maintain the same per-virtual-app state that the
 * real Android PackageManagerService tracks.
 */
data class VirtualPackageUserState(
    /** Whether the app is installed for this user */
    val isInstalled: Boolean = true,

    /** Component enabled state (DEFAULT, ENABLED, DISABLED, etc.) */
    val enabledState: Int = COMPONENT_ENABLED_STATE_DEFAULT,

    /** Whether the app is hidden (by device policy) */
    val isHidden: Boolean = false,

    /** Whether the app is suspended */
    val isSuspended: Boolean = false,

    /** Whether the app has been stopped (force-stopped) */
    val isStopped: Boolean = false,

    /** Whether this is an instant app */
    val isInstantApp: Boolean = false,

    /** Whether the app has never been launched */
    val isNotLaunched: Boolean = true,

    /** Install reason */
    val installReason: Int = INSTALL_REASON_UNKNOWN,

    /** Uninstall reason */
    val uninstallReason: Int = UNINSTALL_REASON_UNKNOWN,

    /** Distraction flags */
    val distractionFlags: Int = 0,

    /** Harmful app warning message */
    val harmfulAppWarning: String? = null,

    /** Last caller that disabled the app */
    val lastDisableAppCaller: String? = null,

    /** Splash screen theme */
    val splashScreenTheme: String? = null,

    /** Components explicitly enabled by user */
    val enabledComponents: Set<String> = emptySet(),

    /** Components explicitly disabled by user */
    val disabledComponents: Set<String> = emptySet()
) {
    /**
     * Check if a specific component is enabled.
     * Port of FrameworkPackageUserStateDefault.isComponentEnabled().
     */
    fun isComponentEnabled(componentName: String): Boolean {
        return componentName in enabledComponents
    }

    /**
     * Check if a specific component is disabled.
     * Port of FrameworkPackageUserStateDefault.isComponentDisabled().
     */
    fun isComponentDisabled(componentName: String): Boolean {
        return componentName in disabledComponents
    }

    companion object {
        // From PackageManager constants
        const val COMPONENT_ENABLED_STATE_DEFAULT = 0
        const val COMPONENT_ENABLED_STATE_ENABLED = 1
        const val COMPONENT_ENABLED_STATE_DISABLED = 2
        const val COMPONENT_ENABLED_STATE_DISABLED_USER = 3
        const val COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED = 4

        const val INSTALL_REASON_UNKNOWN = 0
        const val INSTALL_REASON_POLICY = 1
        const val INSTALL_REASON_DEVICE_RESTORE = 2
        const val INSTALL_REASON_DEVICE_SETUP = 3
        const val INSTALL_REASON_USER = 4

        const val UNINSTALL_REASON_UNKNOWN = 0
        const val UNINSTALL_REASON_USER_TYPE = 1

        /** Default state (mirrors FrameworkPackageUserStateDefault) */
        val DEFAULT = VirtualPackageUserState()
    }
}

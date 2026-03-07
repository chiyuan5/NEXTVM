package com.nextvm.core.virtualization.engine

import com.nextvm.core.common.findField
import com.nextvm.core.common.runSafe
import timber.log.Timber

/**
 * VirtualClassLoaderInjector — Injects guest ClassLoaders into ActivityThread.mPackages.
 *
 * The critical problem: Android's performLaunchActivity() creates Activity instances using
 * the ClassLoader from LoadedApk.mClassLoader (found via mPackages[packageName]).
 * If our guest ClassLoader isn't there, it falls back to the host ClassLoader which
 * doesn't know about guest app classes → ClassNotFoundException.
 *
 * Previous approach used Resources.setClassLoader() which doesn't exist on API 34+.
 * This injector goes directly to the source: LoadedApk.mClassLoader field.
 *
 * Strategy chain:
 *   1. Find LoadedApk in mPackages by guest package name → set mClassLoader
 *   2. If not found, find host LoadedApk, clone it, register under guest package name
 *   3. Fallback: Unsafe field write if standard reflection is blocked
 */
object VirtualClassLoaderInjector {

    private const val TAG = "CLInjector"

    /**
     * Inject the guest ClassLoader into ActivityThread's LoadedApk registry.
     *
     * @param packageName Guest app's package name
     * @param classLoader The guest ClassLoader (from GuestClassLoaderRegistry)
     * @param hostPackageName The host app's package name (com.nextvm.app)
     * @return true if injection succeeded
     */
    fun inject(
        packageName: String,
        classLoader: ClassLoader,
        hostPackageName: String
    ): Boolean {
        // Strategy 1: Direct LoadedApk.mClassLoader replacement
        if (injectViaLoadedApk(packageName, classLoader)) {
            Timber.tag(TAG).d("Strategy 1 succeeded: direct mClassLoader replacement for $packageName")
            return true
        }

        // Strategy 2: Clone host LoadedApk and register under guest package name
        if (cloneHostLoadedApk(packageName, classLoader, hostPackageName)) {
            Timber.tag(TAG).d("Strategy 2 succeeded: cloned host LoadedApk for $packageName")
            return true
        }

        // Strategy 3: Unsafe field manipulation
        if (injectViaUnsafe(packageName, classLoader, hostPackageName)) {
            Timber.tag(TAG).d("Strategy 3 succeeded: Unsafe injection for $packageName")
            return true
        }

        Timber.tag(TAG).e("All injection strategies failed for $packageName")
        return false
    }

    /**
     * Strategy 1: If a LoadedApk already exists for the guest package name,
     * just replace its mClassLoader field.
     */
    private fun injectViaLoadedApk(packageName: String, classLoader: ClassLoader): Boolean {
        return try {
            val activityThread = getActivityThread() ?: return false
            val mPackages = getMPackages(activityThread) ?: return false

            val loadedApkRef = mPackages[packageName]
            if (loadedApkRef == null) {
                Timber.tag(TAG).d("No LoadedApk found for $packageName in mPackages")
                return false
            }

            val loadedApk = (loadedApkRef as? java.lang.ref.WeakReference<*>)?.get()
                ?: return false

            val mClassLoaderField = findField(loadedApk::class.java, "mClassLoader")
            if (mClassLoaderField == null) {
                Timber.tag(TAG).w("mClassLoader field not found in ${loadedApk::class.java.name}")
                return false
            }
            mClassLoaderField.isAccessible = true
            mClassLoaderField.set(loadedApk, classLoader)
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "injectViaLoadedApk failed for $packageName")
            false
        }
    }

    /**
     * Strategy 2: Clone the host's LoadedApk, set guest-specific fields,
     * and register it under the guest package name in mPackages.
     */
    private fun cloneHostLoadedApk(
        packageName: String,
        classLoader: ClassLoader,
        hostPackageName: String
    ): Boolean {
        return try {
            val activityThread = getActivityThread() ?: return false
            val mPackages = getMPackages(activityThread) ?: return false

            // Find host LoadedApk
            val hostRef = mPackages[hostPackageName] as? java.lang.ref.WeakReference<*>
            val hostLoadedApk = hostRef?.get() ?: return false

            val loadedApkClass = hostLoadedApk::class.java

            // Clone via Unsafe.allocateInstance (no-constructor allocation)
            val guestLoadedApk = try {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
                theUnsafeField.isAccessible = true
                val unsafe = theUnsafeField.get(null)
                val allocMethod = unsafeClass.getDeclaredMethod("allocateInstance", Class::class.java)
                allocMethod.invoke(unsafe, loadedApkClass)
            } catch (e: Exception) {
                Timber.tag(TAG).w("Unsafe.allocateInstance failed: ${e.message}")
                return false
            }

            // Copy ALL fields from host to clone
            var currentClass: Class<*>? = loadedApkClass
            while (currentClass != null && currentClass != Any::class.java) {
                for (field in currentClass.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                    try {
                        field.isAccessible = true
                        field.set(guestLoadedApk, field.get(hostLoadedApk))
                    } catch (_: Exception) { /* skip final / inaccessible */ }
                }
                currentClass = currentClass.superclass
            }

            // Override guest-specific fields
            val mClassLoaderField = findField(loadedApkClass, "mClassLoader")
            mClassLoaderField?.isAccessible = true
            mClassLoaderField?.set(guestLoadedApk, classLoader)

            val pkgField = findField(loadedApkClass, "mPackageName")
            pkgField?.isAccessible = true
            pkgField?.set(guestLoadedApk, packageName)

            // Register clone in mPackages under guest package name
            @Suppress("UNCHECKED_CAST")
            val typedPackages = mPackages as android.util.ArrayMap<String, Any>
            typedPackages[packageName] = java.lang.ref.WeakReference(guestLoadedApk)

            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "cloneHostLoadedApk failed for $packageName")
            false
        }
    }

    /**
     * Strategy 3: Use sun.misc.Unsafe for direct field offset manipulation.
     * Bypasses all access checks including hidden API restrictions.
     */
    private fun injectViaUnsafe(
        packageName: String,
        classLoader: ClassLoader,
        hostPackageName: String
    ): Boolean {
        return try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
            theUnsafeField.isAccessible = true
            val unsafe = theUnsafeField.get(null)

            val objectFieldOffset = unsafeClass.getDeclaredMethod(
                "objectFieldOffset", java.lang.reflect.Field::class.java
            )
            val putObject = unsafeClass.getDeclaredMethod(
                "putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java
            )
            val getObject = unsafeClass.getDeclaredMethod(
                "getObject", Any::class.java, Long::class.javaPrimitiveType
            )

            val activityThread = getActivityThread() ?: return false

            // Get mPackages field and its offset
            val atClass = Class.forName("android.app.ActivityThread")
            val mPackagesField = atClass.getDeclaredField("mPackages")
            mPackagesField.isAccessible = true
            val mPackagesOffset = objectFieldOffset.invoke(unsafe, mPackagesField) as Long
            val mPackages = getObject.invoke(unsafe, activityThread, mPackagesOffset)
                as? android.util.ArrayMap<*, *> ?: return false

            // Find existing LoadedApk (host or guest)
            val targetRef = (mPackages[packageName] ?: mPackages[hostPackageName])
                as? java.lang.ref.WeakReference<*>
            val loadedApk = targetRef?.get() ?: return false

            // Find mClassLoader field offset and write directly
            val mClassLoaderField = loadedApk::class.java.getDeclaredField("mClassLoader")
            val clOffset = objectFieldOffset.invoke(unsafe, mClassLoaderField) as Long
            putObject.invoke(unsafe, loadedApk, clOffset, classLoader)

            // If we used the host's LoadedApk, also register under guest package name
            if (mPackages[packageName] == null) {
                @Suppress("UNCHECKED_CAST")
                val typedPackages = mPackages as android.util.ArrayMap<String, Any>
                typedPackages[packageName] = java.lang.ref.WeakReference(loadedApk)
            }

            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "injectViaUnsafe failed for $packageName")
            false
        }
    }

    // ==================== Helpers ====================

    private fun getActivityThread(): Any? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val method = atClass.getDeclaredMethod("currentActivityThread")
            method.isAccessible = true
            method.invoke(null)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get ActivityThread instance")
            null
        }
    }

    private fun getMPackages(activityThread: Any): android.util.ArrayMap<*, *>? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val field = findField(atClass, "mPackages")
            field?.isAccessible = true
            field?.get(activityThread) as? android.util.ArrayMap<*, *>
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get mPackages")
            null
        }
    }
}

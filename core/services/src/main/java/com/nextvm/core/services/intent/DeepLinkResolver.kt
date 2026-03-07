package com.nextvm.core.services.intent

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import com.nextvm.core.common.AndroidCompat
import com.nextvm.core.model.VirtualApp
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeepLinkResolver — Handles deep linking, intent resolution, and PendingIntent management.
 *
 * Mirrors:
 *   - IntentResolver.java in frameworks/base
 *   - ResolveIntentHelper.java in frameworks/base/services/core/java/com/android/server/pm/
 *   - PendingIntentRecord.java in frameworks/base/services/core/java/com/android/server/am/
 *
 * Handles:
 *   - Intent filter matching (action, category, data URI)
 *   - Deep links (HTTP/HTTPS URL → virtual app)
 *   - Custom URI schemes (myapp://action)
 *   - PendingIntent creation and delivery for virtual apps
 *   - Intent chooser with virtual apps included
 *   - Share intents between real and virtual apps
 *   - App Links verification patterns
 */
@Singleton
class DeepLinkResolver @Inject constructor() {

    companion object {
        private const val TAG = "DeepLinkResolver"

        /** Request code counter for PendingIntents */
        private val pendingIntentCounter = AtomicInteger(5000)

        // Intent extra keys for virtual app routing
        const val EXTRA_INSTANCE_ID = "_nextvm_instance_id"
        const val EXTRA_TARGET_PACKAGE = "_nextvm_target_pkg"
        const val EXTRA_TARGET_ACTIVITY = "_nextvm_target_activity"
        const val EXTRA_TARGET_SERVICE = "_nextvm_target_service"
        const val EXTRA_APK_PATH = "_nextvm_apk_path"
        const val EXTRA_PROCESS_SLOT = "_nextvm_process_slot"

        /** Stub component package prefix */
        const val STUB_PREFIX = "com.nextvm.app.stub"
    }

    /**
     * Resolved component result from intent resolution.
     */
    data class ResolvedComponent(
        val packageName: String,
        val className: String,
        val instanceId: String,
        val componentType: ComponentType,
        val matchQuality: MatchQuality,
        val intentFilter: IntentFilter? = null
    )

    enum class ComponentType {
        ACTIVITY,
        SERVICE,
        BROADCAST_RECEIVER,
        CONTENT_PROVIDER
    }

    enum class MatchQuality {
        EXACT,          // Explicit component match
        BEST,           // Best intent filter match
        AMBIGUOUS,      // Multiple matches, need chooser
        NONE            // No match
    }

    /**
     * Deep link registration for a virtual app.
     */
    data class DeepLinkEntry(
        val instanceId: String,
        val packageName: String,
        val activityClassName: String,
        val scheme: String?,
        val host: String?,
        val pathPattern: String?,
        val intentFilter: IntentFilter,
        val isVerified: Boolean = false
    )

    /**
     * PendingIntent record tracking.
     */
    data class PendingIntentRecord(
        val requestCode: Int,
        val instanceId: String,
        val intent: Intent,
        val type: PendingIntentType,
        val flags: Int,
        val pendingIntent: PendingIntent
    )

    enum class PendingIntentType {
        ACTIVITY,
        SERVICE,
        BROADCAST
    }

    // Virtual apps registry (packageName -> VirtualApp)
    private val registeredApps = ConcurrentHashMap<String, VirtualApp>()

    // Intent filters per component: "packageName/className" -> list of IntentFilters
    private val componentFilters = ConcurrentHashMap<String, MutableList<IntentFilter>>()

    // Deep link entries: "scheme://host" -> list of DeepLinkEntry
    private val deepLinks = ConcurrentHashMap<String, MutableList<DeepLinkEntry>>()

    // Custom URI scheme entries: "scheme" -> list of DeepLinkEntry
    private val customSchemes = ConcurrentHashMap<String, MutableList<DeepLinkEntry>>()

    // PendingIntent tracking: requestCode -> PendingIntentRecord
    private val pendingIntents = ConcurrentHashMap<Int, PendingIntentRecord>()

    /**
     * Register a virtual app and its intent filters for resolution.
     */
    fun registerApp(app: VirtualApp, filters: Map<String, List<IntentFilter>>) {
        registeredApps[app.packageName] = app

        for ((componentName, filterList) in filters) {
            val key = "${app.packageName}/$componentName"
            componentFilters[key] = filterList.toMutableList()

            // Extract deep links and custom schemes
            for (filter in filterList) {
                extractDeepLinks(app, componentName, filter)
            }
        }

        Timber.tag(TAG).i("Registered ${filters.size} components for ${app.packageName}")
    }

    /**
     * Unregister a virtual app from intent resolution.
     */
    fun unregisterApp(packageName: String) {
        registeredApps.remove(packageName)

        // Remove component filters
        val keysToRemove = componentFilters.keys.filter { it.startsWith("$packageName/") }
        for (key in keysToRemove) {
            componentFilters.remove(key)
        }

        // Remove deep links belonging to this package
        for (entries in deepLinks.values) {
            entries.removeAll { it.packageName == packageName }
        }
        for (entries in customSchemes.values) {
            entries.removeAll { it.packageName == packageName }
        }

        Timber.tag(TAG).d("Unregistered app: $packageName")
    }

    /**
     * Resolve an Intent to a virtual component.
     *
     * Resolution order (mirrors real Android):
     *   1. Explicit component (Intent has component set)
     *   2. Best intent filter match among registered virtual apps
     *   3. Deep link / custom scheme match
     *   4. No match
     */
    fun resolveIntent(intent: Intent): ResolvedComponent? {
        // Case 1: Explicit component specified
        val component = intent.component
        if (component != null) {
            return resolveExplicitComponent(component)
        }

        // Case 2: Try intent filter matching across all registered components
        val filterMatches = matchIntentFilters(intent)

        return when {
            filterMatches.isEmpty() -> null
            filterMatches.size == 1 -> filterMatches.first()
            else -> {
                // Multiple matches — return the best one
                val best = filterMatches.maxByOrNull { it.matchQuality.ordinal }
                best?.copy(matchQuality = MatchQuality.AMBIGUOUS)
            }
        }
    }

    /**
     * Resolve an explicit component reference.
     */
    private fun resolveExplicitComponent(component: ComponentName): ResolvedComponent? {
        val packageName = component.packageName
        val className = component.className
        val app = registeredApps[packageName] ?: return null

        // Determine component type based on manifest declarations
        val componentType = when {
            app.activities.contains(className) -> ComponentType.ACTIVITY
            app.services.contains(className) -> ComponentType.SERVICE
            app.receivers.contains(className) -> ComponentType.BROADCAST_RECEIVER
            app.providers.contains(className) -> ComponentType.CONTENT_PROVIDER
            else -> ComponentType.ACTIVITY // Default assumption
        }

        return ResolvedComponent(
            packageName = packageName,
            className = className,
            instanceId = app.instanceId,
            componentType = componentType,
            matchQuality = MatchQuality.EXACT
        )
    }

    /**
     * Match an Intent against all registered IntentFilters.
     */
    private fun matchIntentFilters(intent: Intent): List<ResolvedComponent> {
        val results = mutableListOf<ResolvedComponent>()

        for ((key, filters) in componentFilters) {
            val parts = key.split("/", limit = 2)
            if (parts.size != 2) continue

            val packageName = parts[0]
            val className = parts[1]
            val app = registeredApps[packageName] ?: continue

            for (filter in filters) {
                if (matchIntentFilter(intent, filter)) {
                    val componentType = when {
                        app.activities.contains(className) -> ComponentType.ACTIVITY
                        app.services.contains(className) -> ComponentType.SERVICE
                        app.receivers.contains(className) -> ComponentType.BROADCAST_RECEIVER
                        else -> ComponentType.ACTIVITY
                    }

                    results.add(
                        ResolvedComponent(
                            packageName = packageName,
                            className = className,
                            instanceId = app.instanceId,
                            componentType = componentType,
                            matchQuality = MatchQuality.BEST,
                            intentFilter = filter
                        )
                    )
                }
            }
        }

        return results
    }

    /**
     * Match an Intent against a single IntentFilter.
     *
     * Mirrors IntentFilter.match() logic:
     *   1. Action matching
     *   2. Category matching
     *   3. Data (URI + MIME type) matching
     */
    fun matchIntentFilter(intent: Intent, filter: IntentFilter): Boolean {
        val matchResult = filter.match(
            intent.action,
            intent.type,
            intent.scheme,
            intent.data,
            intent.categories,
            TAG
        )
        return matchResult >= 0
    }

    /**
     * Resolve a deep link URI to a virtual app.
     *
     * Supports:
     *   - https://example.com/path → App with matching URL pattern
     *   - http://example.com/path → HTTP deep links
     *   - myapp://action → Custom scheme deep links
     */
    fun resolveDeepLink(uri: Uri): ResolvedComponent? {
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase()

        Timber.tag(TAG).d("Resolving deep link: $uri")

        // Check custom schemes first
        val customEntries = customSchemes[scheme]
        if (customEntries != null) {
            for (entry in customEntries) {
                if (matchDeepLinkEntry(uri, entry)) {
                    return buildResolvedFromDeepLink(entry)
                }
            }
        }

        // Check HTTP/HTTPS deep links
        if (scheme == "http" || scheme == "https") {
            val key = "$scheme://$host"
            val httpEntries = deepLinks[key]
            if (httpEntries != null) {
                for (entry in httpEntries) {
                    if (matchDeepLinkEntry(uri, entry)) {
                        return buildResolvedFromDeepLink(entry)
                    }
                }
            }

            // Try without scheme (match both http and https)
            val httpsEntries = deepLinks["https://$host"]
            val httpFallback = deepLinks["http://$host"]
            val allEntries = (httpsEntries ?: emptyList()) + (httpFallback ?: emptyList())
            for (entry in allEntries) {
                if (matchDeepLinkEntry(uri, entry)) {
                    return buildResolvedFromDeepLink(entry)
                }
            }
        }

        return null
    }

    private fun matchDeepLinkEntry(uri: Uri, entry: DeepLinkEntry): Boolean {
        // Scheme must match
        if (entry.scheme != null && entry.scheme != uri.scheme?.lowercase()) return false

        // Host must match (if specified)
        if (entry.host != null && entry.host != uri.host?.lowercase()) return false

        // Path pattern (if specified)
        if (entry.pathPattern != null) {
            val path = uri.path ?: ""
            if (!path.matches(Regex(entry.pathPattern))) return false
        }

        return true
    }

    private fun buildResolvedFromDeepLink(entry: DeepLinkEntry): ResolvedComponent {
        return ResolvedComponent(
            packageName = entry.packageName,
            className = entry.activityClassName,
            instanceId = entry.instanceId,
            componentType = ComponentType.ACTIVITY,
            matchQuality = MatchQuality.BEST,
            intentFilter = entry.intentFilter
        )
    }

    /**
     * Extract deep link registrations from an IntentFilter.
     */
    private fun extractDeepLinks(app: VirtualApp, className: String, filter: IntentFilter) {
        for (i in 0 until filter.countDataSchemes()) {
            val scheme = filter.getDataScheme(i)?.lowercase() ?: continue

            if (scheme == "http" || scheme == "https") {
                // HTTP/HTTPS deep links
                for (j in 0 until filter.countDataAuthorities()) {
                    val authority = filter.getDataAuthority(j)
                    val host = authority.host?.lowercase() ?: continue

                    val pathPattern = if (filter.countDataPaths() > 0) {
                        filter.getDataPath(0).path
                    } else null

                    val entry = DeepLinkEntry(
                        instanceId = app.instanceId,
                        packageName = app.packageName,
                        activityClassName = className,
                        scheme = scheme,
                        host = host,
                        pathPattern = pathPattern,
                        intentFilter = filter
                    )

                    val key = "$scheme://$host"
                    deepLinks.getOrPut(key) { mutableListOf() }.add(entry)
                }
            } else {
                // Custom scheme
                val entry = DeepLinkEntry(
                    instanceId = app.instanceId,
                    packageName = app.packageName,
                    activityClassName = className,
                    scheme = scheme,
                    host = filter.getDataAuthority(0)?.host,
                    pathPattern = null,
                    intentFilter = filter
                )
                customSchemes.getOrPut(scheme) { mutableListOf() }.add(entry)
            }
        }
    }

    // ---------- PendingIntent Management ----------

    /**
     * Create a PendingIntent for a virtual app.
     *
     * Virtual apps can't create real PendingIntents for their own components
     * (they're not installed). We create PendingIntents that target our stubs
     * and carry the virtual app info as extras.
     *
     * @param context Host app context
     * @param instanceId Guest app instance ID
     * @param intent The intent the guest app wants to PendingIntent-ify
     * @param type Whether this is for an Activity, Service, or Broadcast
     * @param flags PendingIntent flags
     * @return A PendingIntent wrapping the virtual intent
     */
    fun createPendingIntent(
        context: Context,
        instanceId: String,
        intent: Intent,
        type: PendingIntentType,
        flags: Int = 0
    ): PendingIntent? {
        val app = registeredApps.values.find { it.instanceId == instanceId } ?: run {
            Timber.tag(TAG).e("Cannot create PendingIntent: app not found for $instanceId")
            return null
        }

        val requestCode = pendingIntentCounter.incrementAndGet()

        // Wrap the original intent with stub routing info
        val wrappedIntent = Intent(intent).apply {
            putExtra(EXTRA_INSTANCE_ID, instanceId)
            putExtra(EXTRA_TARGET_PACKAGE, app.packageName)
            putExtra(EXTRA_APK_PATH, app.apkPath)
        }

        val stubIntent = when (type) {
            PendingIntentType.ACTIVITY -> {
                val targetActivity = intent.component?.className ?: intent.getStringExtra(EXTRA_TARGET_ACTIVITY)
                wrappedIntent.apply {
                    setClassName(context.packageName, "${STUB_PREFIX}.P${app.processSlot}\$Standard00")
                    putExtra(EXTRA_TARGET_ACTIVITY, targetActivity)
                }
            }
            PendingIntentType.SERVICE -> {
                val targetService = intent.component?.className ?: intent.getStringExtra(EXTRA_TARGET_SERVICE)
                wrappedIntent.apply {
                    setClassName(context.packageName, "${STUB_PREFIX}.P${app.processSlot}\$S00")
                    putExtra(EXTRA_TARGET_SERVICE, targetService)
                }
            }
            PendingIntentType.BROADCAST -> {
                wrappedIntent // Broadcast PendingIntents handled differently
            }
        }

        val combinedFlags = flags or if (AndroidCompat.isAtLeastS) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }

        val pendingIntent = try {
            when (type) {
                PendingIntentType.ACTIVITY ->
                    PendingIntent.getActivity(context, requestCode, stubIntent, combinedFlags)
                PendingIntentType.SERVICE ->
                    PendingIntent.getService(context, requestCode, stubIntent, combinedFlags)
                PendingIntentType.BROADCAST ->
                    PendingIntent.getBroadcast(context, requestCode, stubIntent, combinedFlags)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to create PendingIntent")
            return null
        }

        // Track the PendingIntent
        pendingIntents[requestCode] = PendingIntentRecord(
            requestCode = requestCode,
            instanceId = instanceId,
            intent = wrappedIntent,
            type = type,
            flags = flags,
            pendingIntent = pendingIntent
        )

        Timber.tag(TAG).d("PendingIntent created: type=$type, code=$requestCode for $instanceId")
        return pendingIntent
    }

    // ---------- Chooser Intent ----------

    /**
     * Build a chooser intent that includes both virtual and real app options.
     *
     * @param intent The intent to match against
     * @param context Host context
     * @param title The chooser dialog title
     * @return A chooser Intent, or null if no matches
     */
    fun buildChooserIntent(
        intent: Intent,
        context: Context,
        title: CharSequence = "Open with"
    ): Intent? {
        val matches = matchIntentFilters(intent)

        if (matches.isEmpty()) {
            Timber.tag(TAG).d("No virtual app matches for chooser")
            return null
        }

        // Create intents for each matching virtual app
        val targetIntents = matches.mapNotNull { match ->
            val app = registeredApps[match.packageName] ?: return@mapNotNull null

            Intent(intent).apply {
                setClassName(
                    context.packageName,
                    "${STUB_PREFIX}.P${app.processSlot}\$Standard00"
                )
                putExtra(EXTRA_TARGET_PACKAGE, match.packageName)
                putExtra(EXTRA_TARGET_ACTIVITY, match.className)
                putExtra(EXTRA_INSTANCE_ID, match.instanceId)
                putExtra(EXTRA_APK_PATH, app.apkPath)
            }
        }

        if (targetIntents.isEmpty()) return null

        val chooser = Intent.createChooser(targetIntents.first(), title)
        if (targetIntents.size > 1) {
            val extras = targetIntents.drop(1).toTypedArray()
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extras)
        }

        return chooser
    }

    /**
     * Build a share intent for sharing between real and virtual apps.
     */
    fun buildShareIntent(
        text: String? = null,
        uri: Uri? = null,
        mimeType: String = "text/plain"
    ): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
            if (uri != null) putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ---------- Cleanup ----------

    /**
     * Clear all state.
     */
    fun clearAll() {
        registeredApps.clear()
        componentFilters.clear()
        deepLinks.clear()
        customSchemes.clear()
        pendingIntents.clear()
        Timber.tag(TAG).i("All deep link state cleared")
    }

    /**
     * Initialize the deep link resolver with application context.
     */
    fun initialize(context: android.content.Context) {
        Timber.tag(TAG).d("DeepLinkResolver initialized")
    }
}

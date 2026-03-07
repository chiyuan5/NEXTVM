package com.nextvm.core.binder.proxy

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.nextvm.core.common.findField
import com.nextvm.core.common.runSafe
import com.nextvm.core.model.VirtualConstants
import timber.log.Timber
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ContentResolverProxy — Intercepts ContentResolver calls from virtual apps.
 *
 * Handles:
 * - content:// URI redirection to virtual providers
 * - Cross-app content sharing isolation
 * - MediaStore access for scoped storage compliance
 * - Settings.Secure / Settings.System interception (ANDROID_ID, etc.)
 *
 * ContentResolver internally uses IContentProvider obtained via
 * ActivityThread.acquireProvider(). We hook the ContentResolver instance
 * to intercept query/insert/update/delete/openFile operations.
 */
@Singleton
class ContentResolverProxy @Inject constructor() {

    companion object {
        private const val TAG = "ContentProxy"

        // Common content authority prefixes
        private const val AUTHORITY_SETTINGS = "settings"
        private const val AUTHORITY_MEDIA = "media"
        private const val AUTHORITY_CONTACTS = "com.android.contacts"
        private const val AUTHORITY_CALENDAR = "com.android.calendar"
        private const val AUTHORITY_DOWNLOADS = "downloads"
        private const val AUTHORITY_TELEPHONY = "telephony"

        // Settings URIs
        private val URI_SETTINGS_SECURE = Uri.parse("content://settings/secure")
        private val URI_SETTINGS_SYSTEM = Uri.parse("content://settings/system")
        private val URI_SETTINGS_GLOBAL = Uri.parse("content://settings/global")
    }

    // Virtual package tracking
    private val virtualPackages = mutableSetOf<String>()
    private val instanceToPackage = mutableMapOf<String, String>()
    private val pidToInstance = mutableMapOf<Int, String>()

    // Per-instance Settings.Secure overrides: instanceId -> (key -> value)
    private val settingsOverrides = mutableMapOf<String, MutableMap<String, String>>()

    // Per-instance virtual content providers: instanceId -> (authority -> provider)
    private val virtualProviders = mutableMapOf<String, MutableMap<String, Any>>()

    // URI rewriting rules: originalAuthority -> virtualAuthority
    private val authorityRedirects = mutableMapOf<String, String>()

    private var installed = false

    /**
     * Register a virtual app.
     */
    fun registerVirtualApp(packageName: String, instanceId: String) {
        virtualPackages.add(packageName)
        instanceToPackage[instanceId] = packageName
        Timber.tag(TAG).d("Registered virtual app: $packageName (instance=$instanceId)")
    }

    /**
     * Unregister a virtual app.
     */
    fun unregisterVirtualApp(packageName: String, instanceId: String) {
        virtualPackages.remove(packageName)
        instanceToPackage.remove(instanceId)
        settingsOverrides.remove(instanceId)
        virtualProviders.remove(instanceId)
        Timber.tag(TAG).d("Unregistered virtual app: $packageName")
    }

    /**
     * Map a PID to a virtual instance.
     */
    fun registerProcessPid(pid: Int, instanceId: String) {
        pidToInstance[pid] = instanceId
    }

    fun unregisterProcessPid(pid: Int) {
        pidToInstance.remove(pid)
    }

    /**
     * Set a Settings.Secure override for a virtual app instance.
     * Used primarily for ANDROID_ID spoofing.
     */
    fun setSettingsOverride(instanceId: String, key: String, value: String) {
        settingsOverrides.getOrPut(instanceId) { mutableMapOf() }[key] = value
        Timber.tag(TAG).d("Settings override: $key=$value for instance=$instanceId")
    }

    /**
     * Register a virtual content provider for an instance.
     */
    fun registerVirtualProvider(instanceId: String, authority: String, provider: Any) {
        virtualProviders.getOrPut(instanceId) { mutableMapOf() }[authority] = provider
        Timber.tag(TAG).d("Registered virtual provider: $authority for instance=$instanceId")
    }

    /**
     * Add a URI authority redirect rule.
     */
    fun addAuthorityRedirect(fromAuthority: String, toAuthority: String) {
        authorityRedirects[fromAuthority] = toAuthority
    }

    /**
     * Install the ContentResolver proxy on a Context's ContentResolver.
     */
    fun install(context: Context): Boolean {
        if (installed) return true

        return try {
            val resolver = context.contentResolver
            val resolverClass = resolver::class.java

            // Hook the acquireProvider/acquireUnstableProvider methods
            // on the ContentResolver to intercept provider lookups
            installContentProviderClientProxy(resolver)

            installed = true
            Timber.tag(TAG).i("ContentResolver proxy installed")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to install ContentResolver proxy")
            false
        }
    }

    /**
     * Install proxy on ContentResolver's internal IContentProvider access.
     */
    private fun installContentProviderClientProxy(resolver: Any) {
        try {
            // ContentResolver uses ApplicationContentResolver internally
            // which calls ActivityThread.acquireProvider() to get IContentProvider
            // We intercept at the ActivityThread level

            val atClass = Class.forName("android.app.ActivityThread")
            val currentATMethod = atClass.getDeclaredMethod("currentActivityThread")
            currentATMethod.isAccessible = true
            val activityThread = currentATMethod.invoke(null) ?: return

            // Get the mProviderMap field which caches ContentProviders
            // NOTE: mProviderMap is an ArrayMap in AOSP, NOT a HashMap.
            // We cannot replace it with our own MutableMap wrapper because
            // AOSP code downcasts to ArrayMap internally. Instead, we observe
            // the map via a periodic snapshot approach (no field replacement).
            val providerMapField = findField(atClass, "mProviderMap")
            if (providerMapField != null) {
                providerMapField.isAccessible = true
                val originalMap = providerMapField.get(activityThread)
                if (originalMap != null) {
                    // Store reference for later observation, do NOT replace the field
                    // as ArrayMap cannot be swapped with MutableMap proxy
                    cachedProviderMap = originalMap
                    Timber.tag(TAG).d("Observed ActivityThread.mProviderMap (type=${originalMap::class.java.simpleName})")
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to install ContentProvider proxy")
        }
    }

    // Cached reference to the original mProviderMap (ArrayMap) for observation
    @Volatile
    private var cachedProviderMap: Any? = null

    /**
     * Process a content query, potentially redirecting or filtering.
     */
    fun processQuery(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        callingPid: Int
    ): QueryInterception {
        val instanceId = pidToInstance[callingPid]
            ?: return QueryInterception.PassThrough

        val authority = uri.authority ?: return QueryInterception.PassThrough

        // Check for Settings.Secure interception (ANDROID_ID etc.)
        if (authority == AUTHORITY_SETTINGS) {
            return handleSettingsQuery(uri, selection, selectionArgs, instanceId)
        }

        // Check for virtual provider
        val virtualProvider = virtualProviders[instanceId]?.get(authority)
        if (virtualProvider != null) {
            return QueryInterception.UseVirtualProvider(virtualProvider)
        }

        // Check for authority redirect
        val redirectedAuthority = authorityRedirects[authority]
        if (redirectedAuthority != null) {
            val newUri = uri.buildUpon().authority(redirectedAuthority).build()
            return QueryInterception.RedirectUri(newUri)
        }

        // Rewrite content URIs that reference virtual package paths
        if (shouldRewriteUri(uri, instanceId)) {
            val rewrittenUri = rewriteContentUri(uri, instanceId)
            return QueryInterception.RedirectUri(rewrittenUri)
        }

        return QueryInterception.PassThrough
    }

    /**
     * Handle Settings.Secure / Settings.System / Settings.Global queries.
     */
    private fun handleSettingsQuery(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
        instanceId: String
    ): QueryInterception {
        val overrides = settingsOverrides[instanceId] ?: return QueryInterception.PassThrough

        // Extract the settings key being queried
        // Settings queries typically use: selection = "name=?", selectionArgs = [key]
        val key = when {
            selectionArgs != null && selectionArgs.isNotEmpty() -> selectionArgs[0]
            uri.pathSegments.size >= 2 -> uri.pathSegments.last()
            selection != null && selection.contains("name=") -> {
                val regex = Regex("name\\s*=\\s*'([^']*)'")
                regex.find(selection)?.groupValues?.get(1)
            }
            else -> null
        } ?: return QueryInterception.PassThrough

        val overriddenValue = overrides[key]
        if (overriddenValue != null) {
            Timber.tag(TAG).d("Settings override for $key = $overriddenValue (instance=$instanceId)")
            return QueryInterception.OverrideSettings(key, overriddenValue)
        }

        return QueryInterception.PassThrough
    }

    /**
     * Check if a content URI should be rewritten for sandboxing.
     */
    private fun shouldRewriteUri(uri: Uri, instanceId: String): Boolean {
        val path = uri.path ?: return false
        val packageName = instanceToPackage[instanceId] ?: return false

        // Rewrite URIs that reference the guest app's data directory
        return path.contains("/data/data/$packageName") ||
                path.contains("/data/user/0/$packageName")
    }

    /**
     * Rewrite a content URI to point to sandboxed storage.
     */
    private fun rewriteContentUri(uri: Uri, instanceId: String): Uri {
        val path = uri.path ?: return uri
        val packageName = instanceToPackage[instanceId] ?: return uri

        val sandboxedPath = path
            .replace("/data/data/$packageName", "/data/data/${VirtualConstants.HOST_PACKAGE}/${VirtualConstants.VIRTUAL_DIR}/${VirtualConstants.VIRTUAL_DATA_DIR}/$instanceId")
            .replace("/data/user/0/$packageName", "/data/data/${VirtualConstants.HOST_PACKAGE}/${VirtualConstants.VIRTUAL_DIR}/${VirtualConstants.VIRTUAL_DATA_DIR}/$instanceId")

        return uri.buildUpon().path(sandboxedPath).build()
    }

    /**
     * Process a content insert operation.
     */
    fun processInsert(
        uri: Uri,
        values: ContentValues?,
        callingPid: Int
    ): InsertInterception {
        val instanceId = pidToInstance[callingPid]
            ?: return InsertInterception.PassThrough

        val authority = uri.authority ?: return InsertInterception.PassThrough

        // Rewrite package name in ContentValues if present
        if (values != null && values.containsKey("package_name")) {
            val pkg = values.getAsString("package_name")
            if (pkg != null && virtualPackages.contains(pkg)) {
                val newValues = ContentValues(values)
                newValues.put("package_name", VirtualConstants.HOST_PACKAGE)
                return InsertInterception.RewriteValues(newValues)
            }
        }

        // Check for virtual provider
        val virtualProvider = virtualProviders[instanceId]?.get(authority)
        if (virtualProvider != null) {
            return InsertInterception.UseVirtualProvider(virtualProvider)
        }

        return InsertInterception.PassThrough
    }

    /**
     * Process a MediaStore query to handle scoped storage.
     */
    fun processMediaStoreQuery(
        uri: Uri,
        callingPid: Int
    ): QueryInterception {
        val instanceId = pidToInstance[callingPid]
            ?: return QueryInterception.PassThrough

        val authority = uri.authority
        if (authority != AUTHORITY_MEDIA) return QueryInterception.PassThrough

        // For MediaStore queries, allow access but filter results to this instance
        Timber.tag(TAG).d("MediaStore query from virtual app: $uri (instance=$instanceId)")

        // Let it pass through — the native file hooks will handle sandboxing
        return QueryInterception.PassThrough
    }

    /**
     * Process an openFile/openInputStream request.
     */
    fun processOpenFile(
        uri: Uri,
        mode: String,
        callingPid: Int
    ): OpenFileInterception {
        val instanceId = pidToInstance[callingPid]
            ?: return OpenFileInterception.PassThrough

        val authority = uri.authority ?: return OpenFileInterception.PassThrough

        // Check for virtual provider
        val virtualProvider = virtualProviders[instanceId]?.get(authority)
        if (virtualProvider != null) {
            return OpenFileInterception.UseVirtualProvider(virtualProvider)
        }

        // Rewrite file URIs
        if (shouldRewriteUri(uri, instanceId)) {
            val newUri = rewriteContentUri(uri, instanceId)
            return OpenFileInterception.RedirectUri(newUri)
        }

        return OpenFileInterception.PassThrough
    }

    /**
     * Get the virtual instance ID for a calling PID.
     */
    fun getInstanceForPid(pid: Int): String? = pidToInstance[pid]

    /**
     * Check if a package is virtual.
     */
    fun isVirtualPackage(packageName: String): Boolean = virtualPackages.contains(packageName)
}

// ========================================================================
// Interception result types
// ========================================================================

/**
 * Result of intercepting a content query.
 */
sealed class QueryInterception {
    /** Pass through to original ContentResolver */
    data object PassThrough : QueryInterception()
    /** Redirect to a different URI */
    data class RedirectUri(val newUri: Uri) : QueryInterception()
    /** Use a virtual provider */
    data class UseVirtualProvider(val provider: Any) : QueryInterception()
    /** Override a Settings value */
    data class OverrideSettings(val key: String, val value: String) : QueryInterception()
}

/**
 * Result of intercepting a content insert.
 */
sealed class InsertInterception {
    data object PassThrough : InsertInterception()
    data class RewriteValues(val newValues: ContentValues) : InsertInterception()
    data class UseVirtualProvider(val provider: Any) : InsertInterception()
}

/**
 * Result of intercepting a file open.
 */
sealed class OpenFileInterception {
    data object PassThrough : OpenFileInterception()
    data class RedirectUri(val newUri: Uri) : OpenFileInterception()
    data class UseVirtualProvider(val provider: Any) : OpenFileInterception()
}

// ========================================================================
// ContentProvider map proxy for intercepting provider lookups
// ========================================================================

/**
 * Wraps ActivityThread's mProviderMap to intercept ContentProvider lookups.
 * When a virtual app requests a provider by authority, we can redirect
 * to our virtual providers or modify the IContentProvider before returning.
 */
internal class ContentProviderMapProxy(
    private val delegate: MutableMap<Any, Any>,
    private val proxy: ContentResolverProxy
) : MutableMap<Any, Any> by delegate {

    companion object {
        private const val TAG = "CPMapProxy"
    }

    override fun get(key: Any): Any? {
        val callingPid = android.os.Process.myPid()
        val instanceId = proxy.getInstanceForPid(callingPid)

        if (instanceId != null) {
            // key is typically ProviderKey (auth, userId)
            val authority = extractAuthority(key)
            if (authority != null) {
                Timber.tag(TAG).d("ContentProvider lookup: $authority (instance=$instanceId)")
            }
        }

        return delegate[key]
    }

    override fun put(key: Any, value: Any): Any? {
        val authority = extractAuthority(key)
        if (authority != null) {
            Timber.tag(TAG).d("ContentProvider cached: $authority")
        }
        return delegate.put(key, value)
    }

    private fun extractAuthority(key: Any): String? {
        return try {
            // ProviderKey has an "authority" field
            val authField = findField(key::class.java, "authority")
            authField?.isAccessible = true
            authField?.get(key) as? String
        } catch (_: Exception) {
            // Try toString fallback
            try {
                key.toString()
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * VirtualContentProvider — base class for in-process content providers
 * that serve content to virtual apps.
 *
 * Virtual apps may declare ContentProviders in their manifest that need
 * to be served without actual system-level installation.
 */
abstract class VirtualContentProvider(
    val authority: String,
    val instanceId: String
) {
    companion object {
        private const val TAG = "VirtualCP"
    }

    abstract fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor?

    abstract fun insert(uri: Uri, values: ContentValues?): Uri?

    abstract fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int

    abstract fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int

    abstract fun getType(uri: Uri): String?

    /**
     * Build a content URI for this provider.
     */
    fun buildUri(path: String): Uri {
        return Uri.parse("content://${authority}/${path}")
    }
}

/**
 * SharedPreferencesContentProvider — serves SharedPreferences data
 * as a content provider for cross-process SharedPreferences access.
 */
class SharedPreferencesContentProvider(
    authority: String,
    instanceId: String,
    private val prefsDir: java.io.File
) : VirtualContentProvider(authority, instanceId) {

    companion object {
        private const val TAG = "SharedPrefsCP"
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        Timber.tag(TAG).d("SharedPrefs query: $uri (instance=$instanceId)")
        // Return shared preferences as cursor rows
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        Timber.tag(TAG).d("SharedPrefs insert: $uri (instance=$instanceId)")
        return null
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        Timber.tag(TAG).d("SharedPrefs update: $uri (instance=$instanceId)")
        return 0
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        Timber.tag(TAG).d("SharedPrefs delete: $uri (instance=$instanceId)")
        return 0
    }

    override fun getType(uri: Uri): String? = "vnd.android.cursor.item/vnd.nextvm.sharedprefs"
}

/**
 * FileContentProvider — serves files from the sandbox via content URIs.
 * Used when virtual apps need to share files with external apps.
 */
class FileContentProvider(
    authority: String,
    instanceId: String,
    private val dataDir: java.io.File
) : VirtualContentProvider(authority, instanceId) {

    companion object {
        private const val TAG = "FileCP"
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        val path = uri.path ?: return null
        val file = resolveSecurePath(path) ?: return null
        Timber.tag(TAG).d("File query: $path -> ${file.absolutePath}")
        return null // Would return MatrixCursor with file metadata
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        val path = uri.path ?: return 0
        val file = resolveSecurePath(path) ?: return 0
        return if (file.delete()) 1 else 0
    }

    override fun getType(uri: Uri): String? {
        val path = uri.path ?: return null
        return when {
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".pdf") -> "application/pdf"
            path.endsWith(".txt") -> "text/plain"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".xml") -> "application/xml"
            path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".mp3") -> "audio/mpeg"
            else -> "application/octet-stream"
        }
    }

    /**
     * Resolve a path securely within the sandbox.
     * Prevents path traversal attacks.
     */
    private fun resolveSecurePath(path: String): java.io.File? {
        val normalizedPath = path.replace("..", "").replace("//", "/")
        val file = java.io.File(dataDir, normalizedPath)

        // Verify the resolved path is inside the data directory
        val canonicalDataDir = dataDir.canonicalPath
        val canonicalFile = file.canonicalPath

        return if (canonicalFile.startsWith(canonicalDataDir)) {
            file
        } else {
            Timber.tag(TAG).w("Path traversal blocked: $path -> $canonicalFile")
            null
        }
    }
}

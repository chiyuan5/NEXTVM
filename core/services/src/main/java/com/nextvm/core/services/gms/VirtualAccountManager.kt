package com.nextvm.core.services.gms

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import timber.log.Timber
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VirtualAccountManager — Isolated Google Account store for NEXTVM.
 *
 * HYBRID ACCOUNT LAYER:
 * - GMS engine is REAL (from host device)
 * - Account identity is VIRTUAL (isolated per-instance)
 *
 * ┌─────────────────────────────────────────────────────┐
 * │ Host Device                                         │
 * │  ┌─────────────┐                                    │
 * │  │ Real GMS    │ ← OAuth tokens come from here      │
 * │  └──────┬──────┘                                    │
 * │         │                                            │
 * │  ┌──────┴──────────────────────────────────────┐    │
 * │  │ VirtualAccountManager                       │    │
 * │  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  │    │
 * │  │  │ VM inst1 │  │ VM inst2 │  │ VM inst3 │  │    │
 * │  │  │ @user1   │  │ @user2   │  │ @user1   │  │    │
 * │  │  └──────────┘  └──────────┘  └──────────┘  │    │
 * │  │  (each instance has its own isolated acct)  │    │
 * │  └─────────────────────────────────────────────┘    │
 * └─────────────────────────────────────────────────────┘
 *
 * Key design decisions:
 * 1. Accounts are stored in an encrypted local database, NOT in Android AccountManager
 * 2. OAuth tokens are obtained through real GMS (so they're genuine Google tokens)
 * 3. Host device's own Google accounts are NOT automatically visible to guest apps
 * 4. Each virtual instance can have its own Google account (multi-account support)
 * 5. Token refresh goes through real GMS OAuth endpoint
 */
@Singleton
class VirtualAccountManager @Inject constructor() {

    companion object {
        private const val TAG = "VirtualAcctMgr"
        private const val PREFS_NAME = "nextvm_virtual_accounts"
        private const val KEYSTORE_ALIAS = "nextvm_account_key"
        private const val AES_GCM_TAG_LENGTH = 128
    }

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var initialized = false

    // In-memory account cache: instanceId -> VirtualGoogleAccount
    private val accounts = ConcurrentHashMap<String, VirtualGoogleAccount>()

    // Token cache: instanceId -> token type -> token value
    private val tokenCache = ConcurrentHashMap<String, ConcurrentHashMap<String, TokenEntry>>()

    /**
     * Represents a Google Account inside the virtual environment.
     * This is isolated from the host device's account.
     */
    data class VirtualGoogleAccount(
        val instanceId: String,
        val email: String,
        val displayName: String = "",
        val photoUrl: String = "",
        val googleId: String = "",
        val accountType: String = "com.google",
        val createdAt: Long = System.currentTimeMillis(),
        val lastTokenRefresh: Long = 0
    )

    /**
     * Cached OAuth token with expiry tracking.
     */
    data class TokenEntry(
        val token: String,
        val tokenType: String,
        val expiresAt: Long,
        val scope: String = ""
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAt
    }

    /**
     * Initialize the virtual account manager.
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Ensure encryption key exists
        ensureEncryptionKey()

        // Load persisted accounts
        loadAccountsFromDisk()

        initialized = true
        Timber.tag(TAG).i("VirtualAccountManager initialized (${accounts.size} accounts loaded)")
    }

    // ====================================================================
    // Account CRUD Operations
    // ====================================================================

    /**
     * Add a Google account for a virtual instance.
     * This is called after the user completes Google Sign-In inside the VM.
     *
     * @param instanceId The virtual app instance
     * @param email Google account email
     * @param displayName User's display name
     * @param photoUrl Profile photo URL
     * @param googleId Google user ID
     */
    fun addAccount(
        instanceId: String,
        email: String,
        displayName: String = "",
        photoUrl: String = "",
        googleId: String = ""
    ): VirtualGoogleAccount {
        val account = VirtualGoogleAccount(
            instanceId = instanceId,
            email = email,
            displayName = displayName,
            photoUrl = photoUrl,
            googleId = googleId
        )

        accounts[instanceId] = account
        saveAccountToDisk(instanceId, account)

        Timber.tag(TAG).i("Account added for $instanceId: $email")
        return account
    }

    /**
     * Get the Google account for a virtual instance.
     */
    fun getAccount(instanceId: String): VirtualGoogleAccount? {
        return accounts[instanceId]
    }

    /**
     * Get all virtual accounts.
     */
    fun getAllAccounts(): Map<String, VirtualGoogleAccount> {
        return accounts.toMap()
    }

    /**
     * Remove the Google account for a virtual instance.
     * Also clears all cached tokens.
     */
    fun removeAccount(instanceId: String) {
        accounts.remove(instanceId)
        tokenCache.remove(instanceId)
        removeAccountFromDisk(instanceId)
        Timber.tag(TAG).i("Account removed for $instanceId")
    }

    /**
     * Check if an instance has a Google account.
     */
    fun hasAccount(instanceId: String): Boolean {
        return accounts.containsKey(instanceId)
    }

    // ====================================================================
    // OAuth Token Management
    // ====================================================================

    /**
     * Store an OAuth token for a virtual instance.
     *
     * @param instanceId The virtual app instance
     * @param tokenType Token type (e.g., "oauth2:email profile", "id_token", "access_token")
     * @param token The token value
     * @param expiresInMs Token lifetime in milliseconds
     * @param scope OAuth scope
     */
    fun storeToken(
        instanceId: String,
        tokenType: String,
        token: String,
        expiresInMs: Long = 3600_000L,
        scope: String = ""
    ) {
        val entry = TokenEntry(
            token = token,
            tokenType = tokenType,
            expiresAt = System.currentTimeMillis() + expiresInMs,
            scope = scope
        )

        tokenCache.getOrPut(instanceId) { ConcurrentHashMap() }[tokenType] = entry
        Timber.tag(TAG).d("Token stored for $instanceId: type=$tokenType, expires in ${expiresInMs / 1000}s")
    }

    /**
     * Get a cached OAuth token.
     *
     * @param instanceId The virtual app instance
     * @param tokenType Token type to retrieve
     * @param allowExpired If true, return even if expired (for refresh flow)
     * @return The token string, or null if not found/expired
     */
    fun getToken(instanceId: String, tokenType: String, allowExpired: Boolean = false): String? {
        val entry = tokenCache[instanceId]?.get(tokenType) ?: return null

        if (!allowExpired && entry.isExpired()) {
            Timber.tag(TAG).d("Token expired for $instanceId: type=$tokenType")
            return null
        }

        return entry.token
    }

    /**
     * Invalidate a specific token (forces re-fetch on next use).
     */
    fun invalidateToken(instanceId: String, tokenType: String) {
        tokenCache[instanceId]?.remove(tokenType)
        Timber.tag(TAG).d("Token invalidated for $instanceId: type=$tokenType")
    }

    /**
     * Invalidate all tokens for an instance.
     */
    fun invalidateAllTokens(instanceId: String) {
        tokenCache.remove(instanceId)
        Timber.tag(TAG).d("All tokens invalidated for $instanceId")
    }

    /**
     * Request a real OAuth token from the host device's GMS.
     *
     * This is the bridge between virtual accounts and real Google auth.
     * The host device's AccountManager is used to get a genuine OAuth token,
     * which is then associated with the virtual instance.
     *
     * @param hostAccount The host device Google Account to use (or null for first available)
     * @param scope OAuth scope string (e.g., "oauth2:email profile")
     * @return The real OAuth token, or null if unavailable
     */
    fun requestRealTokenFromHost(
        hostAccount: Account? = null,
        scope: String = "oauth2:email profile"
    ): String? {
        val context = appContext ?: return null

        return try {
            val accountManager = AccountManager.get(context)
            val googleAccounts = accountManager.getAccountsByType("com.google")

            if (googleAccounts.isEmpty()) {
                Timber.tag(TAG).w("No Google accounts on host device")
                return null
            }

            val account = hostAccount
                ?: googleAccounts.firstOrNull()
                ?: return null

            // This may trigger a consent dialog the first time
            val future = accountManager.getAuthToken(
                account, scope, null, false, null, null
            )

            val result = future.result
            val token = result.getString(AccountManager.KEY_AUTHTOKEN)

            if (token != null) {
                Timber.tag(TAG).d("Got real OAuth token from host (${token.length} chars)")
            }
            token
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get real OAuth token from host")
            null
        }
    }

    /**
     * Get available Google accounts on the host device.
     * These can optionally be mapped to virtual instances.
     */
    fun getHostGoogleAccounts(): List<Account> {
        val context = appContext ?: return emptyList()
        return try {
            val accountManager = AccountManager.get(context)
            accountManager.getAccountsByType("com.google").toList()
        } catch (e: Exception) {
            Timber.tag(TAG).w("Cannot access host accounts: ${e.message}")
            emptyList()
        }
    }

    // ====================================================================
    // AccountManager Interception (for guest apps)
    // ====================================================================

    /**
     * Handle a getAccountsByType() call from a guest app.
     * Returns only the virtual account for this instance.
     */
    fun handleGetAccountsByType(instanceId: String, accountType: String): Array<Account> {
        if (accountType != "com.google" && accountType.isNotEmpty()) {
            return emptyArray()
        }

        val virtualAccount = accounts[instanceId] ?: return emptyArray()

        return arrayOf(Account(virtualAccount.email, "com.google"))
    }

    /**
     * Handle a getAuthToken() call from a guest app.
     * Returns cached token or fetches a new one via host GMS.
     */
    fun handleGetAuthToken(
        instanceId: String,
        accountEmail: String,
        scope: String
    ): String? {
        // Check if this account matches the virtual instance's account
        val virtualAccount = accounts[instanceId] ?: return null
        if (virtualAccount.email != accountEmail) return null

        // Try cached token first
        val cached = getToken(instanceId, scope)
        if (cached != null) return cached

        // Fetch real token from host GMS
        val realToken = requestRealTokenFromHost(scope = scope)
        if (realToken != null) {
            storeToken(instanceId, scope, realToken, expiresInMs = 3600_000L, scope = scope)
        }

        return realToken
    }

    // ====================================================================
    // Encrypted Persistence
    // ====================================================================

    private fun ensureEncryptionKey() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
                )
                keyGenerator.init(
                    KeyGenParameterSpec.Builder(
                        KEYSTORE_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                keyGenerator.generateKey()
                Timber.tag(TAG).d("Account encryption key generated")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to ensure encryption key")
        }
    }

    private fun getEncryptionKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get encryption key")
            null
        }
    }

    private fun encrypt(plainText: String): String? {
        val key = getEncryptionKey() ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray())
            val combined = iv + encrypted
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Encryption failed")
            null
        }
    }

    private fun decrypt(encryptedBase64: String): String? {
        val key = getEncryptionKey() ?: return null
        return try {
            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            val iv = combined.sliceArray(0 until 12)
            val encrypted = combined.sliceArray(12 until combined.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(AES_GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(encrypted))
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Decryption failed")
            null
        }
    }

    private fun saveAccountToDisk(instanceId: String, account: VirtualGoogleAccount) {
        val json = "${account.email}|${account.displayName}|${account.photoUrl}|${account.googleId}|${account.createdAt}"
        val encrypted = encrypt(json) ?: return

        prefs?.edit()
            ?.putString("account_$instanceId", encrypted)
            ?.apply()
    }

    private fun removeAccountFromDisk(instanceId: String) {
        prefs?.edit()
            ?.remove("account_$instanceId")
            ?.apply()
    }

    private fun loadAccountsFromDisk() {
        val allEntries = prefs?.all ?: return

        allEntries.forEach { (key, value) ->
            if (key.startsWith("account_") && value is String) {
                val instanceId = key.removePrefix("account_")
                val decrypted = decrypt(value) ?: return@forEach
                val parts = decrypted.split("|")
                if (parts.size >= 5) {
                    accounts[instanceId] = VirtualGoogleAccount(
                        instanceId = instanceId,
                        email = parts[0],
                        displayName = parts[1],
                        photoUrl = parts[2],
                        googleId = parts[3],
                        createdAt = parts[4].toLongOrNull() ?: System.currentTimeMillis()
                    )
                }
            }
        }
    }

    // ====================================================================
    // Cleanup
    // ====================================================================

    fun cleanup(instanceId: String) {
        removeAccount(instanceId)
    }

    fun cleanupAll() {
        accounts.clear()
        tokenCache.clear()
        prefs?.edit()?.clear()?.apply()
        Timber.tag(TAG).i("All virtual accounts cleaned up")
    }

    fun shutdown() {
        cleanupAll()
        initialized = false
    }
}

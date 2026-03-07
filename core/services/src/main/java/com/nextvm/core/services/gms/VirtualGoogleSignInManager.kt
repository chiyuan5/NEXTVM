package com.nextvm.core.services.gms

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Virtual Google Sign-In Manager — Hybrid Google authentication for guest apps.
 *
 * HYBRID AUTH FLOW:
 * ┌────────────┐     ┌──────────────────────┐     ┌───────────────┐
 * │ Guest App  │ ──> │ VGoogleSignInManager │ ──> │ Host GMS Auth │
 * │ GoogleSignIn│     │ (intercept + route)  │     │ (real OAuth)  │
 * │ .getClient()│     │                      │     │               │
 * └────────────┘     └──────────────────────┘     └───────────────┘
 *
 * When a guest app calls GoogleSignIn:
 * 1. We intercept the sign-in intent
 * 2. User authenticates through real GMS on the host device
 * 3. We get genuine OAuth/ID tokens from Google
 * 4. Tokens are stored in VirtualAccountManager (isolated per-instance)
 * 5. The guest app receives a valid GoogleSignInAccount
 *
 * Key principle: Authentication is REAL (through host GMS),
 *               but the account is ISOLATED (per virtual instance).
 */
@Singleton
class VirtualGoogleSignInManager @Inject constructor() {

    companion object {
        private const val TAG = "VGSignIn"

        const val ACTION_SIGN_IN = "com.google.android.gms.auth.GOOGLE_SIGN_IN"
        const val ACTION_ACCOUNT_PICKER = "com.google.android.gms.common.account.CHOOSE_ACCOUNT"
        const val OPTION_REQUEST_ID_TOKEN = "requestIdToken"
        const val OPTION_REQUEST_EMAIL = "requestEmail"
        const val OPTION_REQUEST_PROFILE = "requestProfile"
        const val OPTION_SERVER_CLIENT_ID = "serverClientId"
        const val EXTRA_SIGN_IN_ACCOUNT = "googleSignInAccount"
        const val EXTRA_SIGN_IN_STATUS = "googleSignInStatus"
    }

    // Per-instance auth state
    private val authStates = ConcurrentHashMap<String, GoogleSignInState>()

    // Host device Google accounts
    private var hostGoogleAccounts: List<Account> = emptyList()
    private var appContext: Context? = null

    data class GoogleSignInAccount(
        val id: String,
        val email: String,
        val displayName: String,
        val photoUrl: String? = null,
        val idToken: String? = null,
        val serverAuthCode: String? = null,
        val grantedScopes: Set<String> = emptySet(),
        val isExpired: Boolean = false
    )

    data class GoogleSignInState(
        val instanceId: String,
        var signedInAccount: GoogleSignInAccount? = null,
        var lastSignInTime: Long = 0,
        var silentSignInEnabled: Boolean = false,
        var requestedScopes: Set<String> = emptySet(),
        var serverClientId: String? = null
    )

    fun initialize(context: Context) {
        appContext = context.applicationContext

        // Detect available Google accounts on host device
        try {
            val accountManager = AccountManager.get(context)
            hostGoogleAccounts = accountManager.getAccountsByType("com.google").toList()
            Timber.tag(TAG).i("Found ${hostGoogleAccounts.size} Google accounts on host")
        } catch (e: Exception) {
            Timber.tag(TAG).w("Cannot access host accounts: ${e.message}")
            hostGoogleAccounts = emptyList()
        }

        Timber.tag(TAG).i("VirtualGoogleSignInManager initialized (Hybrid mode)")
    }

    /**
     * Get a real Google auth token from the host device's AccountManager.
     * Used by VirtualGmsManager to attach real tokens to sign-in results.
     */
    fun getRealAuthToken(
        context: Context,
        accountEmail: String? = null,
        scope: String = "oauth2:email profile"
    ): String? {
        return try {
            val accountManager = AccountManager.get(context)
            val accounts = accountManager.getAccountsByType("com.google")
            if (accounts.isEmpty()) return null

            val account = if (accountEmail != null) {
                accounts.firstOrNull { it.name == accountEmail } ?: accounts.firstOrNull()
            } else {
                accounts.firstOrNull()
            } ?: return null

            val future = accountManager.getAuthToken(
                account, scope, null, false, null, null
            )
            val result = future.result
            val token = result.getString(AccountManager.KEY_AUTHTOKEN)
            Timber.tag(TAG).d("Got real auth token for ${account.name}")
            token
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get real auth token")
            null
        }
    }

    fun getAvailableGoogleAccounts(): List<Account> = hostGoogleAccounts

    /**
     * Create a sign-in intent for a guest app.
     * The intent triggers the host device's REAL Google account picker,
     * then routes the selected account back through our virtual sign-in flow.
     *
     * Flow:
     * 1. Guest calls GoogleSignIn.getSignInIntent() → we intercept
     * 2. We return an intent that opens the REAL GMS account picker
     * 3. User selects a Google account on the host device
     * 4. Result comes back through handleSignInResult()
     * 5. We store the account per-instance and get real OAuth tokens
     */
    fun createSignInIntent(instanceId: String, options: Bundle? = null): Intent {
        val state = getOrCreateState(instanceId)

        options?.let {
            state.serverClientId = it.getString(OPTION_SERVER_CLIENT_ID)
            if (it.getBoolean(OPTION_REQUEST_EMAIL, false)) {
                state.requestedScopes = state.requestedScopes + "email"
            }
            if (it.getBoolean(OPTION_REQUEST_PROFILE, false)) {
                state.requestedScopes = state.requestedScopes + "profile"
            }
        }

        // Try to use the real GMS Google Sign-In intent (preferred)
        val realSignInIntent = createRealGmsSignInIntent(instanceId, state)
        if (realSignInIntent != null) {
            return realSignInIntent
        }

        // Fallback: Use Android's built-in account picker
        // This shows the native "Choose an account" dialog listing Google accounts
        val accountPickerIntent = createAccountPickerIntent(instanceId)
        if (accountPickerIntent != null) {
            return accountPickerIntent
        }

        // Last resort: return a self-handled intent (no real UI, uses first available account)
        return createAutoSelectIntent(instanceId, state)
    }

    /**
     * Create an intent that launches the REAL GMS Google Sign-In UI.
     * Uses reflection to invoke GoogleSignInClient on the host.
     */
    private fun createRealGmsSignInIntent(instanceId: String, state: GoogleSignInState): Intent? {
        return try {
            // GoogleSignInOptions.Builder
            val gsoBuilderClass = Class.forName(
                "com.google.android.gms.auth.api.signin.GoogleSignInOptions\$Builder"
            )
            val gsoClass = Class.forName(
                "com.google.android.gms.auth.api.signin.GoogleSignInOptions"
            )
            val defaultSignIn = gsoClass.getDeclaredField("DEFAULT_SIGN_IN").get(null)

            val builder = gsoBuilderClass.getConstructor(gsoClass).newInstance(defaultSignIn)

            // Request email
            if ("email" in state.requestedScopes || state.requestedScopes.isEmpty()) {
                gsoBuilderClass.getDeclaredMethod("requestEmail").invoke(builder)
            }
            // Request profile
            if ("profile" in state.requestedScopes || state.requestedScopes.isEmpty()) {
                gsoBuilderClass.getDeclaredMethod("requestProfile").invoke(builder)
            }
            // Request ID token if server client ID provided
            if (state.serverClientId != null) {
                gsoBuilderClass.getDeclaredMethod("requestIdToken", String::class.java)
                    .invoke(builder, state.serverClientId)
            }

            val gso = gsoBuilderClass.getDeclaredMethod("build").invoke(builder)

            // GoogleSignIn.getClient(context, gso)
            val googleSignInClass = Class.forName(
                "com.google.android.gms.auth.api.signin.GoogleSignIn"
            )
            val getClientMethod = googleSignInClass.getDeclaredMethod(
                "getClient", Context::class.java, gsoClass
            )
            val client = getClientMethod.invoke(null, appContext, gso)

            // client.getSignInIntent()
            val clientClass = Class.forName(
                "com.google.android.gms.auth.api.signin.GoogleSignInClient"
            )
            val getSignInIntentMethod = clientClass.getDeclaredMethod("getSignInIntent")
            val signInIntent = getSignInIntentMethod.invoke(client) as? Intent

            signInIntent?.apply {
                putExtra("_nextvm_instance_id", instanceId)
                putExtra("_nextvm_is_virtual_sign_in", true)
                putExtra("_nextvm_hybrid_mode", true)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d("Real GMS GoogleSignIn not available: ${e.message}")
            null
        }
    }

    /**
     * Create an Android AccountManager account picker intent.
     * Shows the native "Choose an account" dialog.
     *
     * NOTE: Do NOT guard with hostGoogleAccounts.isEmpty() — on Android 11+
     * AccountManager.getAccountsByType() returns empty for third-party apps,
     * but the system account picker UI shows ALL accounts regardless.
     */
    private fun createAccountPickerIntent(instanceId: String): Intent? {
        return try {
            val intent = AccountManager.newChooseAccountIntent(
                null,                   // selectedAccount
                null,                   // allowableAccounts
                arrayOf("com.google"),  // allowableAccountTypes
                null,                   // descriptionOverrideText
                null,                   // addAccountAuthTokenType
                null,                   // addAccountRequiredFeatures
                null                    // addAccountOptions
            )
            intent.putExtra("_nextvm_instance_id", instanceId)
            intent.putExtra("_nextvm_is_virtual_sign_in", true)
            intent.putExtra("_nextvm_hybrid_mode", true)
            intent.putExtra("_nextvm_use_account_picker", true)
            Timber.tag(TAG).d("Created AccountManager picker intent for $instanceId")
            intent
        } catch (e: Exception) {
            Timber.tag(TAG).d("AccountManager.newChooseAccountIntent failed: ${e.message}")
            null
        }
    }

    /**
     * Auto-select the first available Google account on the host device.
     * Used as a last resort when no sign-in UI can be shown.
     */
    private fun createAutoSelectIntent(instanceId: String, state: GoogleSignInState): Intent {
        val intent = Intent(ACTION_SIGN_IN).apply {
            putExtra("_nextvm_instance_id", instanceId)
            putExtra("_nextvm_is_virtual_sign_in", true)
            putExtra("_nextvm_hybrid_mode", true)
        }

        // If host accounts are available, auto-select the first one
        if (hostGoogleAccounts.isNotEmpty()) {
            val account = hostGoogleAccounts.first()
            intent.putExtra("account", account)
            intent.putExtra("email", account.name)
            intent.putExtra("_nextvm_auto_selected", true)

            // Immediately populate the sign-in state
            val realToken = appContext?.let { ctx ->
                getRealAuthToken(ctx, account.name, buildScopeString(state.requestedScopes))
            }

            val signInAccount = GoogleSignInAccount(
                id = account.name.hashCode().toString(),
                email = account.name,
                displayName = account.name.substringBefore("@"),
                idToken = realToken,
                grantedScopes = state.requestedScopes.ifEmpty { setOf("email", "profile") }
            )
            state.signedInAccount = signInAccount
            state.lastSignInTime = System.currentTimeMillis()
            state.silentSignInEnabled = true

            Timber.tag(TAG).i("Auto-selected Google account for $instanceId: ${account.name}")
        }

        return intent
    }

    /**
     * Handle sign-in result.
     * In Hybrid mode, this processes the real Google auth response
     * and stores it per-instance.
     *
     * Handles results from:
     * 1. Real GMS GoogleSignIn intent (contains GoogleSignInAccount parcelable)
     * 2. Android AccountManager account picker (contains selected Account)
     * 3. Auto-selected account (contains email + account in extras)
     */
    fun handleSignInResult(instanceId: String, data: Intent?): GoogleSignInAccount? {
        if (data == null) {
            Timber.tag(TAG).w("Sign-in result is null for $instanceId")
            return null
        }

        val state = getOrCreateState(instanceId)

        // Check if already auto-selected (from createAutoSelectIntent)
        if (data.getBooleanExtra("_nextvm_auto_selected", false)) {
            val existing = state.signedInAccount
            if (existing != null) {
                Timber.tag(TAG).i("Sign-in auto-selected for $instanceId: ${existing.email}")
                return existing
            }
        }

        // Approach 1: Real GMS GoogleSignInAccount result
        val gmsAccount = extractGmsSignInAccount(data)
        if (gmsAccount != null) {
            state.signedInAccount = gmsAccount
            state.lastSignInTime = System.currentTimeMillis()
            state.silentSignInEnabled = true
            Timber.tag(TAG).i("GMS sign-in successful for $instanceId: ${gmsAccount.email}")
            return gmsAccount
        }

        // Approach 2: Android AccountManager picker result
        // The system picker stores the chosen account under KEY_ACCOUNT_NAME / KEY_ACCOUNT_TYPE
        Timber.tag(TAG).d("Sign-in result extras: ${data.extras?.keySet()?.joinToString()}")
        val accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            ?: data.getStringExtra("authAccount")
            ?: data.getStringExtra("selected_account")
        val accountType = data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE) ?: "com.google"

        val account = if (accountName != null) {
            Account(accountName, accountType)
        } else {
            @Suppress("DEPRECATION")
            data.getParcelableExtra<Account>("account")
        }

        if (account != null) {
            val realToken = appContext?.let { ctx ->
                getRealAuthToken(ctx, account.name, buildScopeString(state.requestedScopes))
            }

            val signInAccount = GoogleSignInAccount(
                id = account.name.hashCode().toString(),
                email = account.name,
                displayName = account.name.substringBefore("@"),
                idToken = realToken,
                grantedScopes = state.requestedScopes.ifEmpty { setOf("email", "profile") }
            )

            state.signedInAccount = signInAccount
            state.lastSignInTime = System.currentTimeMillis()
            state.silentSignInEnabled = true

            Timber.tag(TAG).i("Hybrid sign-in successful for $instanceId: ${account.name}")
            return signInAccount
        }

        // Approach 3: Token + email in intent extras (set by VirtualGmsManager)
        val authToken = data.getStringExtra("auth_token")
        val email = data.getStringExtra("email")
        if (authToken != null && email != null) {
            val signInAccount = GoogleSignInAccount(
                id = email.hashCode().toString(),
                email = email,
                displayName = email.substringBefore("@"),
                idToken = authToken,
                grantedScopes = state.requestedScopes.ifEmpty { setOf("email", "profile") }
            )
            state.signedInAccount = signInAccount
            state.lastSignInTime = System.currentTimeMillis()
            state.silentSignInEnabled = true
            Timber.tag(TAG).i("Token-based sign-in successful for $instanceId: $email")
            return signInAccount
        }

        Timber.tag(TAG).w("Sign-in failed for $instanceId — no account data in result")
        return null
    }

    /**
     * Extract a GoogleSignInAccount from a real GMS sign-in result intent.
     * Uses reflection since the GMS SDK may or may not be on the classpath.
     */
    private fun extractGmsSignInAccount(data: Intent): GoogleSignInAccount? {
        return try {
            val googleSignInClass = Class.forName(
                "com.google.android.gms.auth.api.signin.GoogleSignIn"
            )
            val getSignedInAccountMethod = googleSignInClass.getDeclaredMethod(
                "getSignedInAccountFromIntent",
                Intent::class.java
            )
            val taskObj = getSignedInAccountMethod.invoke(null, data)

            // Task.getResult() — may throw ApiException
            val taskClass = Class.forName("com.google.android.gms.tasks.Task")
            val getResultMethod = taskClass.getDeclaredMethod("getResult")
            val gmsAccount = getResultMethod.invoke(taskObj) ?: return null

            // Extract fields from GoogleSignInAccount
            val accountClass = gmsAccount::class.java
            val email = accountClass.getDeclaredMethod("getEmail").invoke(gmsAccount) as? String
                ?: return null
            val displayName = accountClass.getDeclaredMethod("getDisplayName").invoke(gmsAccount) as? String
                ?: email.substringBefore("@")
            val id = accountClass.getDeclaredMethod("getId").invoke(gmsAccount) as? String
                ?: email.hashCode().toString()
            val idToken = try {
                accountClass.getDeclaredMethod("getIdToken").invoke(gmsAccount) as? String
            } catch (_: Exception) { null }
            val serverAuthCode = try {
                accountClass.getDeclaredMethod("getServerAuthCode").invoke(gmsAccount) as? String
            } catch (_: Exception) { null }
            val photoUrl = try {
                accountClass.getDeclaredMethod("getPhotoUrl").invoke(gmsAccount)?.toString()
            } catch (_: Exception) { null }

            GoogleSignInAccount(
                id = id,
                email = email,
                displayName = displayName,
                photoUrl = photoUrl,
                idToken = idToken,
                serverAuthCode = serverAuthCode,
                grantedScopes = emptySet()
            )
        } catch (e: Exception) {
            Timber.tag(TAG).d("Could not extract GMS GoogleSignInAccount: ${e.message}")
            null
        }
    }

    fun silentSignIn(instanceId: String): GoogleSignInAccount? {
        val state = authStates[instanceId] ?: return null
        if (!state.silentSignInEnabled) return null
        val account = state.signedInAccount
        if (account != null && !account.isExpired) return account
        return null
    }

    fun getSignedInAccount(instanceId: String): GoogleSignInAccount? {
        return authStates[instanceId]?.signedInAccount
    }

    fun isSignedIn(instanceId: String): Boolean {
        return authStates[instanceId]?.signedInAccount != null
    }

    fun signOut(instanceId: String) {
        val state = authStates[instanceId] ?: return
        state.signedInAccount = null
        state.silentSignInEnabled = false
        Timber.tag(TAG).i("Signed out for $instanceId")
    }

    fun revokeAccess(instanceId: String) {
        authStates.remove(instanceId)
        Timber.tag(TAG).i("Access revoked for $instanceId")
    }

    fun isVirtualSignInIntent(intent: Intent): Boolean {
        return intent.getBooleanExtra("_nextvm_is_virtual_sign_in", false)
    }

    /**
     * Intercept a GMS sign-in intent and redirect to Hybrid flow.
     */
    fun interceptSignInIntent(intent: Intent, instanceId: String): Intent? {
        val action = intent.action ?: return null

        if (action == ACTION_SIGN_IN ||
            action == ACTION_ACCOUNT_PICKER ||
            intent.component?.packageName == "com.google.android.gms"
        ) {
            Timber.tag(TAG).d("Intercepted GMS sign-in for $instanceId (Hybrid mode)")
            return Intent(intent).apply {
                putExtra("_nextvm_instance_id", instanceId)
                putExtra("_nextvm_is_virtual_sign_in", true)
                putExtra("_nextvm_hybrid_mode", true)
                putExtra("_nextvm_original_action", action)
            }
        }

        return null
    }

    fun cleanup(instanceId: String) {
        authStates.remove(instanceId)
    }

    // ==================== Internal Helpers ====================

    private fun getOrCreateState(instanceId: String): GoogleSignInState {
        return authStates.getOrPut(instanceId) { GoogleSignInState(instanceId) }
    }

    private fun buildScopeString(scopes: Set<String>): String {
        val scopeList = scopes.ifEmpty { setOf("email", "profile") }
        return "oauth2:${scopeList.joinToString(" ")}"
    }
}

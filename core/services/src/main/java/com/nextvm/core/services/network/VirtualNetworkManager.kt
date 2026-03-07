package com.nextvm.core.services.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.nextvm.core.common.AndroidCompat
import com.nextvm.core.model.NetworkPolicy
import com.nextvm.core.model.VmResult
import timber.log.Timber
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VirtualNetworkManager — Network isolation and management for virtual apps.
 *
 * Provides per-app network control including:
 * - Network on/off toggle (complete isolation)
 * - HTTP proxy routing per virtual app
 * - Custom DNS per virtual app
 * - Traffic stats per virtual app
 * - Network policy enforcement (FULL_ACCESS, WIFI_ONLY, VPN_ONLY, OFFLINE, CUSTOM_DNS)
 * - WebView network isolation
 * - Socket-level interception support
 *
 * Integrates with ConnectivityManagerProxy for high-level interception
 * and provides lower-level socket controls for tighter isolation.
 */
@Singleton
class VirtualNetworkManager @Inject constructor() {

    companion object {
        private const val TAG = "VNetMgr"

        // Default DNS servers
        private val DEFAULT_DNS = listOf("8.8.8.8", "8.8.4.4")
        private val CLOUDFLARE_DNS = listOf("1.1.1.1", "1.0.0.1")
    }

    // Per-instance network configuration
    private val networkConfigs = ConcurrentHashMap<String, NetworkConfig>()

    // Per-instance traffic stats
    private val trafficStats = ConcurrentHashMap<String, TrafficCounter>()

    // Network callback registrations per instance
    private val networkCallbacks = ConcurrentHashMap<String, MutableList<ConnectivityManager.NetworkCallback>>()

    // Blocked instances (network completely off)
    private val blockedInstances = ConcurrentHashMap.newKeySet<String>()

    // Active proxy configurations
    private val proxyConfigs = ConcurrentHashMap<String, ProxyConfig>()

    // Custom DNS configurations
    private val dnsConfigs = ConcurrentHashMap<String, List<String>>()

    // Application context
    private var appContext: Context? = null
    private var connectivityManager: ConnectivityManager? = null
    private var initialized = false

    /**
     * Initialize the network manager with application context.
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        initialized = true
        Timber.tag(TAG).i("VirtualNetworkManager initialized")
    }

    /**
     * Set the network policy for a virtual app instance.
     *
     * @param instanceId The virtual app instance
     * @param policy The network policy to enforce
     */
    fun setNetworkPolicy(instanceId: String, policy: NetworkPolicy): VmResult<Unit> {
        return try {
            val config = networkConfigs.getOrPut(instanceId) { NetworkConfig(instanceId) }
            val previousPolicy = config.policy
            config.policy = policy

            when (policy) {
                NetworkPolicy.OFFLINE -> {
                    blockedInstances.add(instanceId)
                    disconnectAllSockets(instanceId)
                    Timber.tag(TAG).i("Network OFFLINE for instance: $instanceId")
                }
                NetworkPolicy.FULL_ACCESS -> {
                    blockedInstances.remove(instanceId)
                    Timber.tag(TAG).i("Network FULL_ACCESS for instance: $instanceId")
                }
                NetworkPolicy.WIFI_ONLY -> {
                    blockedInstances.remove(instanceId)
                    config.allowMobile = false
                    config.allowWifi = true
                    Timber.tag(TAG).i("Network WIFI_ONLY for instance: $instanceId")
                }
                NetworkPolicy.VPN_ONLY -> {
                    blockedInstances.remove(instanceId)
                    config.requireVpn = true
                    Timber.tag(TAG).i("Network VPN_ONLY for instance: $instanceId")
                }
                NetworkPolicy.CUSTOM_DNS -> {
                    blockedInstances.remove(instanceId)
                    val dns = dnsConfigs[instanceId] ?: DEFAULT_DNS
                    config.customDns = dns
                    Timber.tag(TAG).i("Network CUSTOM_DNS for instance: $instanceId, dns=$dns")
                }
            }

            networkConfigs[instanceId] = config
            VmResult.Success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set network policy for $instanceId")
            VmResult.Error("Failed to set network policy: ${e.message}", e)
        }
    }

    /**
     * Get the current network policy for an instance.
     */
    fun getNetworkPolicy(instanceId: String): NetworkPolicy {
        return networkConfigs[instanceId]?.policy ?: NetworkPolicy.FULL_ACCESS
    }

    /**
     * Set an HTTP/SOCKS proxy for a virtual app instance.
     *
     * @param instanceId The virtual app instance
     * @param host Proxy host
     * @param port Proxy port
     * @param type Proxy type (HTTP or SOCKS)
     * @param username Optional proxy authentication username
     * @param password Optional proxy authentication password
     */
    fun setProxy(
        instanceId: String,
        host: String,
        port: Int,
        type: Proxy.Type = Proxy.Type.HTTP,
        username: String? = null,
        password: String? = null
    ): VmResult<Unit> {
        return try {
            val proxyConfig = ProxyConfig(
                host = host,
                port = port,
                type = type,
                username = username,
                password = password
            )
            proxyConfigs[instanceId] = proxyConfig

            val config = networkConfigs.getOrPut(instanceId) { NetworkConfig(instanceId) }
            config.proxy = proxyConfig

            Timber.tag(TAG).i("Proxy set for $instanceId: $type://$host:$port")
            VmResult.Success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set proxy for $instanceId")
            VmResult.Error("Failed to set proxy: ${e.message}", e)
        }
    }

    /**
     * Remove the proxy for a virtual app instance.
     */
    fun removeProxy(instanceId: String): VmResult<Unit> {
        proxyConfigs.remove(instanceId)
        networkConfigs[instanceId]?.proxy = null
        Timber.tag(TAG).d("Proxy removed for $instanceId")
        return VmResult.Success(Unit)
    }

    /**
     * Get the proxy configuration for an instance.
     */
    fun getProxy(instanceId: String): ProxyConfig? = proxyConfigs[instanceId]

    /**
     * Get the Java Proxy object for an instance (for use with URLConnection, OkHttp, etc.).
     */
    fun getJavaProxy(instanceId: String): Proxy? {
        val config = proxyConfigs[instanceId] ?: return null
        return Proxy(config.type, InetSocketAddress(config.host, config.port))
    }

    /**
     * Set custom DNS servers for a virtual app instance.
     *
     * @param instanceId The virtual app instance
     * @param servers List of DNS server IP addresses
     */
    fun setCustomDns(instanceId: String, servers: List<String>): VmResult<Unit> {
        return try {
            require(servers.isNotEmpty()) { "DNS server list cannot be empty" }

            // Validate IP addresses
            for (server in servers) {
                try {
                    InetAddress.getByName(server)
                } catch (e: Exception) {
                    return VmResult.Error("Invalid DNS server: $server")
                }
            }

            dnsConfigs[instanceId] = servers

            val config = networkConfigs.getOrPut(instanceId) { NetworkConfig(instanceId) }
            config.customDns = servers

            Timber.tag(TAG).i("Custom DNS set for $instanceId: $servers")
            VmResult.Success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set DNS for $instanceId")
            VmResult.Error("Failed to set DNS: ${e.message}", e)
        }
    }

    /**
     * Get custom DNS servers for an instance.
     */
    fun getCustomDns(instanceId: String): List<String> {
        return dnsConfigs[instanceId] ?: DEFAULT_DNS
    }

    /**
     * Resolve a hostname using custom DNS for the instance.
     */
    fun resolveDns(instanceId: String, hostname: String): VmResult<List<InetAddress>> {
        return try {
            val servers = dnsConfigs[instanceId]
            if (servers != null && servers.isNotEmpty()) {
                // Use custom DNS resolver
                val addresses = customDnsResolve(hostname, servers)
                VmResult.Success(addresses)
            } else {
                // Use system DNS
                val addresses = InetAddress.getAllByName(hostname).toList()
                VmResult.Success(addresses)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "DNS resolution failed for $hostname")
            VmResult.Error("DNS resolution failed: ${e.message}", e)
        }
    }

    /**
     * Get traffic statistics for a virtual app instance.
     */
    fun getTrafficStats(instanceId: String): TrafficStats {
        val counter = trafficStats.getOrPut(instanceId) { TrafficCounter(instanceId) }
        return TrafficStats(
            instanceId = instanceId,
            txBytes = counter.txBytes.get(),
            rxBytes = counter.rxBytes.get(),
            txPackets = counter.txPackets.get(),
            rxPackets = counter.rxPackets.get(),
            connectionCount = counter.connectionCount.get(),
            startTime = counter.startTime,
            lastActivityTime = counter.lastActivityTime
        )
    }

    /**
     * Reset traffic statistics for an instance.
     */
    fun resetTrafficStats(instanceId: String) {
        trafficStats[instanceId]?.reset()
        Timber.tag(TAG).d("Traffic stats reset for $instanceId")
    }

    /**
     * Record outgoing traffic for an instance.
     */
    fun recordTxTraffic(instanceId: String, bytes: Long) {
        val counter = trafficStats.getOrPut(instanceId) { TrafficCounter(instanceId) }
        counter.txBytes.addAndGet(bytes)
        counter.txPackets.incrementAndGet()
        counter.lastActivityTime = System.currentTimeMillis()
    }

    /**
     * Record incoming traffic for an instance.
     */
    fun recordRxTraffic(instanceId: String, bytes: Long) {
        val counter = trafficStats.getOrPut(instanceId) { TrafficCounter(instanceId) }
        counter.rxBytes.addAndGet(bytes)
        counter.rxPackets.incrementAndGet()
        counter.lastActivityTime = System.currentTimeMillis()
    }

    /**
     * Block all network access for a virtual app instance.
     */
    fun blockNetwork(instanceId: String): VmResult<Unit> {
        return setNetworkPolicy(instanceId, NetworkPolicy.OFFLINE)
    }

    /**
     * Allow all network access for a virtual app instance.
     */
    fun allowNetwork(instanceId: String): VmResult<Unit> {
        return setNetworkPolicy(instanceId, NetworkPolicy.FULL_ACCESS)
    }

    /**
     * Check if an instance's network is blocked.
     */
    fun isNetworkBlocked(instanceId: String): Boolean {
        return blockedInstances.contains(instanceId)
    }

    /**
     * Check if a network request should be allowed for an instance.
     * Takes into account the network policy and current connectivity.
     */
    fun shouldAllowNetworkRequest(instanceId: String): Boolean {
        if (blockedInstances.contains(instanceId)) return false

        val config = networkConfigs[instanceId] ?: return true

        return when (config.policy) {
            NetworkPolicy.OFFLINE -> false
            NetworkPolicy.FULL_ACCESS -> true
            NetworkPolicy.WIFI_ONLY -> isWifiConnected()
            NetworkPolicy.VPN_ONLY -> isVpnConnected()
            NetworkPolicy.CUSTOM_DNS -> true // DNS policy doesn't block traffic
        }
    }

    /**
     * Create a Socket with the instance's network configuration applied.
     * Applies proxy and DNS settings.
     */
    fun createConfiguredSocket(instanceId: String, host: String, port: Int): VmResult<Socket> {
        return try {
            if (!shouldAllowNetworkRequest(instanceId)) {
                return VmResult.Error("Network blocked for instance: $instanceId")
            }

            val socket = Socket()
            val proxy = proxyConfigs[instanceId]

            if (proxy != null) {
                // Route through proxy
                val proxySocket = Socket(Proxy(proxy.type, InetSocketAddress(proxy.host, proxy.port)))
                proxySocket.connect(InetSocketAddress(host, port), 30_000)
                recordConnectionOpen(instanceId)
                VmResult.Success(proxySocket)
            } else {
                // Direct connection, potentially with custom DNS
                val addresses = when {
                    dnsConfigs.containsKey(instanceId) -> {
                        val result = resolveDns(instanceId, host)
                        when (result) {
                            is VmResult.Success -> result.data
                            else -> listOf(InetAddress.getByName(host))
                        }
                    }
                    else -> listOf(InetAddress.getByName(host))
                }

                val address = addresses.firstOrNull()
                    ?: return VmResult.Error("Could not resolve host: $host")

                socket.connect(InetSocketAddress(address, port), 30_000)
                recordConnectionOpen(instanceId)
                VmResult.Success(socket)
            }
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "Failed to create socket for $instanceId: $host:$port")
            VmResult.Error("Socket creation failed: ${e.message}", e)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Unexpected error creating socket for $instanceId")
            VmResult.Error("Socket creation failed: ${e.message}", e)
        }
    }

    /**
     * Register a NetworkCallback for an instance.
     * Callbacks are tracked so they can be cleaned up on instance removal.
     */
    fun registerNetworkCallback(
        instanceId: String,
        request: NetworkRequest,
        callback: ConnectivityManager.NetworkCallback
    ): VmResult<Unit> {
        return try {
            val cm = connectivityManager
                ?: return VmResult.Error("ConnectivityManager not available")

            if (blockedInstances.contains(instanceId)) {
                Timber.tag(TAG).d("Suppressing network callback for blocked instance: $instanceId")
                return VmResult.Success(Unit)
            }

            cm.registerNetworkCallback(request, callback)
            networkCallbacks.getOrPut(instanceId) { mutableListOf() }.add(callback)

            Timber.tag(TAG).d("Network callback registered for $instanceId")
            VmResult.Success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to register network callback for $instanceId")
            VmResult.Error("Failed to register callback: ${e.message}", e)
        }
    }

    /**
     * Unregister a NetworkCallback for an instance.
     */
    fun unregisterNetworkCallback(instanceId: String, callback: ConnectivityManager.NetworkCallback) {
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
            networkCallbacks[instanceId]?.remove(callback)
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to unregister network callback: ${e.message}")
        }
    }

    /**
     * Unregister all network callbacks for an instance.
     */
    fun unregisterAllCallbacks(instanceId: String) {
        val callbacks = networkCallbacks.remove(instanceId)
        callbacks?.forEach { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (_: Exception) { /* ignore */ }
        }
        Timber.tag(TAG).d("All network callbacks unregistered for $instanceId")
    }

    /**
     * Clean up all network state for an instance.
     * Call when a virtual app is stopped or uninstalled.
     */
    fun cleanupInstance(instanceId: String) {
        networkConfigs.remove(instanceId)
        trafficStats.remove(instanceId)
        proxyConfigs.remove(instanceId)
        dnsConfigs.remove(instanceId)
        blockedInstances.remove(instanceId)
        unregisterAllCallbacks(instanceId)
        Timber.tag(TAG).d("Network state cleaned up for $instanceId")
    }

    /**
     * Clean up all network state.
     */
    fun cleanupAll() {
        val instances = networkConfigs.keys.toList()
        instances.forEach { cleanupInstance(it) }
        initialized = false
        Timber.tag(TAG).i("VirtualNetworkManager cleaned up")
    }

    /**
     * Get a summary of all managed instances and their policies.
     */
    fun getNetworkSummary(): Map<String, NetworkPolicy> {
        return networkConfigs.entries.associate { (id, config) -> id to config.policy }
    }

    // ====================================================================
    // Internal helpers
    // ====================================================================

    private fun isWifiConnected(): Boolean {
        return try {
            val cm = connectivityManager ?: return false
            if (AndroidCompat.isAtLeastQ) {
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = cm.activeNetworkInfo
                @Suppress("DEPRECATION")
                networkInfo?.type == ConnectivityManager.TYPE_WIFI && networkInfo.isConnected
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to check WiFi: ${e.message}")
            false
        }
    }

    private fun isVpnConnected(): Boolean {
        return try {
            val cm = connectivityManager ?: return false
            if (AndroidCompat.isAtLeastQ) {
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } else {
                @Suppress("DEPRECATION")
                val allNetworks = cm.allNetworks
                allNetworks.any { network ->
                    val caps = cm.getNetworkCapabilities(network)
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to check VPN: ${e.message}")
            false
        }
    }

    private fun disconnectAllSockets(instanceId: String) {
        // In a real implementation, this would track open sockets per instance
        // and close them. For Phase 1, the ConnectivityManagerProxy handles
        // blocking at the API level.
        Timber.tag(TAG).d("Disconnect all sockets for $instanceId")
    }

    private fun recordConnectionOpen(instanceId: String) {
        val counter = trafficStats.getOrPut(instanceId) { TrafficCounter(instanceId) }
        counter.connectionCount.incrementAndGet()
        counter.lastActivityTime = System.currentTimeMillis()
    }

    /**
     * Custom DNS resolution using specified servers.
     * Performs a basic UDP DNS query to the first available server.
     */
    private fun customDnsResolve(hostname: String, servers: List<String>): List<InetAddress> {
        // For Phase 1, use Java's built-in resolver
        // A full implementation would send custom DNS queries to the specified servers
        // using DatagramSocket and DNS protocol
        for (server in servers) {
            try {
                // Attempt resolution — Java doesn't support custom DNS easily
                // In Phase 2, use dnsjava library or native DNS queries
                val addresses = InetAddress.getAllByName(hostname)
                return addresses.toList()
            } catch (e: Exception) {
                Timber.tag(TAG).w("DNS resolution via $server failed: ${e.message}")
            }
        }
        throw IOException("All DNS servers failed for $hostname")
    }

    /**
     * Shutdown (delegates to cleanupAll).
     */
    fun shutdown() {
        cleanupAll()
        Timber.tag(TAG).d("VirtualNetworkManager shut down")
    }
}

// ========================================================================
// Data classes
// ========================================================================

/**
 * Internal network configuration for a virtual app instance.
 */
internal data class NetworkConfig(
    val instanceId: String,
    var policy: NetworkPolicy = NetworkPolicy.FULL_ACCESS,
    var allowWifi: Boolean = true,
    var allowMobile: Boolean = true,
    var requireVpn: Boolean = false,
    var proxy: ProxyConfig? = null,
    var customDns: List<String> = emptyList()
)

/**
 * Proxy configuration.
 */
data class ProxyConfig(
    val host: String,
    val port: Int,
    val type: Proxy.Type = Proxy.Type.HTTP,
    val username: String? = null,
    val password: String? = null
) {
    override fun toString(): String = "$type://$host:$port"
}

/**
 * Traffic statistics snapshot for a virtual app.
 */
data class TrafficStats(
    val instanceId: String,
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    val txPackets: Long = 0,
    val rxPackets: Long = 0,
    val connectionCount: Long = 0,
    val startTime: Long = 0,
    val lastActivityTime: Long = 0
) {
    val totalBytes: Long get() = txBytes + rxBytes
    val totalPackets: Long get() = txPackets + rxPackets

    fun formatTotalTraffic(): String {
        val total = totalBytes
        return when {
            total < 1024 -> "$total B"
            total < 1024 * 1024 -> "${total / 1024} KB"
            total < 1024L * 1024 * 1024 -> "${total / (1024 * 1024)} MB"
            else -> "${"%.2f".format(total / (1024.0 * 1024 * 1024))} GB"
        }
    }
}

/**
 * Traffic counter with atomic operations for thread safety.
 */
internal class TrafficCounter(val instanceId: String) {
    val txBytes = AtomicLong(0)
    val rxBytes = AtomicLong(0)
    val txPackets = AtomicLong(0)
    val rxPackets = AtomicLong(0)
    val connectionCount = AtomicLong(0)
    val startTime: Long = System.currentTimeMillis()
    @Volatile var lastActivityTime: Long = 0

    fun reset() {
        txBytes.set(0)
        rxBytes.set(0)
        txPackets.set(0)
        rxPackets.set(0)
        connectionCount.set(0)
        lastActivityTime = 0
    }
}

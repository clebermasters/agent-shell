package com.agentshell.data.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresApi
import com.agentshell.data.model.ConnectionStatus
import com.agentshell.data.remote.WebSocketService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reports this phone's network addresses and wireless-debugging endpoint through the primary socket.
 * NSD is best effort: no result means unavailable, never a guessed ADB port. Discovery does
 * not enable debugging or pair a computer; Android's developer settings still own those steps.
 */
@Singleton
class DeviceAdbReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webSocket: WebSocketService,
) {
    // All discovery state is confined to Main; Android's NSD/network callbacks may use other threads.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val callbackExecutor = Executor { command -> scope.launch { command.run() } }
    private val deviceId by lazy { persistentDeviceId(context) }
    private val deviceName = deviceAdbName(Build.MANUFACTURER, Build.MODEL)
    private var started = false
    private var enabled = false
    private var trackingGeneration = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val networks = DeviceNetworkState<Network>()
    private var discovery: Discovery? = null
    private var heartbeat: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // Pre-34 NSD supports only one outstanding resolution. Keep the slot occupied even when
    // its discovery generation is stopped; its eventual callback releases it for the new run.
    private var legacyResolution: LegacyResolution? = null

    private class Discovery(val network: Network, val addresses: Set<InetAddress>) {
        val endpoints = DeviceAdbEndpoints(addresses)
        val services = linkedMapOf<String, Service>()
        val pendingResolutions = linkedSetOf<String>()
        var listener: NsdManager.DiscoveryListener? = null
        var discoveryRequested = false
        var retry: Job? = null
    }

    private class Service(var info: NsdServiceInfo) {
        var stopUpdates: (() -> Unit)? = null
        var retry: Job? = null
    }

    private class LegacyResolution(val discovery: Discovery, val key: String, val service: Service)

    /** Application-owned and idempotent; survives navigation and activity recreation. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            webSocket.keepAliveEnabled.collect { desired ->
                if (desired) enable() else disable()
            }
        }
        scope.launch {
            webSocket.connectionStatus.collect { status ->
                if (status == ConnectionStatus.CONNECTED) publish()
            }
        }
    }

    private fun enable() {
        if (enabled) return
        enabled = true
        val generation = ++trackingGeneration
        publish()
        heartbeat = scope.launch {
            while (enabled) {
                delay(REPORT_INTERVAL_MS)
                // Legacy resolution is a snapshot; re-resolve to notice a rotated TLS port.
                discovery?.let { run ->
                    if (Build.VERSION.SDK_INT < 34) {
                        run.pendingResolutions.addAll(run.services.keys)
                        resolveNextLegacyService()
                    }
                }
                publish()
            }
        }
        // Network metadata works independently of wireless ADB and its Android 11 requirement.
        if (connectivity == null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val transport = deviceNetworkTransport(capabilities)
                scope.launch {
                    if (!enabled || generation != trackingGeneration) return@launch
                    updateNetworks { updateTransport(network, transport) }
                }
            }

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                val addresses = properties.linkAddresses.map { it.address }
                val interfaceName = properties.interfaceName
                scope.launch {
                    if (!enabled || generation != trackingGeneration) return@launch
                    updateNetworks { updateLinkProperties(network, interfaceName, addresses) }
                }
            }

            override fun onLost(network: Network) {
                scope.launch {
                    if (!enabled || generation != trackingGeneration) return@launch
                    updateNetworks { remove(network) }
                }
            }
        }
        networkCallback = callback
        try {
            // Observe all visible networks, including VPNs and their underlying Wi-Fi/cellular
            // networks. A default callback would hide the other connected interfaces.
            connectivity.registerNetworkCallback(
                deviceNetworkRequest(),
                callback,
            )
        } catch (_: RuntimeException) {
            networkCallback = null
        }
    }

    private fun disable() {
        if (!enabled) return
        enabled = false
        trackingGeneration++
        heartbeat?.cancel()
        heartbeat = null
        networkCallback?.let { callback ->
            runCatching { connectivity?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        networks.clear()
        stopDiscovery()
    }

    private fun updateNetworks(update: DeviceNetworkState<Network>.() -> Unit) {
        val before = networks.snapshot
        networks.update()
        selectWifiNetwork()
        if (before != networks.snapshot) publish()
    }

    private fun selectWifiNetwork() {
        val wifiAddresses = networks.wifiAddresses
        val current = discovery
        val selected = current?.network?.takeIf { !wifiAddresses[it].isNullOrEmpty() }
            ?: wifiAddresses.entries.firstOrNull { it.value.isNotEmpty() }?.key
        val addresses = selected?.let(wifiAddresses::get).orEmpty()
        if (current?.network == selected && current?.addresses == addresses) return
        stopDiscovery()
        // Clearing the endpoint precedes new asynchronous discovery so a lost Wi-Fi address
        // or stale service cannot be kept alive by the heartbeat.
        publish()
        if (selected != null && addresses.isNotEmpty()) startDiscovery(selected, addresses)
    }

    private fun startDiscovery(network: Network, addresses: Set<InetAddress>) {
        // Metadata remains available on older phones and when NSD is unavailable.
        if (!enabled || nsd == null || Build.VERSION.SDK_INT < 30) return
        val run = Discovery(network, addresses)
        discovery = run
        acquireMulticastLockIfNeeded()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                scope.launch {
                    // A stop can happen while start is still asynchronous.
                    if (!isCurrent(run)) run.listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
                }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                scope.launch {
                    if (!isCurrent(run) || !isAdbConnectService(serviceInfo.serviceType)) return@launch
                    val key = serviceInfo.serviceName ?: return@launch
                    val existing = run.services[key]
                    if (existing != null) {
                        existing.info = serviceInfo
                        if (Build.VERSION.SDK_INT < 34) {
                            run.pendingResolutions.add(key)
                            resolveNextLegacyService()
                        }
                        return@launch
                    }
                    if (run.services.size >= MAX_DISCOVERED_SERVICES) return@launch
                    val service = Service(serviceInfo)
                    run.services[key] = service
                    if (Build.VERSION.SDK_INT >= 34) {
                        observeServiceUpdates(run, key, service)
                    } else {
                        run.pendingResolutions.add(key)
                        resolveNextLegacyService()
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                scope.launch {
                    if (!isCurrent(run)) return@launch
                    val key = serviceInfo.serviceName ?: return@launch
                    val service = run.services.remove(key) ?: return@launch
                    service.retry?.cancel()
                    service.stopUpdates?.invoke()
                    run.pendingResolutions.remove(key)
                    updateEndpoint(run) { remove(key) }
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                scope.launch {
                    if (!isCurrent(run)) return@launch
                    run.discoveryRequested = false
                    retryDiscovery(run)
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                scope.launch {
                    if (isCurrent(run)) {
                        run.discoveryRequested = false
                        retryDiscovery(run)
                    }
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        run.listener = listener
        run.discoveryRequested = true
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                nsd.discoverServices(ADB_CONNECT_SERVICE, NsdManager.PROTOCOL_DNS_SD, network, callbackExecutor, listener)
            } else {
                nsd.discoverServices(ADB_CONNECT_SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        } catch (_: RuntimeException) {
            run.discoveryRequested = false
            retryDiscovery(run)
        }
    }

    private fun retryDiscovery(run: Discovery) {
        updateEndpoint(run) { clear() }
        if (run.retry?.isActive == true) return
        run.retry = scope.launch {
            delay(DISCOVERY_RETRY_MS)
            if (!isCurrent(run)) return@launch
            val network = run.network
            val addresses = run.addresses
            stopDiscovery()
            startDiscovery(network, addresses)
        }
    }

    private fun stopDiscovery() {
        val run = discovery
        discovery = null // Invalidate callbacks before unregistering anything.
        run?.retry?.cancel()
        run?.services?.values?.forEach { service ->
            service.retry?.cancel()
            service.stopUpdates?.invoke()
        }
        if (run?.discoveryRequested == true) {
            run.listener?.let { listener -> runCatching { nsd?.stopServiceDiscovery(listener) } }
        }
        multicastLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        multicastLock = null
    }

    @RequiresApi(34)
    private fun observeServiceUpdates(run: Discovery, key: String, service: Service) {
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(info: NsdServiceInfo) {
                if (!isCurrentService(run, key, service)) return
                updateEndpoint(run) { update(key, info.hostAddresses, info.port) }
            }

            override fun onServiceLost() {
                if (isCurrentService(run, key, service)) updateEndpoint(run) { remove(key) }
            }

            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                if (!isCurrentService(run, key, service)) return
                service.stopUpdates = null
                updateEndpoint(run) { remove(key) }
                retryServiceUpdates(run, key, service)
            }

            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        try {
            nsd?.registerServiceInfoCallback(service.info, callbackExecutor, callback)
            service.stopUpdates = { runCatching { nsd?.unregisterServiceInfoCallback(callback) }; Unit }
        } catch (_: RuntimeException) {
            updateEndpoint(run) { remove(key) }
            retryServiceUpdates(run, key, service)
        }
    }

    @RequiresApi(34)
    private fun retryServiceUpdates(run: Discovery, key: String, service: Service) {
        service.retry?.cancel()
        service.retry = scope.launch {
            delay(DISCOVERY_RETRY_MS)
            if (isCurrentService(run, key, service)) observeServiceUpdates(run, key, service)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNextLegacyService() {
        if (legacyResolution != null) return
        val run = discovery ?: return
        val key = run.pendingResolutions.firstOrNull() ?: return
        run.pendingResolutions.remove(key)
        val service = run.services[key] ?: return resolveNextLegacyService()
        val resolution = LegacyResolution(run, key, service)
        legacyResolution = resolution
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                scope.launch { finishLegacyResolution(resolution, info) }
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                scope.launch { finishLegacyResolution(resolution, null) }
            }
        }
        try {
            nsd?.resolveService(service.info, listener)
        } catch (_: RuntimeException) {
            finishLegacyResolution(resolution, null)
        }
    }

    @Suppress("DEPRECATION")
    private fun finishLegacyResolution(resolution: LegacyResolution, info: NsdServiceInfo?) {
        if (legacyResolution !== resolution) return
        legacyResolution = null
        val run = resolution.discovery
        if (isCurrentService(run, resolution.key, resolution.service)) {
            updateEndpoint(run) {
                if (info == null) remove(resolution.key)
                else update(resolution.key, listOfNotNull(info.host), info.port)
            }
        }
        resolveNextLegacyService()
    }

    private fun isCurrent(run: Discovery): Boolean = enabled && discovery === run

    private fun isCurrentService(run: Discovery, key: String, service: Service): Boolean =
        isCurrent(run) && run.services[key] === service

    private fun updateEndpoint(run: Discovery, update: DeviceAdbEndpoints.() -> Unit) {
        if (!isCurrent(run)) return
        val before = run.endpoints.current
        run.endpoints.update()
        if (before != run.endpoints.current) publish()
    }

    private fun publish() {
        if (!webSocket.keepAliveEnabled.value) return
        val serverUrl = webSocket.currentWebSocketUrl ?: return
        webSocket.sendIfConnected(
            deviceAdbReport(deviceId, deviceName, discovery?.endpoints?.current, networks.snapshot),
            serverUrl,
        )
    }

    private fun acquireMulticastLockIfNeeded() {
        val needsLock = Build.VERSION.SDK_INT < 33 ||
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) < 7
        if (!needsLock) return
        try {
            multicastLock = context.applicationContext.getSystemService(WifiManager::class.java)
                ?.createMulticastLock("AgentShell:wireless-adb")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } catch (_: RuntimeException) {
            multicastLock = null
        }
    }
}

internal data class DeviceAdbEndpoint(val host: String, val port: Int)

/** Holds only this phone's resolved endpoints; discovery can also return other people's phones. */
internal class DeviceAdbEndpoints(private val ownAddresses: Set<InetAddress>) {
    private val endpoints = linkedMapOf<String, DeviceAdbEndpoint>()
    val current: DeviceAdbEndpoint? get() = endpoints.values.lastOrNull()

    fun update(service: String, addresses: List<InetAddress>, port: Int) {
        endpoints.remove(service)
        if (port !in 1..65535) return
        val ownAddress = addresses.filter { candidate ->
            !candidate.isAnyLocalAddress && !candidate.isLoopbackAddress &&
                // Link-local IPv6 needs the computer's interface scope, which the phone cannot supply.
                (candidate is Inet4Address || !candidate.isLinkLocalAddress) &&
                ownAddresses.any { it.address.contentEquals(candidate.address) }
        }.sortedBy { if (it is Inet4Address) 0 else 1 }.firstOrNull() ?: return
        endpoints[service] = DeviceAdbEndpoint(ownAddress.hostAddress!!.substringBefore('%'), port)
    }

    fun remove(service: String) { endpoints.remove(service) }
    fun clear() { endpoints.clear() }
}

internal fun deviceAdbReport(
    deviceId: String,
    deviceName: String,
    endpoint: DeviceAdbEndpoint?,
    networks: List<DeviceNetwork> = emptyList(),
): Map<String, Any?> =
    mapOf(
        "type" to "report-device-adb",
        "deviceId" to deviceId,
        "deviceName" to deviceName,
        "host" to endpoint?.host,
        "port" to endpoint?.port,
        "networks" to networks.map(DeviceNetwork::toMessage),
    )

internal fun deviceAdbName(manufacturer: String?, model: String?): String {
    val name = listOfNotNull(manufacturer, model).joinToString(" ")
        .map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()
    var bytes = 0
    return buildString {
        for (codePoint in name.codePoints().toArray()) {
            val character = String(Character.toChars(codePoint))
            val length = character.toByteArray(Charsets.UTF_8).size
            if (bytes + length > 128) break
            append(character)
            bytes += length
        }
    }.trim().ifBlank { "Android device" }
}

internal fun persistentDeviceId(context: Context): String {
    val preferences = context.getSharedPreferences("device_adb_reporter", Context.MODE_PRIVATE)
    val existing = preferences.getString("device_id", null)
    if (existing != null && runCatching { UUID.fromString(existing) }.isSuccess) return existing
    return UUID.randomUUID().toString().also { preferences.edit().putString("device_id", it).apply() }
}

private fun isAdbConnectService(serviceType: String?): Boolean =
    serviceType?.trimEnd('.')?.equals(ADB_CONNECT_SERVICE.trimEnd('.'), ignoreCase = true) == true

private const val ADB_CONNECT_SERVICE = "_adb-tls-connect._tcp."
private const val REPORT_INTERVAL_MS = 30_000L
private const val DISCOVERY_RETRY_MS = 5_000L
private const val MAX_DISCOVERED_SERVICES = 32

package com.agentshell.data.services

import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress

internal data class DeviceNetwork(
    val transport: String,
    val interfaceName: String,
    val addresses: List<String>,
) {
    fun toMessage(): Map<String, Any?> = mapOf(
        "transport" to transport,
        "interface" to interfaceName,
        "addresses" to addresses,
    )
}

/** Joins independent capability/link-property callbacks without retaining lost network addresses. */
internal class DeviceNetworkState<Key> {
    private class Entry {
        var transport: String? = null
        var interfaceName: String? = null
        var addresses: Set<InetAddress> = emptySet()
    }

    private val entries = linkedMapOf<Key, Entry>()

    fun updateTransport(key: Key, transport: String) {
        entries.getOrPut(key, ::Entry).transport = transport
    }

    fun updateLinkProperties(key: Key, interfaceName: String?, addresses: Collection<InetAddress>) {
        entries.getOrPut(key, ::Entry).apply {
            this.interfaceName = interfaceName?.takeIf { name ->
                name.length in 1..64 && name.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "_.:-" }
            }
            // These are local unicast addresses from LinkProperties, never a public-IP guess.
            this.addresses = addresses.filter(::isReportableNetworkAddress).toSet()
        }
    }

    fun remove(key: Key) { entries.remove(key) }
    fun clear() { entries.clear() }

    val snapshot: List<DeviceNetwork>
        get() = entries.values.mapNotNull { entry ->
            val transport = entry.transport ?: return@mapNotNull null
            val interfaceName = entry.interfaceName ?: return@mapNotNull null
            DeviceNetwork(
                transport,
                interfaceName,
                entry.addresses.sortedWith(compareBy<InetAddress> { if (it is Inet4Address) 0 else 1 }
                    .thenBy { it.hostAddress }).take(MAX_NETWORK_ADDRESSES)
                    .map { it.hostAddress!!.substringBefore('%') },
            )
        }.distinct().sortedWith(compareBy<DeviceNetwork> { transportOrder(it.transport) }
            .thenBy { it.interfaceName }).take(MAX_REPORTED_NETWORKS)

    // A VPN can carry TRANSPORT_WIFI as well as TRANSPORT_VPN. Only physical Wi-Fi entries
    // qualify for mDNS self-filtering; a tunnel address never becomes a guessed ADB endpoint.
    val wifiAddresses: Map<Key, Set<InetAddress>>
        get() = entries.filterValues { it.transport == "wifi" && it.interfaceName != null && it.addresses.isNotEmpty() }
            .mapValues { it.value.addresses }
}

internal fun deviceNetworkTransport(capabilities: NetworkCapabilities): String = when {
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
    else -> "other"
}

internal fun deviceNetworkRequest(): NetworkRequest = NetworkRequest.Builder().apply {
    if (Build.VERSION.SDK_INT >= 30) {
        clearCapabilities()
    } else {
        // These defaults otherwise exclude VPNs and some additional visible networks.
        removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
        removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    }
}.build()

private fun isReportableNetworkAddress(address: InetAddress): Boolean =
    !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isMulticastAddress &&
        (address is Inet4Address || !address.isLinkLocalAddress) &&
        !(address is Inet4Address && address.address.all { it.toInt() and 0xff == 255 })

private fun transportOrder(transport: String): Int = when (transport) {
    "vpn" -> 0
    "wifi" -> 1
    "cellular" -> 2
    "ethernet" -> 3
    else -> 4
}

private const val MAX_REPORTED_NETWORKS = 16
private const val MAX_NETWORK_ADDRESSES = 16

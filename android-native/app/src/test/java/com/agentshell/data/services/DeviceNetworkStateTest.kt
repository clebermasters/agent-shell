package com.agentshell.data.services

import android.app.Application
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DeviceNetworkStateTest {
    private val networks = DeviceNetworkState<String>()

    @Test fun cellularAddressesAreReportedWhileWirelessAdbIsUnavailable() {
        connect("mobile", "cellular", "rmnet_data0", "100.64.3.9", "2001:db8::9")
        val report = deviceAdbReport("phone", "Samsung", null, networks.snapshot)
        assertNull(report["host"])
        assertNull(report["port"])
        assertEquals(listOf(mapOf(
            "transport" to "cellular", "interface" to "rmnet_data0",
            "addresses" to listOf("100.64.3.9", "2001:db8:0:0:0:0:0:9"),
        )), report["networks"])
        assertTrue(networks.wifiAddresses.isEmpty())
    }

    @Test fun vpnOverCellularKeepsItsOwnAddressAndDoesNotBecomeWifiAdb() {
        connect("mobile", "cellular", "rmnet_data0", "100.64.3.9")
        val capabilities = capabilities(NetworkCapabilities.TRANSPORT_VPN,
            NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_WIFI)
        networks.updateTransport("tunnel", deviceNetworkTransport(capabilities))
        networks.updateLinkProperties("tunnel", "tun0", listOf(ip("10.8.0.4")))
        assertEquals(listOf("vpn", "cellular"), networks.snapshot.map { it.transport })
        assertEquals(listOf("10.8.0.4"), networks.snapshot.first().addresses)
        assertTrue(networks.wifiAddresses.isEmpty())
    }

    @Test fun wifiLossRetainsTheVpnAndCellularMetadata() {
        connect("wifi", "wifi", "wlan0", "192.168.0.187")
        connect("mobile", "cellular", "rmnet_data0", "100.64.3.9")
        connect("tunnel", "vpn", "tun0", "10.8.0.4")
        assertEquals(setOf("wifi"), networks.wifiAddresses.keys)
        networks.remove("wifi")
        assertTrue(networks.wifiAddresses.isEmpty())
        assertEquals(listOf("vpn", "cellular"), networks.snapshot.map { it.transport })
        assertFalse(networks.snapshot.any { "192.168.0.187" in it.addresses })
    }

    @Test fun vpnLossAndAddressRotationNeverRetainThePreviousTunnelAddress() {
        connect("tunnel", "vpn", "tun0", "10.8.0.4")
        networks.updateLinkProperties("tunnel", "tun0", listOf(ip("10.8.0.7")))
        assertEquals(listOf("10.8.0.7"), networks.snapshot.single().addresses)
        networks.remove("tunnel")
        assertTrue(networks.snapshot.isEmpty())
        assertEquals(emptyList<Any>(), deviceAdbReport("phone", "Samsung", null, networks.snapshot)["networks"])
    }

    @Test fun losingAllLinkAddressesClearsThemWithoutHidingTheVisibleInterface() {
        connect("tunnel", "vpn", "tun0", "10.8.0.4")
        networks.updateLinkProperties("tunnel", "tun0", emptyList())
        assertEquals(listOf(DeviceNetwork("vpn", "tun0", emptyList())), networks.snapshot)
    }

    @Test fun transportClassificationPrioritizesVpnOverItsUnderlyingTransports() {
        assertEquals("vpn", deviceNetworkTransport(capabilities(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI)))
        assertEquals("wifi", deviceNetworkTransport(capabilities(NetworkCapabilities.TRANSPORT_WIFI)))
        assertEquals("cellular", deviceNetworkTransport(capabilities(NetworkCapabilities.TRANSPORT_CELLULAR)))
        assertEquals("ethernet", deviceNetworkTransport(capabilities(NetworkCapabilities.TRANSPORT_ETHERNET)))
        assertEquals("other", deviceNetworkTransport(capabilities(NetworkCapabilities.TRANSPORT_BLUETOOTH)))
    }

    @Test fun changingAnEntriesTransportToVpnImmediatelyRemovesItFromWifiDiscovery() {
        connect("network", "wifi", "wlan0", "192.168.0.187")
        assertFalse(networks.wifiAddresses.isEmpty())
        networks.updateTransport("network", "vpn")
        assertTrue(networks.wifiAddresses.isEmpty())
        assertEquals("vpn", networks.snapshot.single().transport)
    }

    @Test fun independentCallbacksCanArriveInEitherOrder() {
        networks.updateLinkProperties("tunnel", "tun0", listOf(ip("10.8.0.4")))
        assertTrue(networks.snapshot.isEmpty())
        networks.updateTransport("tunnel", "vpn")
        assertEquals("tun0", networks.snapshot.single().interfaceName)
        networks.clear()
        assertTrue(networks.snapshot.isEmpty())
        assertTrue(networks.wifiAddresses.isEmpty())
    }

    @Test fun networkRequestIncludesVpnAndDoesNotRequireInternetOrTrust() {
        val request = deviceNetworkRequest()
        for (capability in listOf(NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
            NetworkCapabilities.NET_CAPABILITY_TRUSTED, NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED,
            NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            assertFalse(request.hasCapability(capability))
        }
    }

    @Test @Config(sdk = [26]) fun legacyNetworkRequestAlsoIncludesVpn() {
        // NetworkRequest.hasCapability became public after API 26; inspect its framework
        // capabilities through Robolectric's test helper rather than calling a newer API.
        val capabilities = ReflectionHelpers.getField<NetworkCapabilities>(deviceNetworkRequest(), "networkCapabilities")
        assertFalse(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
    }

    @Test fun filtersUnusableAddressesAndRejectsMalformedInterfaceNames() {
        connect("tunnel", "vpn", "tun0", "10.8.0.4", "0.0.0.0", "127.0.0.1", "255.255.255.255",
            "224.0.0.1", "::", "::1", "ff02::1", "fe80::1")
        assertEquals(listOf("10.8.0.4"), networks.snapshot.single().addresses)
        networks.updateLinkProperties("tunnel", "tun0\n", listOf(ip("10.8.0.4")))
        assertTrue(networks.snapshot.isEmpty())
    }

    @Test fun payloadIsBoundedAndRetainsVpnBeforeOtherInterfaces() {
        for (index in 1..20) connect("other$index", "other", "dummy$index", "10.0.0.$index")
        connect("tunnel", "vpn", "tun0", *(1..20).map { "10.8.0.$it" }.toTypedArray())
        val snapshot = networks.snapshot
        assertEquals(16, snapshot.size)
        assertEquals("vpn", snapshot.first().transport)
        assertEquals(16, snapshot.first().addresses.size)
        assertEquals(setOf("transport", "interface", "addresses"), snapshot.first().toMessage().keys)
    }

    private fun connect(key: String, transport: String, interfaceName: String, vararg addresses: String) {
        networks.updateTransport(key, transport)
        networks.updateLinkProperties(key, interfaceName, addresses.map(::ip))
    }

    private fun ip(value: String): InetAddress = InetAddress.getByName(value)

    private fun capabilities(vararg transports: Int): NetworkCapabilities = ReflectionHelpers.getField(
        NetworkRequest.Builder().apply { transports.forEach { addTransportType(it) } }.build(),
        "networkCapabilities",
    )
}

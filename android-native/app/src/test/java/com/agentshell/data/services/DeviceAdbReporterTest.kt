package com.agentshell.data.services

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.net.InetAddress
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DeviceAdbReporterTest {
    private val phone = ip("192.168.0.187")
    private val endpoints = DeviceAdbEndpoints(setOf(phone))

    @Test fun onlyReportsAResolvedAddressBelongingToThisPhonesWifi() {
        endpoints.update("other-phone", listOf(ip("192.168.0.188")), 35567)
        assertNull(endpoints.current)
        endpoints.update("own-phone", listOf(phone), 35567)
        assertEquals(DeviceAdbEndpoint("192.168.0.187", 35567), endpoints.current)
        endpoints.update("another-phone", listOf(ip("192.168.0.189")), 40000)
        assertEquals(35567, endpoints.current?.port)
    }

    @Test fun aChangedTlsPortReplacesThePreviousResolvedPort() {
        endpoints.update("own-phone", listOf(phone), 35567)
        endpoints.update("own-phone", listOf(phone), 40421)
        assertEquals(DeviceAdbEndpoint("192.168.0.187", 40421), endpoints.current)
        endpoints.remove("own-phone")
        assertNull(endpoints.current)
    }

    @Test fun anUpdatedServiceCannotKeepItsOldEndpointAfterMovingOffThePhone() {
        endpoints.update("own-phone", listOf(phone), 35567)
        endpoints.update("own-phone", listOf(ip("192.168.0.188")), 35567)
        assertNull(endpoints.current)
    }

    @Test fun losingAnOldServiceDoesNotDiscardANewerEndpoint() {
        endpoints.update("old-instance", listOf(phone), 35567)
        endpoints.update("new-instance", listOf(phone), 40421)
        endpoints.remove("old-instance")
        assertEquals(40421, endpoints.current?.port)
        endpoints.clear()
        assertNull(endpoints.current)
    }

    @Test fun aNewWifiAddressCannotReuseThePreviousNetworkEndpoint() {
        endpoints.update("own-phone", listOf(phone), 35567)
        val newNetwork = DeviceAdbEndpoints(setOf(ip("10.0.0.4")))
        newNetwork.update("stale-service", listOf(phone), 35567)
        assertNull(newNetwork.current)
        newNetwork.update("fresh-service", listOf(ip("10.0.0.4")), 40421)
        assertEquals(DeviceAdbEndpoint("10.0.0.4", 40421), newNetwork.current)
    }

    @Test fun invalidPortsClearThePreviousEndpoint() {
        for (port in listOf(-1, 0, 65536)) {
            endpoints.update("own-phone", listOf(phone), 35567)
            endpoints.update("own-phone", listOf(phone), port)
            assertNull(endpoints.current)
        }
    }

    @Test fun prefersMatchingIpv4ButAlsoSupportsGlobalIpv6() {
        val ipv6 = ip("2001:db8::7")
        val both = DeviceAdbEndpoints(setOf(phone, ipv6))
        both.update("own-phone", listOf(ip("192.168.0.188"), ipv6, phone), 35567)
        assertEquals(phone.hostAddress, both.current?.host)
        both.update("own-phone", listOf(ipv6), 40421)
        assertEquals(ipv6.hostAddress, both.current?.host)
    }

    @Test fun rejectsLoopbackWildcardAndUnscopedLinkLocalIpv6() {
        for (address in listOf("127.0.0.1", "0.0.0.0", "::", "::1", "fe80::7")) {
            val own = ip(address)
            val candidates = DeviceAdbEndpoints(setOf(own))
            candidates.update("own-phone", listOf(own), 35567)
            assertNull("Unsafe endpoint $address", candidates.current)
        }
    }

    @Test fun unavailableReportsContainExplicitNullHostAndPort() {
        val report = deviceAdbReport("device-id", "Samsung phone", null)
        assertEquals("report-device-adb", report["type"])
        assertEquals("device-id", report["deviceId"])
        assertEquals("Samsung phone", report["deviceName"])
        assertTrue(report.containsKey("host"))
        assertTrue(report.containsKey("port"))
        assertNull(report["host"])
        assertNull(report["port"])
        assertEquals(35567, deviceAdbReport("device-id", "Phone", DeviceAdbEndpoint("192.168.0.187", 35567))["port"])
    }

    @Test fun deviceNamesAreReadableAndBoundedInUtf8Bytes() {
        assertEquals("Samsung SM-F966U", deviceAdbName("Samsung", "SM-F966U"))
        assertEquals("Android device", deviceAdbName(null, "\n\t"))
        val name = deviceAdbName("日本".repeat(30), "📱".repeat(30))
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 128)
        assertFalse(name.contains('\uFFFD'))
        assertFalse(deviceAdbName("Maker\n", "Model\u0000").any { it.isISOControl() })
    }

    @Test fun deviceIdentitySurvivesReporterRecreationAndRepairsCorruptedPreferences() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val first = persistentDeviceId(app)
        assertEquals(UUID.fromString(first).toString(), first)
        assertEquals(first, persistentDeviceId(app))
        app.getSharedPreferences("device_adb_reporter", 0).edit().putString("device_id", "broken").commit()
        val replacement = persistentDeviceId(app)
        assertEquals(UUID.fromString(replacement).toString(), replacement)
        assertEquals(replacement, persistentDeviceId(app))
    }

    private fun ip(value: String): InetAddress = InetAddress.getByName(value)
}

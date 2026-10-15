package com.agentshell.data.repository

import android.app.Application
import com.agentshell.data.model.Host
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class BrowserEndpointTest {
    private val legacy=Host("existing-id","192.168.0.76","192.168.0.76",4010,sortOrder=7)
    private val secure=Host("default-id","LOCAL_LAN","lan.example.test",4443,tls=true)
    private val requests=mutableListOf<Host>()
    private fun capabilities(port:Int=4010,origin:String=secure.httpUrl)=JSONObject()
        .put("available",true).put("httpPort",port).put("viewerOrigin",origin)
    private fun repository(response:JSONObject=capabilities(),address:String="192.168.0.76")=
        object:BrowserRepository(OkHttpClient.Builder().dns(object:Dns {
            override fun lookup(hostname:String)=listOf(InetAddress.getByName(address))
        }).build()) {
            override suspend fun request(host:Host,path:String,body:JSONObject?):JSONObject {
                assertTrue("An HTTP request escaped secure discovery",host.isSecure)
                requests.add(host);return response
            }
        }
    @Test fun oldCustomNamedIpBookmarkUsesMatchingTlsWithoutChangingItsIdentity()=runTest {
        val endpoint=repository().endpoint(legacy,listOf(legacy,secure))
        assertEquals(secure.httpUrl,endpoint.host.httpUrl)
        assertEquals(legacy.id,endpoint.host.id)
        assertEquals(legacy.name,endpoint.host.name)
        assertEquals(legacy.sortOrder,endpoint.host.sortOrder)
        assertEquals(listOf(secure),requests)
        assertFalse(legacy.isSecure)
    }
    @Test fun unrelatedAddressIsNeverProbedOrUsed()=runTest {
        try { repository(address="192.168.0.38").endpoint(legacy,listOf(secure));fail() }
        catch(error:BrowserFailure) { assertEquals("HTTPS_REQUIRED",error.code) }
        assertTrue(requests.isEmpty())
    }
    @Test fun sameIpDifferentBackendPortCannotCaptureTheBookmark()=runTest {
        try { repository(capabilities(port=4011)).endpoint(legacy,listOf(secure));fail() }
        catch(error:BrowserFailure) { assertEquals("HTTPS_REQUIRED",error.code) }
    }
    @Test fun httpsMustAdvertiseItsOwnOriginAndItsHttpPort()=runTest {
        for(response in listOf(capabilities(origin="https://other.example.test"),capabilities().apply { remove("httpPort") })) {
            try { repository(response).endpoint(legacy,listOf(secure));fail() } catch(_:BrowserFailure) { }
        }
    }
    @Test fun secureSavedServersStillWorkWithOlderBackendCapabilities()=runTest {
        val endpoint=repository(JSONObject().put("viewerOrigin",secure.httpUrl)).endpoint(secure,emptyList())
        assertEquals(secure,endpoint.host)
    }
    @Test fun publicIpAndHttpHostnameAreNotSilentlyRedirected()=runTest {
        for(address in listOf("8.8.8.8","unknown.example.test")) {
            try { repository().endpoint(legacy.copy(address=address),listOf(secure));fail() }
            catch(error:BrowserFailure) { assertEquals("HTTPS_REQUIRED",error.code) }
        }
        assertTrue(requests.isEmpty())
    }
}

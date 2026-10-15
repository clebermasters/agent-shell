package com.agentshell.feature.browser

import android.app.Application
import com.agentshell.data.model.Host
import com.agentshell.data.repository.BrowserFailure
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class BrowserOriginTest {
    private val public=Host("public","Public","public.example.test",443)
    private val lan=Host("lan","LAN","lan.example.test",4443,tls=true)
    @Test fun oldBackendKeepsItsSingleTrustedOrigin() {
        val capabilities=JSONObject("""{"viewerOrigin":"https://public.example.test"}""")
        assertEquals(public.httpUrl,viewerOrigin(capabilities,public))
        assertThrows(BrowserFailure::class.java) { viewerOrigin(capabilities,lan) }
    }
    @Test fun lanMustBeExplicitlyAllowedAndKeepsItsOwnOrigin() {
        val capabilities=JSONObject("""{"viewerOrigin":"https://public.example.test","viewerOrigins":["https://public.example.test","https://lan.example.test:4443"]}""")
        assertEquals(lan.httpUrl,viewerOrigin(capabilities,lan))
        assertEquals(public.httpUrl,viewerOrigin(capabilities,public))
        assertThrows(BrowserFailure::class.java) { viewerOrigin(capabilities,lan.copy(address="attacker.example.test")) }
        assertThrows(BrowserFailure::class.java) { viewerOrigin(capabilities,lan.copy(tls=false)) }
    }
}

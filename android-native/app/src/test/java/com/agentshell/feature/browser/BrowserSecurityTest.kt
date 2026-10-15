package com.agentshell.feature.browser

import com.agentshell.data.model.Host
import org.junit.Assert.*
import org.junit.Test

class BrowserSecurityTest {
    @Test fun oldHostsPreserveTheirProtocol() {
        assertEquals("https://example.test",Host("a","Host","example.test",443).httpUrl)
        assertEquals("ws://example.test:4010",Host("a","Host","example.test",4010).wsUrl)
    }
    @Test fun explicitTlsWorksOnNonstandardPorts() {
        val host=Host("a","Host","example.test",4443,tls=true)
        assertEquals("https://example.test:4443",host.httpUrl)
        assertEquals("wss://example.test:4443",host.wsUrl)
        assertFalse(Host("a","Host","example.test",443,tls=false).isSecure)
    }
    @Test fun aViewerLaunchContainsOnlyScopedAuthorization() {
        val launch=BrowserLaunch("https://example.test","fixture","temporary-grant")
        assertEquals("https://example.test/browser/fixture/bootstrap",launch.bootstrapUrl)
        assertFalse(launch.bootstrapUrl.contains(launch.grant))
    }
}

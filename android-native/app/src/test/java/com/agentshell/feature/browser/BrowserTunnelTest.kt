package com.agentshell.feature.browser

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BrowserTunnelTest {
    private fun headers(socket:Socket):String {
        val bytes=java.io.ByteArrayOutputStream()
        while(bytes.size()<8192) {
            val next=socket.getInputStream().read();if(next<0) break
            bytes.write(next)
            if(bytes.toString("US-ASCII").endsWith("\r\n\r\n")) break
        }
        return bytes.toString("US-ASCII")
    }
    @Test fun connectRelaysOpaqueBytesOnlyToItsExactAuthority() {
        ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).use { target ->
            target.soTimeout=3000
            val executor=Executors.newSingleThreadExecutor()
            try {
                val received=executor.submit<String> { target.accept().use { remote ->
                    remote.soTimeout=3000
                    val bytes=ByteArray(4);java.io.DataInputStream(remote.getInputStream()).readFully(bytes)
                    remote.getOutputStream().write("pong".toByteArray());String(bytes)
                } }
                BrowserTunnel("lan.example.test",target.localPort,"127.0.0.1").use { proxy ->
                    Socket("127.0.0.1",proxy.port).use { client ->
                        client.soTimeout=3000
                        client.getOutputStream().write("CONNECT lan.example.test:${target.localPort} HTTP/1.1\r\n\r\n".toByteArray())
                        assertTrue(headers(client).startsWith("HTTP/1.1 200"))
                        client.getOutputStream().write("ping".toByteArray())
                        val bytes=ByteArray(4);java.io.DataInputStream(client.getInputStream()).readFully(bytes)
                        assertEquals("pong",String(bytes))
                    }
                }
                assertEquals("ping",received.get(3,TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun connectRejectsOtherHostsPortsAndPlainHttpRequests() {
        BrowserTunnel("lan.example.test",4443,"127.0.0.1").use { proxy ->
            for(request in listOf("CONNECT other.example.test:4443 HTTP/1.1","CONNECT lan.example.test:443 HTTP/1.1","GET https://lan.example.test:4443/ HTTP/1.1")) {
                Socket("127.0.0.1",proxy.port).use { client ->
                    client.soTimeout=3000
                    client.getOutputStream().write((request+"\r\n\r\n").toByteArray())
                    assertTrue(headers(client).startsWith("HTTP/1.1 403"))
                }
            }
        }
    }
    @Test fun directRouteRequiresAPrivateOrLoopbackAddress() {
        assertThrows(IllegalArgumentException::class.java) { BrowserTunnel("lan.example.test",4443,"8.8.8.8") }
    }
}

package com.agentshell.feature.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/** Loopback CONNECT transport for one exact HTTPS authority. It never decrypts TLS. */
internal class BrowserTunnel(private val hostname:String,private val targetPort:Int,address:String):AutoCloseable {
    private val target=InetAddress.getByName(address).also { require(it.isSiteLocalAddress || it.isLoopbackAddress) }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val sockets=Collections.synchronizedSet(mutableSetOf<Socket>())
    private val server=ServerSocket().apply { bind(InetSocketAddress("127.0.0.1",0),8) }
    val port get()=server.localPort

    init { scope.launch {
        while(!server.isClosed) {
            val incoming=try { server.accept() } catch (_:Exception) { break }
            if(sockets.size>=16) { incoming.close();continue }
            sockets.add(incoming)
            launch { connect(incoming) }
        }
    } }

    private fun connect(incoming:Socket) {
        var remote:Socket?=null
        var delegated=false
        try {
            incoming.soTimeout=10_000
            val bytes=ByteArrayOutputStream()
            val input=incoming.getInputStream().buffered()
            var tail=0
            var complete=false
            while(bytes.size()<8192) {
                val byte=input.read()
                if(byte<0) return
                bytes.write(byte)
                tail=(tail shl 8) or byte
                if(tail==0x0d0a0d0a) { complete=true;break }
            }
            val request=bytes.toString("US-ASCII").substringBefore("\r\n").split(' ')
            if(!complete || request.size!=3 || request[0]!="CONNECT" || !request[1].equals("$hostname:$targetPort",ignoreCase=true) || !request[2].startsWith("HTTP/1.")) {
                incoming.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray());return
            }
            remote=Socket().apply { connect(InetSocketAddress(target,targetPort),10_000);tcpNoDelay=true }
            val outgoing=remote
            sockets.add(outgoing)
            incoming.soTimeout=0;incoming.tcpNoDelay=true
            incoming.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            delegated=true
            scope.launch { try { input.copyTo(outgoing.getOutputStream(),32768) } catch (_:Exception) { }
                finally { closeSocket(incoming);closeSocket(outgoing) } }
            scope.launch { try { outgoing.getInputStream().copyTo(incoming.getOutputStream(),32768) } catch (_:Exception) { }
                finally { closeSocket(incoming);closeSocket(outgoing) } }
        } catch (_:Exception) { }
        finally { if(!delegated) { closeSocket(incoming);remote?.let { closeSocket(it) } } }
    }

    private fun closeSocket(socket:Socket) { runCatching { socket.close() };sockets.remove(socket) }

    override fun close() {
        server.close()
        synchronized(sockets) { sockets.toList().forEach { runCatching { it.close() } };sockets.clear() }
        scope.cancel()
    }
}

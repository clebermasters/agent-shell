package com.agentshell.data.remote

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** System DNS first. Some home routers suppress legitimate private-address DNS answers. */
@Singleton
class ServerDns @Inject constructor() : Dns {
    private val resolver = OkHttpClient.Builder().dns(Dns.SYSTEM)
        .callTimeout(8,TimeUnit.SECONDS).build()

    override fun lookup(hostname:String):List<InetAddress> {
        try { return Dns.SYSTEM.lookup(hostname) } catch (_:UnknownHostException) { }
        try {
            val url=okhttp3.HttpUrl.Builder().scheme("https").host("cloudflare-dns.com").addPathSegment("dns-query")
                .addQueryParameter("name",hostname).addQueryParameter("type","A").build()
            resolver.newCall(Request.Builder().url(url).header("Accept","application/dns-json").build()).execute().use { response ->
                if(!response.isSuccessful) throw UnknownHostException(hostname)
                val body=response.body ?: throw UnknownHostException(hostname)
                val bytes=body.byteStream().use { input ->
                    val output=ByteArrayOutputStream();val buffer=ByteArray(4096)
                    while(true) {
                        val count=input.read(buffer)
                        if(count<0) break
                        if(output.size()+count>65536) throw UnknownHostException(hostname)
                        output.write(buffer,0,count)
                    }
                    output.toByteArray()
                }
                val json=JSONObject(String(bytes,Charsets.UTF_8))
                if(json.optInt("Status",-1)!=0) throw UnknownHostException(hostname)
                val answers=json.optJSONArray("Answer") ?: throw UnknownHostException(hostname)
                val addresses=(0 until answers.length()).mapNotNull { index ->
                    val answer=answers.getJSONObject(index)
                    if(answer.optInt("type")!=1) return@mapNotNull null
                    val parts=answer.optString("data").split('.').map { it.toIntOrNull() }
                    if(parts.size!=4 || parts.any { it==null || it !in 0..255 }) return@mapNotNull null
                    InetAddress.getByAddress(hostname,parts.map { it!!.toByte() }.toByteArray())
                }.distinct().take(16)
                if(addresses.isEmpty()) throw UnknownHostException(hostname)
                return addresses
            }
        } catch (_:Exception) { throw UnknownHostException("No DNS address for $hostname") }
    }
}

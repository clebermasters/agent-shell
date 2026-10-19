package com.agentshell.data.repository

import com.agentshell.core.config.BuildConfig
import com.agentshell.data.model.Host
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.net.UnknownHostException
import java.net.InetAddress
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Inject
import javax.inject.Singleton

class BrowserFailure(val code: String, val phonePreserved: Boolean = false, val computerClosed: Boolean = false) : IOException(code)
internal data class BrowserEndpoint(val host:Host,val capabilities:JSONObject)

@Singleton
open class BrowserRepository @Inject constructor(private val client: OkHttpClient) {
    val viewerId: String = UUID.randomUUID().toString()
    /** Old IP bookmarks retain their identity; only the browser transport uses the verified HTTPS alias. */
    internal suspend fun endpoint(selected:Host,knownHosts:List<Host>):BrowserEndpoint {
        if(selected.isSecure) return BrowserEndpoint(selected,request(selected,"/api/browser/capabilities"))
        val address=privateBrowserAddress(selected.address) ?: throw BrowserFailure("HTTPS_REQUIRED")
        var failure:IOException?=null
        for(candidate in knownHosts.filter { it.isSecure }.distinctBy { it.httpUrl }) {
            try {
                val matches=withContext(Dispatchers.IO) { address in client.dns.lookup(candidate.address) }
                if(!matches) continue
                // Discovery itself is authenticated HTTPS; never probe/send a grant over the old HTTP URL.
                val capabilities=request(candidate,"/api/browser/capabilities")
                if(capabilities.optInt("httpPort",-1)!=selected.port) continue
                val origin=candidate.httpUrl.toHttpUrl().toString().trimEnd('/')
                val aliases=capabilities.optJSONArray("viewerOrigins")
                val allowed=if(aliases==null) listOf(capabilities.optString("viewerOrigin"))
                    else (0 until aliases.length()).map { aliases.optString(it) }
                if(origin !in allowed) { failure=BrowserFailure("VIEWER_HOST_MISMATCH");continue }
                return BrowserEndpoint(selected.copy(address=candidate.address,port=candidate.port,tls=true),capabilities)
            } catch(error:IOException) { failure=error }
        }
        throw failure ?: BrowserFailure("HTTPS_REQUIRED")
    }
    /** Keep the HTTPS hostname, routing its encrypted bytes directly when system DNS filters LAN answers. */
    internal open suspend fun viewerTunnelAddress(host:Host):String? = withContext(Dispatchers.IO) {
        try { Dns.SYSTEM.lookup(host.address); null } catch (_:UnknownHostException) {
            client.dns.lookup(host.address).firstOrNull { it.isSiteLocalAddress || it.isLoopbackAddress }?.hostAddress
                ?: throw BrowserFailure("LAN_DNS_ROUTING_UNAVAILABLE")
        }
    }
    open suspend fun request(host: Host, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        if (!host.isSecure) throw BrowserFailure("HTTPS_REQUIRED")
        val request = Request.Builder().url(host.httpUrl + path).header("X-Auth-Token", BuildConfig.AUTH_TOKEN)
        if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
        val transport = if(path.endsWith("/native-handoff") || path.endsWith("/profiles/open")) client.newBuilder()
            .readTimeout(115,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS).build()
        else if (path.endsWith("/desktop-handoff")) client.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS).callTimeout(65, TimeUnit.SECONDS).build() else client
        transport.newCall(request.build()).execute().use { response ->
            val data = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
            if (!response.isSuccessful) throw BrowserFailure(data?.optString("code")?.takeIf { it.isNotBlank() } ?: "CONNECTION_FAILED", data?.optBoolean("phonePreserved") == true, data?.optBoolean("computerClosed") == true)
            data ?: throw BrowserFailure("INVALID_SERVER_RESPONSE")
        }
    }
}

private fun privateBrowserAddress(address:String):InetAddress? {
    val parts=address.split('.').map { it.toIntOrNull() }
    if(parts.size!=4 || parts.any { it==null || it !in 0..255 }) return null
    return InetAddress.getByAddress(parts.map { it!!.toByte() }.toByteArray())
        .takeIf { it.isSiteLocalAddress || it.isLoopbackAddress }
}

package com.agentshell.feature.browser

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.model.Host
import com.agentshell.data.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Inject

data class BrowserUiState(val server: String = "", val title: String = "Chrome", val status: String = "loading",
    val error: String? = null, val launch: BrowserLaunch? = null, val canTakeControl: Boolean = false,
    val canSendToComputer: Boolean = false, val sessionId: String? = null, val generation: String? = null, val notice: String? = null,
    val canNativeHandoff: Boolean = false)

@HiltViewModel
class BrowserViewModel @Inject constructor(private val hosts: HostRepository, private val browser: BrowserRepository,
    saved: SavedStateHandle) : ViewModel() {
    private val viewerId = saved.get<String>("browserViewerId") ?: browser.viewerId.also { saved["browserViewerId"] = it }
    private val _ui = MutableStateFlow(BrowserUiState())
    val uiState = _ui.asStateFlow()
    private var host: Host? = null
    private var browserHost:Host?=null
    private var active = false
    private var job: Job? = null
    private var transferJob: Job? = null
    private var viewerConnected = false
    private var primaryOrigin: String? = null
    private var attempt = 0L
    init { viewModelScope.launch {
        hosts.getSelectedHost().collect { selected ->
            if (host != selected) {
                host = selected; browserHost=null;primaryOrigin=null;job?.cancel(); attempt++
                _ui.value = BrowserUiState(server=selected?.name ?: "Choose a server")
                if (active) connect()
            }
        }
    } }
    fun resume() { if (!active) { active=true;if (_ui.value.status !in listOf("on-computer","on-native") && transferJob?.isActive!=true) connect() } }
    fun pause() { active=false;attempt++;job?.cancel();_ui.update { it.copy(launch=null) } }
    fun connect(start: Boolean = false, takeControl: Boolean = false, cancelHandoff: String? = null, notice: String? = null) {
        if (!active || (transferJob?.isActive==true && cancelHandoff==null)) return
        val selected=host ?: run { _ui.update{it.copy(status="stopped",error="Select a server first.")};return }
        job?.cancel();val current=++attempt
        _ui.update{it.copy(status="connecting",error=null,launch=null,notice=notice)}
        job=viewModelScope.launch {
            try {
                val endpoint=browser.endpoint(selected,hosts.loadBuildTimeHosts()+hosts.getHosts().first())
                if(current!=attempt || host?.id!=selected.id || !active) return@launch
                val secure=endpoint.host
                browserHost=secure
                val capabilities=endpoint.capabilities
                if(!capabilities.optBoolean("available")) throw BrowserFailure("BROWSER_NOT_CONFIGURED")
                val origin=viewerOrigin(capabilities,secure)
                primaryOrigin=capabilities.getString("viewerOrigin")
                var session=capabilities.getJSONObject("session")
                if(start) session=browser.request(secure,"/api/browser/sessions",JSONObject())
                if(current!=attempt || host?.id!=selected.id || !active) return@launch
                val title=capabilities.optString("displayName","Chrome")
                val canTake=capabilities.optBoolean("desktopHandoff")
                val canSend=capabilities.optBoolean("mobileHandoff")
                val canNative=capabilities.optBoolean("nativeHandoff")
                _ui.update { it.copy(canTakeControl=canTake,canSendToComputer=canSend,canNativeHandoff=canNative) }
                if(session.optString("state") in listOf("native","native-closed")) {
                    _ui.value=BrowserUiState(selected.name,title,"on-native",canTakeControl=canTake,canSendToComputer=canSend,
                        sessionId=session.getString("sessionId"),generation=session.getString("runtimeGeneration"),canNativeHandoff=canNative,
                        notice=notice ?: if(session.optString("state")=="native-closed") "Native Chrome was closed. Use on this phone restores its tabs." else "Chrome is open directly on your computer's desktop.")
                    return@launch
                }
                if(session.optString("state")!="ready") {
                    _ui.value=BrowserUiState(selected.name,title,session.optString("state","stopped"),session.optString("code").takeIf{it.isNotBlank()}?.let(::browserError))
                    return@launch
                }
                val id=UUID.fromString(session.getString("sessionId")).toString()
                val grant=browser.request(secure,"/api/browser/sessions/$id/viewer-grants",
                    JSONObject().put("viewerId",viewerId).put("runtimeGeneration",session.getString("runtimeGeneration"))
                        .apply { if(origin!=capabilities.getString("viewerOrigin")) put("viewerOrigin",origin) }
                        .apply { if(canTake) put("takeControl",takeControl);if(cancelHandoff!=null) put("cancelHandoffId",cancelHandoff) })
                val tunnelAddress=browser.viewerTunnelAddress(secure)
                if(current==attempt && host?.id==selected.id && active) _ui.value=BrowserUiState(selected.name,title,"connecting",launch=BrowserLaunch(origin,id,grant.getString("grant"),tunnelAddress),
                    canTakeControl=canTake,canSendToComputer=canSend,sessionId=id,generation=session.getString("runtimeGeneration"),notice=notice,canNativeHandoff=canNative)
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(error:Exception) {
                val code=(error as? BrowserFailure)?.code ?: if(error is javax.net.ssl.SSLException) "CERTIFICATE_ERROR" else "CONNECTION_FAILED"
                if(current==attempt) {
                    _ui.update{it.copy(status="offline",error=browserError(code),launch=null)}
                    if(code=="CONNECTION_FAILED" && active) { delay(5000);if(current==attempt && active) connect(takeControl=takeControl,cancelHandoff=cancelHandoff,notice=notice) }
                }
            }
        }
    }
    fun connection(launch:BrowserLaunch,status:String) {
        if(_ui.value.launch!=launch || !active) return
        viewerConnected=status=="connected"
        if(transferJob?.isActive==true) return
        _ui.update{it.copy(status=status)}
        if(status in listOf("disconnected","renderer-error")) {
            job?.cancel();job=viewModelScope.launch { delay(1500);if(active && _ui.value.launch==launch) connect() }
        } else if(status in listOf("authorization-error","certificate-error","viewer-error","lan-routing-error")) {
            val code=when(status){"certificate-error"->"CERTIFICATE_ERROR";"viewer-error"->"VIEWER_ERROR";"lan-routing-error"->"LAN_DNS_ROUTING_UNAVAILABLE";else->"VIEWER_AUTHORIZATION_REQUIRED"}
            _ui.update{it.copy(error=browserError(code),launch=null)}
        }
    }

    fun useOnPhone() { if(_ui.value.status!="on-native") connect(takeControl=true) }
    private fun currentBrowserHost()=browserHost?.takeIf { it.id==host?.id } ?: host?.takeIf { it.isSecure }
    fun moveNative(toComputer:Boolean) {
        val selected=currentBrowserHost() ?: return
        val previous=_ui.value
        val id=previous.sessionId ?: return
        val generation=previous.generation ?: return
        if(!active || transferJob?.isActive==true || !previous.canNativeHandoff) return
        if(toComputer && previous.status!="connected") return
        if(!toComputer && previous.status!="on-native") return
        job?.cancel();attempt++
        _ui.update{it.copy(status="switching-native",error=null,notice=null)}
        transferJob=viewModelScope.launch {
            try {
                val result=browser.request(selected,"/api/browser/sessions/$id/native-handoff",JSONObject()
                    .put("viewerId",viewerId).put("runtimeGeneration",generation).put("destination",if(toComputer) "desktop" else "phone"))
                if(!result.optBoolean("connected")) throw BrowserFailure("NATIVE_HANDOFF_FAILED")
                if(host?.id!=selected.id) return@launch
                transferJob=null
                if(toComputer) {
                    val session=result.getJSONObject("session")
                    _ui.update{it.copy(status="on-native",launch=null,generation=session.getString("runtimeGeneration"),
                        notice="Chrome is open directly on your computer's desktop.")}
                } else if(active) connect(notice="Chrome was restored on this phone.")
            } catch(error:Exception) {
                if(error is CancellationException) throw error
                if(host?.id!=selected.id) return@launch
                val code=(error as? BrowserFailure)?.code ?: "NATIVE_HANDOFF_FAILED"
                val message=browserError(code)
                transferJob=null
                if((error as? BrowserFailure)?.phonePreserved==true && viewerConnected && _ui.value.launch==previous.launch) {
                    _ui.update{it.copy(status="connected",notice=message)}
                } else if(active) connect(notice=message)
                else _ui.update{it.copy(status="offline",error=message,launch=null)}
            }
        }
    }
    fun sendToComputer() {
        val selected=currentBrowserHost() ?: return
        val previous=_ui.value
        val grantOrigin=previous.launch?.origin?.takeIf { it!=primaryOrigin }
        val id=previous.sessionId ?: return
        val generation=previous.generation ?: return
        if(!active || previous.status!="connected" || !previous.canSendToComputer || transferJob?.isActive==true) return
        job?.cancel();attempt++
        val handoff=UUID.randomUUID().toString()
        _ui.update{it.copy(status="sending-to-computer",error=null,notice=null)}
        transferJob=viewModelScope.launch {
            try {
                val result=browser.request(selected,"/api/browser/sessions/$id/desktop-handoff",
                    JSONObject().put("viewerId",viewerId).put("runtimeGeneration",generation).put("handoffId",handoff))
                if(!result.optBoolean("connected")) throw BrowserFailure("DESKTOP_UNAVAILABLE")
                if(host?.id==selected.id) _ui.update { it.copy(status="on-computer",launch=null,error=null,
                    notice="Chrome is on your computer. Use on this phone to bring it back.") }
            } catch(error:Exception) {
                if(error is CancellationException) {
                    // Cancel the transfer and invalidate its nonce atomically. A
                    // late desktop launch cannot take control after recovery.
                    withContext(NonCancellable) { runCatching {
                        browser.request(selected,"/api/browser/sessions/$id/viewer-grants",JSONObject()
                            .put("viewerId",viewerId).put("runtimeGeneration",generation)
                            .apply { grantOrigin?.let { put("viewerOrigin",it) } }
                            .put("takeControl",true).put("cancelHandoffId",handoff))
                    } }
                    throw error
                }
                if(host?.id!=selected.id) return@launch
                val code=(error as? BrowserFailure)?.code ?: "CONNECTION_FAILED"
                val message=browserError(code)
                if((error as? BrowserFailure)?.phonePreserved==true && viewerConnected && _ui.value.launch==previous.launch) {
                    _ui.update{it.copy(status="connected",notice=message+" Continue on this phone.")}
                } else if(active) {
                    // connect() needs the transfer flag cleared before it can
                    // launch the authenticated recovery request.
                    transferJob=null
                    connect(takeControl=true,cancelHandoff=handoff,notice=message+" Reconnecting on this phone.")
                } else _ui.update{it.copy(status="offline",error=message,launch=null)}
            }
        }
    }

    fun stop() {
        val selected=currentBrowserHost() ?: return;val id=_ui.value.launch?.sessionId ?: return
        attempt++;job?.cancel();_ui.update{it.copy(status="stopping",launch=null)}
        job=viewModelScope.launch {
            try { browser.request(selected,"/api/browser/sessions/$id/stop",JSONObject());if(active) connect() }
            catch(error:Exception) { if(error is CancellationException) throw error;_ui.update{it.copy(status="offline",error="Unable to stop Chrome. Reconnect to check its state.")} }
        }
    }
}

internal fun viewerOrigin(capabilities:JSONObject,host:Host):String {
    val selected=host.httpUrl.toHttpUrl().toString().trimEnd('/')
    val aliases=capabilities.optJSONArray("viewerOrigins")
    val allowed=if(aliases==null) listOf(capabilities.getString("viewerOrigin"))
        else (0 until aliases.length()).map { aliases.getString(it) }
    if(!host.isSecure || selected !in allowed) throw BrowserFailure("VIEWER_HOST_MISMATCH")
    return selected
}

internal fun browserError(code:String):String=when(code) {
    "PROFILE_IN_USE" -> "Chrome is open on this computer. Close it normally, then start the browser here."
    "HTTPS_REQUIRED" -> "Choose a server with HTTPS enabled to open your browser securely."
    "LAN_DNS_ROUTING_UNAVAILABLE" -> "This network filters LAN DNS. Update Android System WebView or use the public server."
    "VIEWER_HOST_MISMATCH" -> "Connect to the browser's configured HTTPS server address."
    "BROWSER_NOT_CONFIGURED" -> "Install the browser component on this server to enable Chrome."
    "CONTROLLER_IN_USE" -> "Another viewer is controlling this browser. Use on this phone to bring it here."
    "DESKTOP_UNAVAILABLE" -> "The computer desktop is unavailable. Sign in on the computer and try again."
    "DESKTOP_CONNECTION_FAILED" -> "The computer viewer could not connect."
    "HANDOFF_IN_PROGRESS" -> "A browser transfer is already in progress."
    "HANDOFF_SUPERSEDED", "CONTROLLER_CHANGED" -> "Another action changed browser control. Choose Use on this phone to take control."
    "HANDOFF_EXPIRED" -> "The browser transfer was cancelled."
    "CHROME_CLOSE_BLOCKED" -> "Chrome needs confirmation before closing. Resolve its dialog or quit Chrome normally; it was left open."
    "CREDENTIAL_STORE_LOCKED" -> "Unlock the computer's credential store before moving Chrome."
    "NATIVE_HANDOFF_FAILED" -> "Chrome could not move between displays. Check its current location and retry."
    "UNCLEAN_CHROME_EXIT" -> "Chrome did not finish a normal exit. Check the restored browser before retrying."
    "CREDENTIAL_STORE_UNAVAILABLE" -> "The computer's credential store is unavailable. Unlock the desktop session, then retry."
    "CERTIFICATE_ERROR" -> "The server certificate could not be verified. Check its HTTPS configuration."
    "VIEWER_AUTHORIZATION_REQUIRED" -> "Browser access expired. Reconnect to obtain new access."
    "VIEWER_ERROR" -> "The browser viewer could not load. Check the installed streaming component, then reconnect."
    "RUNTIME_CONFIGURATION_CHANGED" -> "Browser settings changed while Chrome was running. Stop the managed browser on the computer, then restart it."
    else -> "The browser could not connect. Check the server and try again."
}

package com.agentshell.feature.browser

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.model.Host
import com.agentshell.data.repository.*
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.TranscriptionJob
import com.agentshell.data.services.AudioService
import com.agentshell.data.services.TranscriptionQueueService
import com.agentshell.data.services.TranscriptionReceipt
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
    saved: SavedStateHandle, private val audio: AudioService, private val transcriptions: TranscriptionQueueService,
    private val preferences: PreferencesDataStore) : ViewModel() {
    private val viewerId = saved.get<String>("browserViewerId") ?: browser.viewerId.also { saved["browserViewerId"] = it }
    private val _ui = MutableStateFlow(BrowserUiState())
    val uiState = _ui.asStateFlow()
    private val _profiles = MutableStateFlow(BrowserProfilesUiState())
    val profileUiState = _profiles.asStateFlow()
    private var profilesJob: Job? = null
    private var host: Host? = null
    private var browserHost:Host?=null
    private var active = false
    private var job: Job? = null
    private var transferJob: Job? = null
    private var viewerConnected = false
    private var primaryOrigin: String? = null
    private var attempt = 0L
    private val _voice = MutableStateFlow(BrowserVoiceUiState())
    val voiceUiState = _voice.asStateFlow()
    private val audioOwner = "browser:${UUID.randomUUID()}"
    private val voiceGate = BrowserVoiceGate()
    private val voiceReceipt = TranscriptionReceipt()
    private var recording: BrowserVoiceCapture? = null
    private var transcriptionContext: String? = null
    init { viewModelScope.launch {
        hosts.getSelectedHost().collect { selected ->
            if (host != selected) {
                invalidateVoice(); cancelVoiceRecording()
                host = selected; browserHost=null;primaryOrigin=null;job?.cancel();profilesJob?.cancel(); attempt++
                _profiles.value=BrowserProfilesUiState()
                _ui.value = BrowserUiState(server=selected?.name ?: "Choose a server")
                if (active) connect()
            }
        }
    } }
    init {
        viewModelScope.launch { audio.recordingDuration.collect { seconds ->
            if(recording != null) _voice.update { it.copy(recordingDuration=seconds) }
        } }
        viewModelScope.launch { transcriptions.resultEvents.collect(::receiveTranscription) }
        viewModelScope.launch { transcriptions.pendingResult.collect { result ->
            if(result?.source == TranscriptionJob.Source.BROWSER) {
                receiveTranscription(result)
                transcriptions.clearPendingResult(result)
            }
        } }
    }
    fun resume() { if (!active) { active=true;if (_ui.value.status !in listOf("on-computer","on-native") && transferJob?.isActive!=true) connect() } }
    fun pause() { invalidateVoice();cancelVoiceRecording();active=false;attempt++;job?.cancel();_ui.update { it.copy(launch=null) } }
    fun connect(start: Boolean = false, takeControl: Boolean = false, cancelHandoff: String? = null, notice: String? = null) {
        if (!active || (transferJob?.isActive==true && cancelHandoff==null)) return
        val selected=host ?: run { _ui.update{it.copy(status="stopped",error="Select a server first.")};return }
        invalidateVoice()
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
                val supportsProfiles=capabilities.optBoolean("profileSelection")
                _profiles.update { it.copy(available=supportsProfiles) }
                if(supportsProfiles) {
                    // The server's active selection is authoritative. A remembered
                    // choice must never close a different active desktop browser.
                    try {
                        val catalog=browser.request(secure,"/api/browser/profiles")
                        if(current!=attempt || host?.id!=selected.id || !active) return@launch
                        applyProfiles(catalog,preferences.browserProfileId(selected.id).first())
                        catalog.optJSONObject("session")?.let { session=it }
                    } catch(error:Exception) {
                        if(error is CancellationException) throw error
                        // A profile-list failure must not disable an otherwise
                        // healthy browser or any of its existing controls.
                        _profiles.update { it.copy(error="Unable to load Chrome profiles. Refresh the list to retry.") }
                    }
                }
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
    private fun applyProfiles(data:JSONObject, remembered:String? = _profiles.value.rememberedProfileId) {
        val entries=browserProfiles(data)
        val selected=data.optString("selectedProfileId").takeIf { it.isNotBlank() }
            ?: entries.firstOrNull { it.selected }?.id
        _profiles.update { it.copy(available=data.optBoolean("canSelect",true),profiles=entries,
            selectedProfileId=selected,rememberedProfileId=remembered,loading=false) }
    }

    fun showProfiles() {
        if(!_profiles.value.available || transferJob?.isActive==true) return
        val selected=currentBrowserHost() ?: return
        _profiles.update { it.copy(visible=true,loading=true,error=null,confirmation=null) }
        profilesJob?.cancel()
        profilesJob=viewModelScope.launch {
            try {
                val result=browser.request(selected,"/api/browser/profiles")
                if(host?.id==selected.id) applyProfiles(result,preferences.browserProfileId(selected.id).first())
            } catch(error:Exception) {
                if(error is CancellationException) throw error
                if(host?.id==selected.id) _profiles.update { it.copy(loading=false,error=browserError((error as? BrowserFailure)?.code ?: "CONNECTION_FAILED")) }
            }
        }
    }

    fun dismissProfiles() {
        if(!_profiles.value.switching) _profiles.update { it.copy(visible=false,confirmation=null) }
    }

    fun dismissProfileConfirmation() { _profiles.update { it.copy(confirmation=null) } }

    fun chooseProfile(profileId:String) { openProfile(profileId,null) }

    fun confirmProfile() {
        val confirmation=_profiles.value.confirmation ?: return
        openProfile(confirmation.profileId,confirmation)
    }

    private fun openProfile(profileId:String,confirmation:BrowserProfileConfirmation?) {
        val selected=currentBrowserHost() ?: return
        val profile=_profiles.value.profiles.firstOrNull { it.id==profileId } ?: return
        if(!active || transferJob?.isActive==true || !_profiles.value.available) return
        val previous=_ui.value
        invalidateVoice();cancelVoiceRecording();job?.cancel();profilesJob?.cancel();attempt++
        _profiles.update { it.copy(switching=true,error=null,confirmation=null) }
        // Keep the original viewer mounted until the backend has actually moved
        // the profile; cancelling the confirmation leaves its focus and page intact.
        _ui.update { it.copy(status="switching-profile",error=null) }
        transferJob=viewModelScope.launch {
            try {
                val result=browser.request(selected,"/api/browser/profiles/open",
                    browserProfileRequest(profileId,viewerId,previous.generation,confirmation))
                if(host?.id!=selected.id) return@launch
                if(result.optBoolean("connected")) {
                    preferences.setBrowserProfileId(selected.id,profileId)
                    _profiles.update { it.copy(switching=false,visible=false,confirmation=null,
                        selectedProfileId=profileId,rememberedProfileId=profileId) }
                    transferJob=null
                    _ui.update { it.copy(launch=null) }
                    if(active) connect(takeControl=true,notice="${profile.name} is open on this phone.")
                } else {
                    val pending=browserProfileConfirmation(result,profile) ?: throw BrowserFailure("INVALID_SERVER_RESPONSE")
                    _profiles.update { it.copy(switching=false,confirmation=pending) }
                    _ui.value=if(active) previous else previous.copy(launch=null)
                }
            } catch(error:Exception) {
                if(error is CancellationException) throw error
                if(host?.id!=selected.id) return@launch
                val failure=error as? BrowserFailure
                val message=browserProfileFailureMessage(failure)
                _profiles.update { it.copy(switching=false,error=message,confirmation=null,visible=true) }
                transferJob=null
                val unchanged=failure?.phonePreserved==true || failure?.code in setOf("PROFILE_CONFIRMATION_EXPIRED","PROFILE_CONFIRMATION_INVALID","INVALID_PROFILE_REQUEST","HANDOFF_IN_PROGRESS")
                if(unchanged && previous.launch!=null && viewerConnected) _ui.value=if(active) previous else previous.copy(launch=null)
                else if(active) connect(notice=message)
                else _ui.update { it.copy(status="offline",launch=null,error=message) }
            } finally {
                if(host?.id==selected.id) _profiles.update { it.copy(switching=false) }
            }
        }
    }

    fun connection(launch:BrowserLaunch,status:String) {
        if(_ui.value.launch!=launch || !active) return
        if(status!="connected") invalidateVoice()
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
        invalidateVoice();cancelVoiceRecording()
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
        invalidateVoice();cancelVoiceRecording()
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
        invalidateVoice();cancelVoiceRecording()
        attempt++;job?.cancel();_ui.update{it.copy(status="stopping",launch=null)}
        job=viewModelScope.launch {
            try { browser.request(selected,"/api/browser/sessions/$id/stop",JSONObject());if(active) connect() }
            catch(error:Exception) { if(error is CancellationException) throw error;_ui.update{it.copy(status="offline",error="Unable to stop Chrome. Reconnect to check its state.")} }
        }
    }

    private fun voiceTarget(): BrowserVoiceTarget? {
        val state=_ui.value
        return BrowserVoiceTarget(host?.id ?: return null,state.sessionId ?: return null,
            state.generation ?: return null,viewerId)
    }
    private fun voiceConnected()=active && _ui.value.status=="connected" && _ui.value.launch!=null && transferJob?.isActive!=true

    fun voicePermissionTarget(): BrowserVoiceTarget? = voiceTarget().takeIf { voiceConnected() }

    fun startVoiceRecording(expectedTarget: BrowserVoiceTarget? = null) {
        val target=voiceTarget() ?: return
        val launch=_ui.value.launch ?: return
        if(expectedTarget!=null && expectedTarget!=target) return
        if(!voiceConnected() || recording!=null || _voice.value.isTranscribing || _voice.value.transcripts.isNotEmpty()) return
        try {
            if(audio.startRecording(audioOwner)==null) {
                _voice.update { it.copy(error="Microphone unavailable. Allow microphone access and finish any other recording.") }
                return
            }
            recording=voiceGate.begin(target,launch,attempt)
            _voice.update { it.copy(isRecording=true,recordingDuration=0,error=null,failedJobId=null,failedContext=null) }
        } catch(_:Exception) {
            audio.cancelRecording(audioOwner)
            _voice.update { it.copy(error="Unable to start the microphone. Try again.") }
        }
    }

    fun stopVoiceRecording() {
        val capture=recording ?: return
        recording=null
        val path=audio.stopRecording(audioOwner)
        _voice.update { it.copy(isRecording=false) }
        if(path==null) {
            voiceGate.invalidate()
            _voice.update { it.copy(error="The recording could not be saved. Record for a moment, then tap Stop again.") }
            return
        }
        transcriptionContext=capture.context
        _voice.update { it.copy(isTranscribing=true,error=null,failedJobId=null,failedContext=null) }
        viewModelScope.launch {
            transcriptions.enqueue(path,preferences.openaiApiKey.first(),TranscriptionJob.Source.BROWSER,capture.context)
        }
    }

    fun cancelVoiceRecording() {
        if(recording==null) return
        recording=null
        audio.cancelRecording(audioOwner)
        voiceGate.invalidate()
        _voice.update { it.copy(isRecording=false,recordingDuration=0) }
    }

    private fun receiveTranscription(result: TranscriptionQueueService.PendingResult) {
        if(result.source!=TranscriptionJob.Source.BROWSER) return
        when(result) {
            is TranscriptionQueueService.PendingResult.Failed -> {
                _voice.update { it.copy(isTranscribing=if(result.sourceSessionId==transcriptionContext) false else it.isTranscribing,
                    error="Transcription failed: ${result.error}",failedJobId=result.jobId.takeIf { id -> transcriptions.jobs.value.any { job -> job.id==id } },failedContext=result.sourceSessionId) }
            }
            is TranscriptionQueueService.PendingResult.Success -> {
                if(!voiceReceipt.accept(result.jobId)) return
                val target=browserVoiceTarget(result.sourceSessionId)
                val text=result.text
                val auto=voiceGate.canInsert(result.sourceSessionId,voiceTarget(),_ui.value.launch,attempt,voiceConnected())
                _voice.update { it.copy(isTranscribing=if(result.sourceSessionId==transcriptionContext) false else it.isTranscribing,
                    error=if(text.isBlank()) "No speech was detected. Try recording again." else null,failedJobId=null,failedContext=null,
                    transcripts=if(text.isBlank()) it.transcripts else it.transcripts+BrowserVoiceTranscript(result.jobId,text,target,
                        if(auto) null else "Transcript ready. Focus a field in the original browser, then paste.")) }
                if(auto && target!=null && text.isNotBlank()) requestVoicePaste(result.jobId)
            }
        }
    }

    fun canPasteVoice(jobId: String): Boolean {
        val transcript=_voice.value.transcripts.find { it.jobId==jobId } ?: return false
        return voiceConnected() && transcript.target?.sameRuntime(voiceTarget())==true && _voice.value.delivery==null
    }

    fun requestVoicePaste(jobId: String) {
        if(!canPasteVoice(jobId)) return
        val transcript=_voice.value.transcripts.first { it.jobId==jobId }
        if(transcript.text.length>8192 || '\u0000' in transcript.text) {
            _voice.update { it.copy(transcripts=it.transcripts.map { item ->
                if(item.jobId==jobId) item.copy(message="This transcript cannot be pasted automatically. Copy it instead.") else item }) }
            return
        }
        val delivery=BrowserVoiceDelivery(UUID.randomUUID().toString(),transcript.jobId,transcript.text,voiceTarget() ?: return,
            _ui.value.launch ?: return,attempt)
        _voice.update { it.copy(delivery=delivery) }
    }

    fun isVoiceDeliveryCurrent(delivery: BrowserVoiceDelivery)=_voice.value.delivery==delivery && voiceConnected() &&
        delivery.launch==_ui.value.launch && delivery.attempt==attempt && delivery.target==voiceTarget()

    fun voiceDeliveryResult(delivery: BrowserVoiceDelivery, result: String?) {
        if(!isVoiceDeliveryCurrent(delivery)) return
        val inserted=result in listOf("\"inserted\"","\"duplicate\"")
        _voice.update { state -> state.copy(delivery=null,transcripts=if(inserted) state.transcripts.filterNot { it.jobId==delivery.jobId }
            else state.transcripts.map { if(it.jobId!=delivery.jobId) it else it.copy(message=when(result) {
                "\"unsupported\"" -> "Update the browser component to paste transcripts. You can copy this text now."
                "\"invalid\"" -> "This transcript cannot be pasted automatically. Copy it instead."
                else -> "Paste was not confirmed. Check the field before pasting again."
            }) }) }
        voiceGate.invalidate()
    }

    fun dismissVoiceTranscript(jobId: String) {
        if(_voice.value.delivery?.jobId==jobId) return
        _voice.update { it.copy(transcripts=it.transcripts.filterNot { item -> item.jobId==jobId }) }
    }

    fun retryVoiceTranscription() {
        val state=_voice.value
        val jobId=state.failedJobId ?: return
        if(state.isTranscribing || state.isRecording) return
        if(transcriptions.jobs.value.none { it.id==jobId }) { dismissVoiceError();return }
        // A manual retry may finish much later; insertion now requires an explicit Paste action.
        voiceGate.invalidate();transcriptionContext=state.failedContext
        _voice.update { it.copy(isTranscribing=true,error=null,failedJobId=null,failedContext=null) }
        viewModelScope.launch { transcriptions.retryJob(jobId,preferences.openaiApiKey.first()) }
    }

    fun dismissVoiceError() { _voice.update { it.copy(error=null,failedJobId=null,failedContext=null) } }
    fun voicePermissionDenied() { _voice.update { it.copy(error="Allow microphone access to dictate into the browser.",failedJobId=null,failedContext=null) } }

    private fun invalidateVoice() {
        voiceGate.invalidate()
        _voice.update { state -> state.copy(delivery=null,transcripts=state.transcripts.map {
            if(state.delivery?.jobId==it.jobId) it.copy(message="Paste was interrupted. Check the field before pasting again.") else it
        }) }
    }

    /** Debug fixtures feed a completed result through the same delivery path; no audio API is invoked. */
    internal fun receiveFixtureTranscript(text: String): Boolean {
        if(com.agentshell.core.config.BuildConfig.AUTH_TOKEN!="isolated-ui-poc-only" || !voiceConnected()) return false
        val capture=voiceGate.begin(voiceTarget() ?: return false,_ui.value.launch ?: return false,attempt)
        receiveTranscription(TranscriptionQueueService.PendingResult.Success(text,UUID.randomUUID().toString(),
            TranscriptionJob.Source.BROWSER,capture.context))
        return true
    }

    override fun onCleared() { audio.cancelRecording(audioOwner);super.onCleared() }
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
    "PROFILE_IN_USE" -> "Chrome is open on this computer. Choose its profile to move it to this phone."
    "PROFILE_NOT_FOUND" -> "This Chrome profile is no longer available. Refresh the profile list and choose another."
    "PROFILE_CONFIRMATION_EXPIRED", "PROFILE_CONFIRMATION_INVALID" -> "The browser changed or the confirmation expired. Choose the profile again to review the current warning."
    "PROFILE_SELECTION_UNAVAILABLE" -> "Profile selection is not available on this server."
    "PROFILE_OWNER_UNVERIFIED", "PROFILE_OWNER_MISMATCH" -> "The running Chrome instance could not be verified and was left open. Close it normally on the computer, then retry."
    "PROFILE_OPEN_FAILED" -> "Chrome could not open the selected profile. Check its current location and retry."
    "PROFILE_LIST_FAILED" -> "Unable to load Chrome profiles. Refresh the list to retry."
    "BROWSER_BUSY" -> "Chrome is already starting or moving between displays. Wait for it to finish, then retry."
    "RUNTIME_CHANGED" -> "The browser changed while this action was pending. Reconnect and choose the profile again."
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

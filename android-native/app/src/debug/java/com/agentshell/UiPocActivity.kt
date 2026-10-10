package com.agentshell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agentshell.core.theme.AgentShellTheme
import com.agentshell.data.model.*
import com.agentshell.data.remote.SessionSocket
import com.agentshell.data.services.AudioPlayerManager
import com.agentshell.feature.chat.MessageBubble
import okhttp3.OkHttpClient
import java.util.UUID

/** Debug-only entry point: separate socket and test endpoint, no host preferences. */
class UiPocActivity : ComponentActivity() {
 override fun onCreate(state: Bundle?) {
  super.onCreate(state)
  val prefs=androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(produceFile={java.io.File(cacheDir,"ui-poc.preferences_pb")})
  val audio=AudioPlayerManager(applicationContext, com.agentshell.data.local.PreferencesDataStore(prefs))
  val endpoint=intent.getStringExtra("endpoint").orEmpty()
  setContent { AgentShellTheme { Surface {
   var activeEndpoint by remember { mutableStateOf(endpoint) }
   var address by remember { mutableStateOf("") }
   if(activeEndpoint.isEmpty()) Column(Modifier.fillMaxSize().safeDrawingPadding().padding(10.dp)) {
    Text("Interactive UI · offline sample gallery",style=MaterialTheme.typography.titleMedium)
    OutlinedTextField(address,{address=it},label={Text("Isolated POC WebSocket URL")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    Button(onClick={if(address.startsWith("ws://") || address.startsWith("wss://"))activeEndpoint=address}) { Text("Connect test conversations") }
    var result by remember { mutableStateOf("") };Text(result)
    val docs=remember { org.json.JSONArray(assets.open("ui-poc/demo.json").bufferedReader().use { it.readText() }) }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
     for(index in 0 until docs.length()) {
      val doc=docs.getJSONObject(index)
      MessageBubble(message=ChatMessage(id="demo-$index",type="assistant",timestamp=0,blocks=listOf(ChatBlock(type="ui_widget",id="demo-$index",title=doc.getString("title"),html=doc.getString("html")))),audioPlayerManager=audio,onWidgetAction={_,text->result="Preview action: $text"})
     }
    }
   } else Poc(activeEndpoint,audio)
  } } }
 }
}
@Composable private fun Poc(endpoint:String,audio:AudioPlayerManager) {
 val socket=remember { SessionSocket(OkHttpClient()) }
 var session by remember { mutableStateOf("ui-A") }
 var binding by remember { mutableStateOf<ConversationBinding?>(null) }
 val messages=remember { mutableStateListOf<ChatMessage>() }
 var status by remember { mutableStateOf("Connecting") }
 LaunchedEffect(socket) {
  socket.messages.collect { message ->
   when(message["type"]) {
    "chat-binding" -> if(message["sessionName"]==session) {
     binding=ChatBindingState.parse(message)?.binding
     status="Linked $session · ${binding?.conversationId?.take(8).orEmpty()}"
    }
    "chat-history" -> if(message["sessionName"]==session && message["bindingId"]==binding?.id) {
     messages.clear()
     (message["messages"] as? List<*>)?.forEach { raw -> (raw as? Map<String,Any?>)?.let { messages.add(ChatMessageParser.parse(it)) } }
    }
    "chat-event" -> if(message["sessionName"]==session && message["bindingId"]==binding?.id) {
     (message["message"] as? Map<String,Any?>)?.let { messages.add(ChatMessageParser.parse(it)) }
    }
    "chat-send-result" -> status=if(message["success"]==true) "Action sent to $session" else "Rejected: ${message["error"]}"
   }
  }
 }
 LaunchedEffect(endpoint) { socket.connect(endpoint) }
 val connected by socket.isConnected.collectAsState()
 LaunchedEffect(connected,session) {
  if(connected) socket.send(mapOf("type" to "watch-chat-log","sessionName" to session,"windowIndex" to 0,"limit" to 100))
 }
 DisposableEffect(socket) { onDispose { socket.dispose() } }
 Column(Modifier.fillMaxSize().safeDrawingPadding().padding(10.dp)) {
  Text("AgentShell · Interactive UI POC",style=MaterialTheme.typography.titleMedium)
  Text(status,style=MaterialTheme.typography.bodySmall)
  Row { for(name in listOf("ui-A","ui-B")) TextButton(onClick={session=name;binding=null;messages.clear()}) { Text(name) } }
  Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
   for(message in messages) key(message.id) {
    MessageBubble(message=message,audioPlayerManager=audio,onWidgetAction={id,text ->
     binding?.let { linked -> socket.send(mapOf("type" to "send-bound-ui-action","sessionName" to session,"windowIndex" to 0,"bindingId" to linked.id,"widgetId" to id,"requestId" to UUID.randomUUID().toString(),"message" to text)) }
    })
   }
  }
 }
}

package com.agentshell.feature.browser

import android.content.Context
import android.app.Activity
import android.content.ContextWrapper
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentshell.core.theme.ThemeToggleButton
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

private fun Context.browserActivity(): Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> baseContext.browserActivity()
    else -> null
}

private fun browserKeyboard(view: WebView?) {
    val v=view ?: return
    if(!v.isAttachedToWindow) return
    fun showKeyboard() {
        if(!v.isAttachedToWindow) return
        v.requestFocus();v.evaluateJavascript("window.AgentShellViewer?.keyboard()") {
            v.post { (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v,InputMethodManager.SHOW_IMPLICIT) }
        }
    }
    if(v.hasWindowFocus()) {
        v.post { showKeyboard() }
        return
    }
    // Dropdown windows temporarily own focus. Wait for their dismissal instead
    // of focusing the WebView while the popup still restores the previous view.
    val observer=v.viewTreeObserver
    lateinit var focusListener: ViewTreeObserver.OnWindowFocusChangeListener
    lateinit var attachListener: View.OnAttachStateChangeListener
    fun cleanup() {
        if(observer.isAlive) observer.removeOnWindowFocusChangeListener(focusListener)
        v.removeOnAttachStateChangeListener(attachListener)
    }
    focusListener=ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if(focused) { cleanup();v.post { showKeyboard() } }
    }
    attachListener=object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { cleanup() }
    }
    observer.addOnWindowFocusChangeListener(focusListener)
    v.addOnAttachStateChangeListener(attachListener)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(onBack:()->Unit,viewModel:BrowserViewModel=hiltViewModel()) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var view by remember { mutableStateOf<WebView?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var fullscreenTools by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var transferMenu by remember { mutableStateOf(false) }
    val transferring=ui.status in listOf("sending-to-computer","switching-native")
    var confirmNative by remember { mutableStateOf<Boolean?>(null) }
    val context=LocalContext.current
    val window=remember(context){context.browserActivity()?.window}
    val keyboardVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
    fun sendToComputer() {
        transferMenu=false;fullscreenTools=false
        view?.let { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken,0) }
        viewModel.sendToComputer()
    }
    fun useOnPhone() {
        transferMenu=false;fullscreenTools=false
        if(ui.status=="on-native") confirmNative=false else viewModel.useOnPhone()
    }
    fun requestNative() { transferMenu=false;fullscreenTools=false;confirmNative=true }
    BackHandler(enabled=expanded){expanded=false;fullscreenTools=false}
    DisposableEffect(window,lifecycle,expanded) {
        val controller=window?.let{WindowCompat.getInsetsController(it,it.decorView)}
        val previousBehavior=controller?.systemBarsBehavior
        fun applyBars() {
            if(expanded) {
                controller?.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller?.hide(WindowInsetsCompat.Type.systemBars())
            } else controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        applyBars()
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME) applyBars()}
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller?.show(WindowInsetsCompat.Type.systemBars())
            previousBehavior?.let{controller?.systemBarsBehavior=it}
        }
    }
    LaunchedEffect(view,expanded,keyboardVisible,ui.status) {
        view?.evaluateJavascript("window.AgentShellViewportOptions={adaptive:true,fullscreen:$expanded,keyboardVisible:$keyboardVisible};",null)
    }
    DisposableEffect(lifecycle,viewModel) {
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) viewModel.resume()
        val observer=LifecycleEventObserver{_,event->
            if(event==Lifecycle.Event.ON_RESUME) viewModel.resume()
            if(event==Lifecycle.Event.ON_PAUSE) viewModel.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer);viewModel.pause() }
    }
    Scaffold(contentWindowInsets=WindowInsets(0,0,0,0),topBar={if(!expanded) TopAppBar(title={Column{
        Text(ui.title,style=MaterialTheme.typography.titleMedium)
        Text(ui.server,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back")}},actions={
        ThemeToggleButton()
        if(ui.canTakeControl || ui.canSendToComputer) Box {
            IconButton(onClick={transferMenu=true},enabled=!transferring){Icon(Icons.Default.Devices,"Move browser")}
            DropdownMenu(expanded=transferMenu,onDismissRequest={transferMenu=false}) {
                DropdownMenuItem(text={Text("Use on this phone")},leadingIcon={Icon(Icons.Default.PhoneAndroid,null)},
                    enabled=ui.canTakeControl && !transferring,onClick={useOnPhone()})
                DropdownMenuItem(text={Text("Send to computer")},leadingIcon={Icon(Icons.Default.Computer,null)},
                    enabled=ui.canSendToComputer && ui.status=="connected",onClick={sendToComputer()})
                if(ui.canNativeHandoff) DropdownMenuItem(text={Text("Open native Chrome on computer")},leadingIcon={Icon(Icons.Default.DesktopWindows,null)},
                    enabled=ui.status=="connected",onClick={requestNative()})
            }
        }
        IconButton(onClick={expanded=true}){Icon(Icons.Default.OpenInFull,"Expand browser")}
        IconButton(onClick={viewModel.connect()},enabled=!transferring){Icon(Icons.Default.Refresh,"Reconnect browser")}
    })}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .windowInsetsPadding(if(expanded) WindowInsets.displayCutout else WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal+WindowInsetsSides.Bottom))
            .imePadding()) {
            if(ui.status in listOf("connecting","loading","stopping","sending-to-computer","switching-native")) LinearProgressIndicator(Modifier.fillMaxWidth())
            ui.notice?.takeIf{ui.launch!=null && !expanded}?.let { Text(it,Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.bodySmall) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val launch=ui.launch
                if(launch!=null) key(launch){BrowserViewer(launch,{viewModel.connection(launch,it)},{view=it})}
                else Column(Modifier.align(Alignment.Center).padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Language,contentDescription=null,Modifier.size(48.dp),tint=MaterialTheme.colorScheme.primary)
                    Text(when(ui.status){"on-native"->"Native Chrome on your computer";"on-computer"->"Browsing on your computer";"profile-in-use"->"Chrome is already open";else->"Your computer's browser"},style=MaterialTheme.typography.titleLarge)
                    Text(ui.error ?: ui.notice ?: "Open Chrome on this server and reconnect to the same browser from your phone.",style=MaterialTheme.typography.bodyMedium)
                    if(ui.canTakeControl) Button(onClick={useOnPhone()},enabled=!transferring && ui.status !in listOf("connecting","loading","stopping")){Text("Use on this phone")}
                    else Button(onClick={viewModel.connect(start=true)},enabled=!transferring && ui.status !in listOf("connecting","loading","stopping")){Text("Start browser")}
                }
                if(expanded) Box(Modifier.align(Alignment.BottomEnd).padding(12.dp).navigationBarsPadding()) {
                    Surface(shape=RoundedCornerShape(28.dp),tonalElevation=4.dp,shadowElevation=4.dp,
                        color=MaterialTheme.colorScheme.surface.copy(alpha=.94f)) {
                        Row {
                            IconButton(onClick={fullscreenTools=true}){Icon(Icons.Default.Tune,"Fullscreen controls")}
                            IconButton(onClick={expanded=false;fullscreenTools=false}){Icon(Icons.Default.CloseFullscreen,"Exit fullscreen")}
                        }
                    }
                    DropdownMenu(expanded=fullscreenTools,onDismissRequest={fullscreenTools=false}) {
                        if(ui.canTakeControl) DropdownMenuItem(text={Text("Use on this phone")},enabled=!transferring,
                            onClick={useOnPhone()})
                        if(ui.canSendToComputer) DropdownMenuItem(text={Text("Send to computer")},enabled=ui.status=="connected",onClick={sendToComputer()})
                        if(ui.canNativeHandoff) DropdownMenuItem(text={Text("Open native Chrome on computer")},enabled=ui.status=="connected",onClick={requestNative()})
                        DropdownMenuItem(text={Text("Keyboard")},onClick={
                            fullscreenTools=false
                            browserKeyboard(view)
                        })
                        DropdownMenuItem(text={Text("Mouse pointer")},onClick={fullscreenTools=false;view?.evaluateJavascript("window.AgentShellViewer?.mouse()",null)})
                        DropdownMenuItem(text={Text("Fit browser to screen")},onClick={fullscreenTools=false;view?.evaluateJavascript("window.AgentShellViewer?.fit()",null)})
                        DropdownMenuItem(text={Text("Browser interaction options")},onClick={fullscreenTools=false;view?.evaluateJavascript("window.AgentShellViewer?.controls()",null)})
                        DropdownMenuItem(text={Text("Esc")},onClick={fullscreenTools=false;view?.evaluateJavascript("window.AgentShellViewer?.key(65307)",null)})
                    }
                }
                if(transferring) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha=.5f))
                    .pointerInput(Unit){awaitPointerEventScope{while(true){awaitPointerEvent().changes.forEach{it.consume()}}}},contentAlignment=Alignment.Center) {
                    Surface(shape=RoundedCornerShape(20.dp),tonalElevation=6.dp) {
                        Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
                            CircularProgressIndicator();Text(if(ui.status=="switching-native") "Reopening Chrome…" else "Switching to your computer…")
                        }
                    }
                }
            }
            if(ui.launch!=null && !expanded && !transferring) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={browserKeyboard(view)}){Icon(Icons.Default.Keyboard,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Keyboard")}
                    for((label,code) in listOf("Esc" to 65307,"Tab" to 65289,"Enter" to 65293)) TextButton(onClick={view?.evaluateJavascript("window.AgentShellViewer?.key($code)",null)}){Text(label)}
                    IconButton(onClick={view?.evaluateJavascript("window.AgentShellViewer?.zoom(1.25)",null)}){Icon(Icons.Default.ZoomIn,"Zoom browser view in")}
                    IconButton(onClick={view?.evaluateJavascript("window.AgentShellViewer?.fit()",null)}){Icon(Icons.Default.ZoomOut,"Fit browser to screen")}
                    IconButton(onClick={view?.evaluateJavascript("window.AgentShellViewer?.controls()",null)}){Icon(Icons.Default.Tune,"Browser interaction options")}
                    IconButton(onClick={confirmStop=true}){Icon(Icons.Default.StopCircle,"Stop remote Chrome")}
                }
            }
        }
    }
    if(confirmStop) AlertDialog(onDismissRequest={confirmStop=false},title={Text("Stop Chrome?")},
        text={Text("This closes the remote browser. Your profile is kept. To leave Chrome running, go back instead.")},
        confirmButton={TextButton(onClick={confirmStop=false;viewModel.stop()}){Text("Stop Chrome")}},dismissButton={TextButton(onClick={confirmStop=false}){Text("Keep running")}})
    confirmNative?.let { toComputer -> AlertDialog(onDismissRequest={confirmNative=null},
        title={Text(if(toComputer) "Open Chrome on the desktop?" else "Return Chrome to this phone?")},
        text={Text("Chrome will close and reopen on the other display using the same profile. Tabs are restored, but unsaved page inputs and live page state may be lost. Save your work first.")},
        confirmButton={TextButton(onClick={confirmNative=null;viewModel.moveNative(toComputer)}){Text(if(toComputer) "Open native Chrome" else "Return to phone")}},
        dismissButton={TextButton(onClick={confirmNative=null}){Text("Cancel")}}) }
}

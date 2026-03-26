package com.agentshell.feature.auth

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.model.Host
import com.agentshell.data.repository.HostRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── LoginViewModel ───────────────────────────────────────────────────────────

data class LoginUiState(
    val loginUrl: String? = null,
    val isLoading: Boolean = true,
    val isSuccess: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val hostRepository: HostRepository,
    private val dataStore: PreferencesDataStore,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val host = hostRepository.getSelectedHost().first()
            if (host != null) {
                val url = buildLoginUrl(host)
                _state.update { it.copy(loginUrl = url, isLoading = false) }
            } else {
                _state.update { it.copy(error = "No host configured", isLoading = false) }
            }
        }
    }

    fun onTokenReceived(token: String) {
        viewModelScope.launch {
            dataStore.setWebAuthToken(token)
            _state.update { it.copy(isSuccess = true) }
        }
    }

    fun onLoginError(error: String) {
        _state.update { it.copy(error = error) }
    }

    private fun buildLoginUrl(host: Host): String {
        return "${host.httpUrl}/login"
    }
}

// ─── LoginScreen ──────────────────────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onNavigateUp: () -> Unit = {},
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.isSuccess) {
        if (state.isSuccess) onLoginSuccess()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Login") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                state.error != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Text("Login Error", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(state.error!!, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onNavigateUp) { Text("Go Back") }
                    }
                }
                state.loginUrl != null -> {
                    val loginUrl = state.loginUrl!!
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                                webViewClient = object : WebViewClient() {

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean {
                                        val url = request.url.toString()
                                        // Check for auth token in URL query param ?token=...
                                        val token = request.url.getQueryParameter("token")
                                        if (token != null && token.isNotEmpty()) {
                                            viewModel.onTokenReceived(token)
                                            return true
                                        }
                                        // Check cookies after navigation
                                        val cookies = CookieManager.getInstance().getCookie(url) ?: ""
                                        extractTokenFromCookies(cookies)?.let { t ->
                                            viewModel.onTokenReceived(t)
                                            return true
                                        }
                                        return false
                                    }

                                    override fun onPageFinished(view: WebView, url: String) {
                                        super.onPageFinished(view, url)
                                        // Check cookies after page load
                                        val cookies = CookieManager.getInstance().getCookie(url) ?: ""
                                        extractTokenFromCookies(cookies)?.let { token ->
                                            viewModel.onTokenReceived(token)
                                        }
                                        // Check URL for token
                                        val uri = android.net.Uri.parse(url)
                                        uri.getQueryParameter("token")?.let { token ->
                                            if (token.isNotEmpty()) viewModel.onTokenReceived(token)
                                        }
                                    }

                                    override fun onReceivedError(
                                        view: WebView,
                                        errorCode: Int,
                                        description: String,
                                        failingUrl: String,
                                    ) {
                                        viewModel.onLoginError("WebView error $errorCode: $description")
                                    }
                                }

                                loadUrl(loginUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private fun extractTokenFromCookies(cookies: String): String? {
    if (cookies.isBlank()) return null
    return cookies.split(";").mapNotNull { cookie ->
        val parts = cookie.trim().split("=", limit = 2)
        if (parts.size == 2 && parts[0].trim().equals("token", ignoreCase = true)) {
            parts[1].trim().ifEmpty { null }
        } else null
    }.firstOrNull()
}

package com.hermes.hdfull.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hermes.hdfull.data.HdfullClient
import com.hermes.hdfull.data.SessionManager
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(session: SessionManager, onLoggedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(session.username) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Introduce tus datos de HDFull") }
    var isError by remember { mutableStateOf(false) }
    var cloudflareFallback by remember { mutableStateOf(false) }
    var fallbackHost by remember { mutableStateOf("") }

    fun doLogin() {
        if (busy) return
        if (username.isBlank() || password.isBlank()) {
            status = "Escribe tu usuario y contraseña"
            isError = true
            return
        }
        busy = true
        isError = false
        cloudflareFallback = false
        status = "Buscando servidor disponible…"
        scope.launch {
            try {
                val host = HdfullClient.findWorkingHost()
                status = "Iniciando sesión en $host…"
                when (val res = HdfullClient.login(host, username.trim(), password)) {
                    is HdfullClient.LoginResult.Success -> {
                        session.loggedIn = true
                        session.host = host
                        session.username = username.trim()
                        onLoggedIn()
                    }
                    is HdfullClient.LoginResult.CloudflareBlocked -> {
                        fallbackHost = host
                        cloudflareFallback = true
                        status = "Cloudflare pide verificación: resuélvela abajo y pulsa Continuar"
                    }
                    is HdfullClient.LoginResult.Error -> {
                        status = res.message
                        isError = true
                    }
                }
            } catch (e: Exception) {
                status = "Error: ${e.message ?: "desconocido"}"
                isError = true
            } finally {
                busy = false
            }
        }
    }

    fun continueFromWebView() {
        busy = true
        status = "Verificando sesión…"
        scope.launch {
            try {
                HdfullClient.importWebViewCookies(fallbackHost)
                if (HdfullClient.checkSession(fallbackHost)) {
                    session.loggedIn = true
                    session.host = fallbackHost
                    session.username = username.trim()
                    onLoggedIn()
                } else {
                    status = "Aún no hay sesión: inicia sesión en la web de abajo primero"
                    isError = true
                }
            } catch (e: Exception) {
                status = "Error: ${e.message ?: "desconocido"}"
                isError = true
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBlack)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        Text(
            "HDFull",
            style = MaterialTheme.typography.displaySmall,
            color = Gold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            status,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else TextGrey,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))

        if (!cloudflareFallback) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; isError = false },
                label = { Text("Usuario") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                colors = loginFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; isError = false },
                label = { Text("Contraseña") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { doLogin() }),
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(
                            if (passwordVisible) "Ocultar" else "Ver",
                            color = Gold,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                colors = loginFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { doLogin() },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Gold,
                    contentColor = BgBlack,
                    disabledContainerColor = Gold.copy(alpha = 0.4f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        color = BgBlack,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3.dp
                    )
                } else {
                    Text("Iniciar sesión", style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Tus credenciales solo se envían a HDFull para iniciar sesión. No se guardan en el chat ni se comparten.",
                style = MaterialTheme.typography.bodySmall,
                color = TextGrey,
                textAlign = TextAlign.Center
            )
        } else {
            // Fallback: solo si Cloudflare bloquea el login directo
            CloudflareFallbackView(
                host = fallbackHost,
                busy = busy,
                onContinue = { continueFromWebView() },
                onRetryNative = {
                    cloudflareFallback = false
                    status = "Introduce tus datos de HDFull"
                }
            )
        }
    }
}

@Composable
private fun loginFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Gold,
    unfocusedBorderColor = TextGrey.copy(alpha = 0.4f),
    focusedLabelColor = Gold,
    unfocusedLabelColor = TextGrey,
    focusedTextColor = TextWhite,
    unfocusedTextColor = TextWhite,
    cursorColor = Gold
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CloudflareFallbackView(
    host: String,
    busy: Boolean,
    onContinue: () -> Unit,
    onRetryNative: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Verificación de seguridad",
            style = MaterialTheme.typography.titleMedium,
            color = Gold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Resuelve el control de Cloudflare en la vista de abajo y pulsa Continuar.",
            style = MaterialTheme.typography.bodySmall,
            color = TextGrey
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(BgCard)
        ) {
            AndroidView(
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = HdfullClient.UA
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = WebViewClient()
                        loadUrl(host + "login")
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onContinue,
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = BgBlack),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(color = BgBlack, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
            } else {
                Text("Continuar", style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRetryNative) {
            Text("Volver al login normal", color = TextGrey)
        }
    }
}

package com.montauk.voicecapture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.montauk.voicecapture.BuildConfig
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.auth.DeviceFlowPhase
import com.montauk.voicecapture.auth.GitHubAccountClient
import com.montauk.voicecapture.auth.GitHubAccountError
import com.montauk.voicecapture.auth.GitHubDeviceFlowClient
import com.montauk.voicecapture.auth.GitHubIdentity
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import kotlinx.coroutines.launch

/** What the login screen is showing right now -- one flow at a time. */
private sealed class LoginUiState {
    data object Initial : LoginUiState()
    data class DeviceCode(val userCode: String, val verificationUri: String) : LoginUiState()
    data class DeviceError(val message: String) : LoginUiState()
    data object TokenEntry : LoginUiState()
    data object TokenValidating : LoginUiState()
    data class TokenError(val message: String) : LoginUiState()
}

/**
 * Stock login pattern (design frame 1a): centered mark, app name, a primary
 * GitHub device-flow button and a secondary access-token button, version-only
 * footer. Replaces wave-3's [com.montauk.voicecapture.VoiceCaptureApp]-restoring
 * placeholder entirely -- this is a real sign-in, not a stand-in.
 *
 * [onSignedIn] fires once a token is stored; [AppNavHost] decides whether
 * that lands on the setup wizard (first sign-in) or straight to Sessions
 * (returning user).
 */
@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as VoiceCaptureApp
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf<LoginUiState>(LoginUiState.Initial) }

    fun completeSignIn(token: String, identity: GitHubIdentity?) {
        app.secretsStore.userGithubToken = token
        app.secretsStore.userGithubLogin = identity?.login
        app.secretsStore.isSignedOut = false
        app.refreshBundleUploader()
        onSignedIn()
    }

    fun startDeviceFlow() {
        uiState = LoginUiState.Initial
        scope.launch {
            val client = GitHubDeviceFlowClient(BuildConfig.GITHUB_OAUTH_CLIENT_ID)
            client.runDeviceFlow { phase ->
                when (phase) {
                    is DeviceFlowPhase.AwaitingUser ->
                        uiState = LoginUiState.DeviceCode(phase.userCode, phase.verificationUri)
                    is DeviceFlowPhase.Success -> {
                        val identity = GitHubAccountClient().validateToken(phase.accessToken).getOrNull()
                        completeSignIn(phase.accessToken, identity)
                    }
                    DeviceFlowPhase.Expired -> uiState = LoginUiState.DeviceError("Code expired")
                    DeviceFlowPhase.Denied -> uiState = LoginUiState.DeviceError("Sign-in declined")
                    is DeviceFlowPhase.Error -> uiState = LoginUiState.DeviceError(phase.message)
                    DeviceFlowPhase.Idle -> Unit
                }
            }
        }
    }

    fun submitToken(token: String) {
        uiState = LoginUiState.TokenValidating
        scope.launch {
            GitHubAccountClient().validateToken(token).fold(
                onSuccess = { identity -> completeSignIn(token, identity) },
                onFailure = { e -> uiState = LoginUiState.TokenError(tokenErrorMessage(e)) },
            )
        }
    }

    VoiceCaptureTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.weight(1f))
                AppMark()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "voice-notes",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(40.dp))
                when (val state = uiState) {
                    LoginUiState.Initial -> InitialButtons(
                        onGithubTapped = ::startDeviceFlow,
                        onTokenTapped = { uiState = LoginUiState.TokenEntry },
                    )
                    is LoginUiState.DeviceCode -> DeviceCodeBlock(
                        userCode = state.userCode,
                        verificationUri = state.verificationUri,
                        onCancel = { uiState = LoginUiState.Initial },
                    )
                    is LoginUiState.DeviceError -> RetryableError(
                        message = state.message,
                        onRetry = ::startDeviceFlow,
                        onCancel = { uiState = LoginUiState.Initial },
                    )
                    LoginUiState.TokenEntry -> TokenEntryBlock(
                        errorMessage = null,
                        onSubmit = ::submitToken,
                        onCancel = { uiState = LoginUiState.Initial },
                    )
                    LoginUiState.TokenValidating -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    is LoginUiState.TokenError -> TokenEntryBlock(
                        errorMessage = state.message,
                        onSubmit = ::submitToken,
                        onCancel = { uiState = LoginUiState.Initial },
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "v${app.appVersionName()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppMark() {
    Box(
        modifier = Modifier
            .size(88.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(44.dp),
        )
    }
}

@Composable
private fun InitialButtons(onGithubTapped: () -> Unit, onTokenTapped: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(onClick = onGithubTapped, modifier = Modifier.fillMaxWidth()) {
            Text("Sign in with GitHub")
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onTokenTapped, modifier = Modifier.fillMaxWidth()) {
            Text("Use an access token")
        }
    }
}

/** user_code large, verification URL beneath it, per the device-flow UX GitHub itself uses. */
@Composable
private fun DeviceCodeBlock(userCode: String, verificationUri: String, onCancel: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StatusChip(label = "WAITING", color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = userCode,
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = verificationUri.ifBlank { "github.com/login/device" },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun RetryableError(message: String, onRetry: () -> Unit, onCancel: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Try again") }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun TokenEntryBlock(errorMessage: String?, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var token by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Personal access token") },
            singleLine = true,
            isError = errorMessage != null,
            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            trailingIcon = {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (revealed) "Hide token" else "Show token",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = errorMessage, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { onSubmit(token.trim()) },
            enabled = token.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continue") }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

private fun tokenErrorMessage(e: Throwable): String = when (e) {
    is GitHubAccountError.InvalidToken -> "That token didn't work"
    else -> "Couldn't reach GitHub"
}

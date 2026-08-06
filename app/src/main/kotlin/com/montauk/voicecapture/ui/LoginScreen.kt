package com.montauk.voicecapture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import com.montauk.voicecapture.auth.deviceFlowUserMessage
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import com.montauk.voicecapture.upload.UploadWorker
import kotlinx.coroutines.launch

/** What the login screen is showing right now -- one flow at a time. */
private sealed class LoginUiState {
    data object Initial : LoginUiState()
    data class DeviceCode(val userCode: String, val verificationUri: String) : LoginUiState()
    data class DeviceError(val message: String) : LoginUiState()
    data object TokenEntry : LoginUiState()
    data object TokenValidating : LoginUiState()
    data class TokenError(val message: String) : LoginUiState()

    /**
     * Bead vn-edu.30: the token passed [GitHubAccountClient.validateForVault]
     * (it's valid, and it can reach the vault repo) but its `/user` response
     * carried an `X-OAuth-Scopes` header, meaning it's a classic PAT or OAuth
     * token rather than the documented fine-grained-PAT path -- a gentle,
     * skippable warning, not a hard block, since the token does work.
     */
    data class TokenOverScoped(val token: String, val identity: GitHubIdentity) : LoginUiState()
}

/**
 * Stock login pattern (design frame 1a): centered mark, app name, a primary
 * GitHub device-flow button and a secondary access-token button, version-only
 * footer. Bead vn-edu.29: this screen is no longer an entry gate -- it's the
 * optional *connect* flow, reached from Settings' "Connect GitHub" or the
 * bottom nav's "Sign In" tab, never shown unprompted on launch (see
 * [AppEntryGating.startDestination]).
 *
 * [onSignedIn] fires once a token is stored (and any LOCAL upload backlog
 * has been drained, see [completeSignIn]); [AppNavHost] decides whether that
 * lands on the setup wizard (first-ever connect) or straight back to
 * Sessions (repeat connect), via [AppEntryGating.postLoginDestination].
 *
 * [accountClient] defaults to the real [GitHubAccountClient]; overridable
 * purely for test visibility -- same convention as [IntelligenceStep]'s
 * `assemblyValidator`/`anthropicValidator` -- so `LoginScreenTest` can point
 * it at a [okhttp3.mockwebserver.MockWebServer] instead of the real
 * `api.github.com`.
 */
@Composable
fun LoginScreen(onSignedIn: () -> Unit, accountClient: GitHubAccountClient = GitHubAccountClient()) {
    val context = LocalContext.current
    val app = context.applicationContext as VoiceCaptureApp
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf<LoginUiState>(LoginUiState.Initial) }
    // Bead vn-edu.30: the repo a submitted PAT is validated against -- the
    // wizard's vault picker can have already pointed this at a non-default
    // repo on a repeat connect; falls back to the BuildConfig default on a
    // first-ever connect, same precedence as SettingsScreen/VoiceCaptureApp.
    val vaultOwner = app.secretsStore.selectedVaultOwner ?: BuildConfig.VAULT_OWNER
    val vaultRepo = app.secretsStore.selectedVaultRepo ?: BuildConfig.VAULT_REPO

    fun completeSignIn(token: String, identity: GitHubIdentity?) {
        app.secretsStore.userGithubToken = token
        app.secretsStore.userGithubLogin = identity?.login
        app.secretsStore.isSignedOut = false
        app.refreshBundleUploader()
        // Bead vn-edu.29: connecting is no longer the only way sessions exist --
        // there can already be a backlog of LOCAL sessions recorded signed out.
        // Drain it now that there's a real uploader to hand them to.
        UploadWorker.enqueueBacklog(context, app.sessionStore)
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
                        val identity = accountClient.validateToken(phase.accessToken).getOrNull()
                        completeSignIn(phase.accessToken, identity)
                    }
                    DeviceFlowPhase.Idle -> Unit
                    DeviceFlowPhase.Expired, DeviceFlowPhase.Denied, is DeviceFlowPhase.Error ->
                        uiState = LoginUiState.DeviceError(deviceFlowUserMessage(phase))
                }
            }
        }
    }

    /**
     * Bead vn-edu.30: validates against the configured vault repo, not just
     * `/user` -- a token that can't reach [vaultOwner]/[vaultRepo] is
     * rejected here (never persisted, never calls [completeSignIn]) instead
     * of silently "signing in" and only failing on the first upload attempt.
     * A token that passes but is over-scoped (see [GitHubIdentity.isOverScoped])
     * doesn't persist immediately either -- it routes to
     * [LoginUiState.TokenOverScoped] for a one-tap "use anyway" confirmation.
     */
    fun submitToken(token: String) {
        uiState = LoginUiState.TokenValidating
        scope.launch {
            accountClient.validateForVault(token, vaultOwner, vaultRepo).fold(
                onSuccess = { identity ->
                    if (identity.isOverScoped) {
                        uiState = LoginUiState.TokenOverScoped(token, identity)
                    } else {
                        completeSignIn(token, identity)
                    }
                },
                onFailure = { e -> uiState = LoginUiState.TokenError(tokenErrorMessage(e, vaultOwner, vaultRepo)) },
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
                    text = "rubberduck",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(40.dp))
                // Bead vn-edu.30 follow-up: only TokenEntry/TokenError get a
                // bounded (weight(1f)) + scrollable slot -- TokenEntryBlock's
                // minting guidance made that specific content tall enough to
                // overflow a small/old device's screen, silently collapsing
                // the Continue/Cancel row to zero height instead of just
                // scrolling. Every other state stays an unweighted direct
                // child exactly as before this bead: wrapping *all* states in
                // a shared weight(1f) Box re-centered Initial's (and every
                // other short state's) content within its own bounded slot
                // instead of sitting flush below the title as it always had,
                // which is what broke the `login` Roborazzi golden on CI --
                // that state must render byte-identical to pre-vn-edu.30.
                when (val state = uiState) {
                    LoginUiState.Initial -> InitialButtons(
                        githubOAuthConfigured = app.isGithubOAuthConfigured(),
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
                    LoginUiState.TokenEntry -> Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        TokenEntryBlock(
                            vaultOwner = vaultOwner,
                            vaultRepo = vaultRepo,
                            errorMessage = null,
                            onSubmit = ::submitToken,
                            onCancel = { uiState = LoginUiState.Initial },
                        )
                    }
                    LoginUiState.TokenValidating -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    is LoginUiState.TokenError -> Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        TokenEntryBlock(
                            vaultOwner = vaultOwner,
                            vaultRepo = vaultRepo,
                            errorMessage = state.message,
                            onSubmit = ::submitToken,
                            onCancel = { uiState = LoginUiState.Initial },
                        )
                    }
                    is LoginUiState.TokenOverScoped -> OverScopedWarningBlock(
                        onUseAnyway = { completeSignIn(state.token, state.identity) },
                        onEnterDifferentToken = { uiState = LoginUiState.TokenEntry },
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

/**
 * When no GitHub OAuth App is registered ([VoiceCaptureApp.isGithubOAuthConfigured]
 * false), the device-flow request always 404s before a user code ever shows
 * up -- there's no working button to offer. Promote "Use an access token"
 * (the path that does work) to primary and demote GitHub sign-in to a
 * disabled, self-explanatory "soon" button instead of leading with a dead end.
 */
@Composable
private fun InitialButtons(
    githubOAuthConfigured: Boolean,
    onGithubTapped: () -> Unit,
    onTokenTapped: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (githubOAuthConfigured) {
            Button(onClick = onGithubTapped, modifier = Modifier.fillMaxWidth()) {
                Text("Sign in with GitHub")
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onTokenTapped, modifier = Modifier.fillMaxWidth()) {
                Text("Use an access token")
            }
        } else {
            Button(onClick = onTokenTapped, modifier = Modifier.fillMaxWidth()) {
                Text("Use an access token")
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("Sign in with GitHub (soon)")
            }
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

/**
 * Bead vn-edu.30: names the feature ("Vault uploads"), not the vendor
 * mechanics, per the app-wide copy rule (`PRD.md`'s "the app never narrates
 * its own behavior") -- but a token *is* something the user has to go mint
 * themselves outside the app, so the exact minting steps are spelled out
 * here rather than left to a README only they might not read. Steers toward
 * a fine-grained PAT scoped to just [vaultOwner]/[vaultRepo]'s Contents
 * permission -- the only token shape [GitHubAccountClient.validateForVault]
 * can't flag as over-scoped (see [LoginUiState.TokenOverScoped]).
 */
@Composable
private fun TokenEntryBlock(vaultOwner: String, vaultRepo: String, errorMessage: String?, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var token by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    // Bead vn-edu.30: the minting guidance above the field can be taller than
    // the bounded slot this renders in on a small/old device -- scrolls
    // rather than overflowing (see the weight(1f) Box in LoginScreen).
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Vault uploads need a GitHub token scoped to just this vault.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "On GitHub: Settings -> Developer settings -> Fine-grained tokens -> " +
                "Generate new token. Resource owner: $vaultOwner. Repository access: Only " +
                "select repositories -> $vaultRepo. Permissions -> Repository permissions -> " +
                "Contents: Read and write.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Avoid a classic token -- it reaches every repo you own, not just this one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
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

/**
 * Bead vn-edu.30: shown when a submitted token works (passed [GitHubAccountClient.validateForVault])
 * but its `/user` response carried an `X-OAuth-Scopes` header -- a classic
 * PAT or OAuth token, broader than the fine-grained PAT [TokenEntryBlock]
 * steers toward. Deliberately not a hard block: the token does work, so
 * "use anyway" completes sign-in with it exactly as if this screen weren't
 * here.
 */
@Composable
private fun OverScopedWarningBlock(onUseAnyway: () -> Unit, onEnterDifferentToken: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        StatusChip(label = "BROADER ACCESS THAN NEEDED", color = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "This token can reach more than just this vault. Vault uploads only need " +
                "a fine-grained token scoped to this one repo -- consider minting one of those instead.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onUseAnyway, modifier = Modifier.fillMaxWidth()) { Text("Use anyway") }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onEnterDifferentToken, modifier = Modifier.fillMaxWidth()) { Text("Enter a different token") }
    }
}

private fun tokenErrorMessage(e: Throwable, vaultOwner: String, vaultRepo: String): String = when (e) {
    is GitHubAccountError.InvalidToken -> "That token didn't work"
    is GitHubAccountError.RepoNotAccessible -> "That token can't reach $vaultOwner/$vaultRepo"
    else -> "Couldn't reach GitHub"
}

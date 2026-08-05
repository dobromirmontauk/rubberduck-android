package com.montauk.voicecapture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.montauk.voicecapture.BuildConfig
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.llm.AnthropicKeyError
import com.montauk.voicecapture.llm.AnthropicKeyValidator
import com.montauk.voicecapture.stt.AssemblyAiKeyError
import com.montauk.voicecapture.stt.AssemblyAiKeyValidator
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import kotlinx.coroutines.launch

/** Stable content-description/test-tag anchors so a deep link (e.g. the recording screen's keyless tags message, bead vn-edu.46) can be asserted against. */
const val ANTHROPIC_KEY_ROW_TEST_TAG = "settings_anthropic_key_row"

@Composable
fun SettingsScreen(onRunSetupAgain: () -> Unit, onConnectGithub: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as VoiceCaptureApp
    val vaultOwner = app.secretsStore.selectedVaultOwner ?: BuildConfig.VAULT_OWNER
    val vaultRepo = app.secretsStore.selectedVaultRepo ?: BuildConfig.VAULT_REPO
    val connected = app.secretsStore.isConnectedToGithub()

    VoiceCaptureTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(20.dp))
                SettingsSection {
                    InfoRow(label = "GitHub", value = if (connected) app.secretsStore.userGithubLogin ?: "Connected" else "Not connected")
                    // Bead vn-edu.29: GitHub is an optional, later step now, not
                    // something a fresh install already asked about -- this is the
                    // one place to start that flow when signed out (the bottom
                    // nav's "Sign In" tab is the other).
                    if (!connected) {
                        TextButton(onClick = onConnectGithub) { Text("Connect GitHub") }
                    }
                    InfoRow(label = "Vault repo", value = "$vaultOwner/$vaultRepo")
                    InfoRow(label = "Upload token", value = if (app.isGithubTokenConfigured()) "Configured" else "Not configured")
                    InfoRow(label = "App version", value = app.appVersionName())
                }
                Spacer(modifier = Modifier.height(20.dp))
                // Bead vn-edu.48: in-app key management -- each row shows the
                // runtime-entered key's state (masked, last-4 visible), a reveal
                // toggle, and a Replace flow that validates before storing. The
                // BuildConfig/local.properties dev fallback isn't reflected here
                // deliberately -- it's a dev-build convenience this UI doesn't
                // manage (see AppSecretsStore.effective*() KDoc).
                SettingsSection {
                    ApiKeyManagementRow(
                        label = "Live transcription",
                        initialValue = app.secretsStore.userAssemblyAiKey,
                        validate = { key -> AssemblyAiKeyValidator().validate(key) },
                        errorMessageFor = { e ->
                            if (e is AssemblyAiKeyError.Invalid) "That key didn't work" else "Couldn't reach AssemblyAI"
                        },
                        onSave = { key -> app.secretsStore.userAssemblyAiKey = key },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ApiKeyManagementRow(
                        label = "Word cloud & titles",
                        initialValue = app.secretsStore.userAnthropicKey,
                        validate = { key -> AnthropicKeyValidator().validate(key) },
                        errorMessageFor = { e ->
                            if (e is AnthropicKeyError.Invalid) "That key didn't work" else "Couldn't reach Anthropic"
                        },
                        onSave = { key -> app.secretsStore.userAnthropicKey = key },
                        modifier = Modifier.testTag(ANTHROPIC_KEY_ROW_TEST_TAG),
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                SettingsSection {
                    ToggleRow(label = "Upload on Wi-Fi only", initiallyOn = false)
                    ToggleRow(label = "Keep screen on while recording", initiallyOn = true)
                }
                Spacer(modifier = Modifier.height(20.dp))
                SettingsSection {
                    TextButton(onClick = onRunSetupAgain) { Text("Run setup again") }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Stub toggle -- doesn't yet wire to any real behavior; fine for this wave per the brief. */
@Composable
private fun ToggleRow(label: String, initiallyOn: Boolean) {
    var checked by remember { mutableStateOf(initiallyOn) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = checked, onCheckedChange = { checked = it })
    }
}

/**
 * Bead vn-edu.48: one Settings key row -- [label] names the feature the key
 * powers (per the copy rules), never the vendor. Default (not-editing) state
 * shows [maskedLast4] of the currently-stored value with a reveal toggle, or
 * "Not configured" with an "Add" affordance when there's nothing stored yet;
 * tapping Replace/Add opens a [MaskedKeyField] input. Save calls [validate]
 * (each caller's own test-connection check -- [AssemblyAiKeyValidator] or
 * [AnthropicKeyValidator]) before ever calling [onSave]/
 * [com.montauk.voicecapture.settings.AppSecretsStore], so a bad paste is
 * never silently accepted as "configured." Owns its own
 * local copy of the stored value ([storedValue]) so the row's display
 * updates immediately on a successful save without requiring the parent
 * [SettingsScreen] composable to be driven by observable secrets-store
 * state (a plain `SharedPreferences`-backed store isn't Compose-observable).
 *
 * `internal` (not `private`) purely for test visibility -- same convention as
 * [com.montauk.voicecapture.tags.TagTracker.trackedCandidateCount] -- so
 * [ApiKeyManagementRowTest] can drive it directly with fake `validate`/
 * `onSave` lambdas instead of hitting the real AssemblyAI/Anthropic APIs
 * that [SettingsScreen] wires in production.
 */
@Composable
internal fun ApiKeyManagementRow(
    label: String,
    initialValue: String?,
    validate: suspend (String) -> Result<Unit>,
    errorMessageFor: (Throwable) -> String,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var storedValue by remember { mutableStateOf(initialValue) }
    var editing by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var validating by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val current = storedValue
                Text(
                    text = when {
                        current.isNullOrBlank() -> "Not configured"
                        revealed -> current
                        else -> maskedLast4(current)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!current.isNullOrBlank()) {
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(
                            imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (revealed) "Hide $label key" else "Show $label key",
                        )
                    }
                }
                if (!editing) {
                    TextButton(onClick = { editing = true; input = ""; errorMessage = null; revealed = false }) {
                        Text(if (current.isNullOrBlank()) "Add" else "Replace")
                    }
                }
            }
        }
        if (editing) {
            MaskedKeyField(
                value = input,
                onValueChange = { input = it; errorMessage = null },
                label = label,
                errorMessage = errorMessage,
                enabled = !validating,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { editing = false; errorMessage = null; input = "" }, enabled = !validating) { Text("Cancel") }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    enabled = input.isNotBlank() && !validating,
                    onClick = {
                        validating = true
                        scope.launch {
                            val trimmed = input.trim()
                            val result = validate(trimmed)
                            validating = false
                            result.fold(
                                onSuccess = {
                                    onSave(trimmed)
                                    storedValue = trimmed
                                    editing = false
                                    input = ""
                                },
                                onFailure = { e -> errorMessage = errorMessageFor(e) },
                            )
                        }
                    },
                ) { Text(if (validating) "Checking" else "Save") }
            }
        }
    }
}

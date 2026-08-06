package com.montauk.voicecapture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.montauk.voicecapture.BuildConfig
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.llm.AnthropicKeyError
import com.montauk.voicecapture.llm.AnthropicKeyValidator
import com.montauk.voicecapture.settings.CredentialDisplayState
import com.montauk.voicecapture.settings.credentialDisplayState
import com.montauk.voicecapture.stt.AssemblyAiKeyError
import com.montauk.voicecapture.stt.AssemblyAiKeyValidator
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import kotlinx.coroutines.launch

/** Stable content-description/test-tag anchors so a deep link (e.g. the recording screen's keyless tags message, bead vn-edu.46) can be asserted against. */
const val ANTHROPIC_KEY_ROW_TEST_TAG = "settings_anthropic_key_row"

/** Same purpose as [ANTHROPIC_KEY_ROW_TEST_TAG], for the recording screen's keyless transcript-pane message (bead vn-edu.66). */
const val ASSEMBLYAI_KEY_ROW_TEST_TAG = "settings_assemblyai_key_row"

@Composable
fun SettingsScreen(onRunSetupAgain: () -> Unit, onConnectGithub: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as VoiceCaptureApp
    val vaultOwner = app.secretsStore.selectedVaultOwner ?: BuildConfig.VAULT_OWNER
    val vaultRepo = app.secretsStore.selectedVaultRepo ?: BuildConfig.VAULT_REPO
    val connected = app.secretsStore.isConnectedToGithub()
    // Bead vn-edu.52: "connected" already is the runtime-token/signed-in
    // check (AppSecretsStore.isConnectedToGithub), so it doubles as
    // credentialDisplayState's hasRuntimeValue here -- isGithubTokenConfigured()
    // (the effective/BuildConfig-inclusive check) supplies hasDevFallback.
    val uploadTokenState = credentialDisplayState(hasRuntimeValue = connected, hasDevFallback = app.isGithubTokenConfigured())
    val assemblyKeyState = credentialDisplayState(
        hasRuntimeValue = !app.secretsStore.userAssemblyAiKey.isNullOrBlank(),
        hasDevFallback = app.isAssemblyKeyConfigured(),
    )
    val anthropicKeyState = credentialDisplayState(
        hasRuntimeValue = !app.secretsStore.userAnthropicKey.isNullOrBlank(),
        hasDevFallback = app.isAnthropicKeyConfigured(),
    )

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
                    // Bead vn-edu.29: GitHub is an optional, later step now, not
                    // something a fresh install already asked about -- this is the
                    // one place to start that flow when signed out (the bottom
                    // nav's "Sign In" tab is the other).
                    GithubConnectionRow(
                        connected = connected,
                        githubLogin = app.secretsStore.userGithubLogin,
                        // Only worth a notice when signed out -- a connected
                        // account's own token is already what's uploading.
                        showDevFallbackNotice = !connected && uploadTokenState == CredentialDisplayState.DEV_FALLBACK,
                        onConnectGithub = onConnectGithub,
                    )
                    InfoRow(label = "Vault repo", value = "$vaultOwner/$vaultRepo")
                    UploadTokenRow(state = uploadTokenState)
                    InfoRow(label = "App version", value = app.appVersionName())
                }
                Spacer(modifier = Modifier.height(20.dp))
                // Bead vn-edu.48: in-app key management -- each row shows the
                // runtime-entered key's state (masked, last-4 visible), a reveal
                // toggle, and a Replace flow that validates before storing.
                // Bead vn-edu.52: when there's no runtime key but the
                // BuildConfig/local.properties dev fallback is non-blank, the
                // row shows a distinct "Using build-time key (dev)" state
                // instead of misreporting "Not configured" -- see
                // devFallbackActive's KDoc on ApiKeyManagementRow.
                SettingsSection {
                    ApiKeyManagementRow(
                        label = "Live transcription",
                        initialValue = app.secretsStore.userAssemblyAiKey,
                        validate = { key -> AssemblyAiKeyValidator().validate(key) },
                        errorMessageFor = { e ->
                            if (e is AssemblyAiKeyError.Invalid) "That key didn't work" else "Couldn't reach AssemblyAI"
                        },
                        onSave = { key -> app.secretsStore.userAssemblyAiKey = key },
                        modifier = Modifier.testTag(ASSEMBLYAI_KEY_ROW_TEST_TAG),
                        devFallbackActive = assemblyKeyState == CredentialDisplayState.DEV_FALLBACK,
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
                        devFallbackActive = anthropicKeyState == CredentialDisplayState.DEV_FALLBACK,
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                SettingsSection {
                    AutoPauseSection(
                        enabled = app.secretsStore.autoPauseEnabled,
                        onEnabledChange = { app.secretsStore.autoPauseEnabled = it },
                        thresholdMs = app.secretsStore.autoPauseSilenceThresholdMs,
                        onThresholdChange = { app.secretsStore.autoPauseSilenceThresholdMs = it },
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

/**
 * Bead vn-edu.52: the OAuth sign-in row itself keeps its existing semantics
 * ("Not connected" means "not signed in", full stop) -- but a dev/build
 * token can be actively uploading while signed out, and a bare "Not
 * connected" reads as "uploads impossible." [showDevFallbackNotice] adds a
 * short secondary line for exactly that case. `internal` for
 * [ApiKeyManagementRowTest]-style direct test visibility -- see
 * [ApiKeyManagementRow]'s KDoc for the convention.
 */
@Composable
internal fun GithubConnectionRow(
    connected: Boolean,
    githubLogin: String?,
    showDevFallbackNotice: Boolean,
    onConnectGithub: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        InfoRow(label = "GitHub", value = if (connected) githubLogin ?: "Connected" else "Not connected")
        if (!connected) {
            TextButton(onClick = onConnectGithub) { Text("Connect GitHub") }
            if (showDevFallbackNotice) {
                Text(
                    text = "Uploads active via build-time token (dev)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Bead vn-edu.52: distinguishes a user-entered token ("Configured (in-app)")
 * from a dev build's BuildConfig/`local.properties` fallback ("Using
 * build-time token (dev)") from neither ("Not configured") -- see
 * [CredentialDisplayState]. `internal` for test visibility, same convention
 * as [ApiKeyManagementRow].
 */
@Composable
internal fun UploadTokenRow(state: CredentialDisplayState) {
    val value = when (state) {
        CredentialDisplayState.CONFIGURED -> "Configured (in-app)"
        CredentialDisplayState.DEV_FALLBACK -> "Using build-time token (dev)"
        CredentialDisplayState.NOT_CONFIGURED -> "Not configured"
    }
    InfoRow(label = "Upload token", value = value)
}

/**
 * Bead asn-r60: auto-pause on/off + its silence threshold, both wired to
 * [com.montauk.voicecapture.settings.AppSecretsStore] (real persistence,
 * unlike [ToggleRow]'s stubs below). Owns local `remember`ed copies of both
 * values -- same reason as [ApiKeyManagementRow]'s `storedValue`: a plain
 * `SharedPreferences`-backed store isn't Compose-observable, so this row
 * needs its own state to reflect a change immediately rather than waiting on
 * a recomposition trigger that will never come. `internal` for
 * [AutoPauseSectionTest]-style direct test visibility, same convention as
 * [ApiKeyManagementRow]. The threshold picker only shows while [enabled] --
 * a disabled threshold has nothing to tune.
 */
@Composable
internal fun AutoPauseSection(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    thresholdMs: Long,
    onThresholdChange: (Long) -> Unit,
) {
    var isEnabled by remember { mutableStateOf(enabled) }
    var selectedThresholdMs by remember { mutableStateOf(thresholdMs) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Auto-pause when quiet",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(
                checked = isEnabled,
                onCheckedChange = {
                    isEnabled = it
                    onEnabledChange(it)
                },
                modifier = Modifier.testTag(AUTO_PAUSE_TOGGLE_TEST_TAG),
            )
        }
        if (isEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AUTO_PAUSE_THRESHOLD_OPTIONS_MS.forEach { optionMs ->
                    AutoPauseThresholdChip(
                        label = "${optionMs / 1000}s",
                        selected = optionMs == selectedThresholdMs,
                        onClick = {
                            selectedThresholdMs = optionMs
                            onThresholdChange(optionMs)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoPauseThresholdChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val backgroundColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = contentColor, fontWeight = FontWeight.Medium)
    }
}

/**
 * The silence-threshold choices offered in Settings -- 10s matches
 * [com.montauk.voicecapture.settings.AppSecretsStore.DEFAULT_AUTO_PAUSE_THRESHOLD_MS]
 * (bead asn-o63 re-anchored the default down from 30s after live testing;
 * see that field's KDoc for the fill-window split this now implies). Every
 * option here is `>= AppSecretsStore.AUTO_PAUSE_FILL_DURATION_MS` so the
 * leading invisible span is never negative.
 */
internal val AUTO_PAUSE_THRESHOLD_OPTIONS_MS = listOf(10_000L, 15_000L, 30_000L, 60_000L)

/** Test-only anchor for [AutoPauseSection]'s toggle (bead asn-r60). */
const val AUTO_PAUSE_TOGGLE_TEST_TAG = "settings_auto_pause_toggle"

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
 *
 * [devFallbackActive] (bead vn-edu.52) only changes the not-configured-yet
 * display: when there's no [initialValue] but the caller's BuildConfig/
 * `local.properties` dev fallback is non-blank, the row shows "Using
 * build-time key (dev)" instead of "Not configured", with the same "Add"
 * affordance -- adding a runtime key still replaces the dev fallback (bead
 * vn-edu.48 precedence, unchanged here). Once [initialValue] is non-blank,
 * [devFallbackActive] no longer matters -- the masked/revealed value always
 * wins.
 */
@Composable
internal fun ApiKeyManagementRow(
    label: String,
    initialValue: String?,
    validate: suspend (String) -> Result<Unit>,
    errorMessageFor: (Throwable) -> String,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
    devFallbackActive: Boolean = false,
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
                        current.isNullOrBlank() && devFallbackActive -> "Using build-time key (dev)"
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

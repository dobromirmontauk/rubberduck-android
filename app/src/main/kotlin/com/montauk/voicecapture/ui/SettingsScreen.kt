package com.montauk.voicecapture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.montauk.voicecapture.BuildConfig
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme

@Composable
fun SettingsScreen(onRunSetupAgain: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as VoiceCaptureApp
    val vaultOwner = app.secretsStore.selectedVaultOwner ?: BuildConfig.VAULT_OWNER
    val vaultRepo = app.secretsStore.selectedVaultRepo ?: BuildConfig.VAULT_REPO

    VoiceCaptureTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(20.dp))
                SettingsSection {
                    InfoRow(label = "Signed in as", value = app.secretsStore.userGithubLogin ?: "Not signed in")
                    InfoRow(label = "Vault repo", value = "$vaultOwner/$vaultRepo")
                    InfoRow(label = "Live transcription key", value = if (app.isAssemblyKeyConfigured()) "Configured" else "Not configured")
                    InfoRow(label = "Upload token", value = if (app.isGithubTokenConfigured()) "Configured" else "Not configured")
                    InfoRow(label = "App version", value = app.appVersionName())
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

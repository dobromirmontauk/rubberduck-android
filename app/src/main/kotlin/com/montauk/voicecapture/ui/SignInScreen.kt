package com.montauk.voicecapture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme

/**
 * Minimal placeholder shown after "Log Out" (or on cold start while signed
 * out). The real login screen + setup wizard is a later wave -- for now,
 * secrets still come from `local.properties`/`BuildConfig`, and this screen
 * just explains that and offers a way back in without touching a build.
 */
@Composable
fun SignInScreen(onRestoreFromBuildConfig: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as VoiceCaptureApp

    VoiceCaptureTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Signed out",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Recording and local sessions stay on this device. " +
                        "Live transcription and vault upload are paused until you sign back in.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "A real sign-in flow is coming in a later update. For now, keys still " +
                        "come from local.properties on this build.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(28.dp))
                Button(onClick = {
                    app.secretsStore.isSignedOut = false
                    app.refreshBundleUploader()
                    onRestoreFromBuildConfig()
                }) {
                    Text("Restore from build config")
                }
            }
        }
    }
}

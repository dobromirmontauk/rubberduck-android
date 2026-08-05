package com.montauk.voicecapture.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A bundled test-fixture WAV, packaged only into debug builds under
 * `app/src/debug/assets/fixtures/` (mirrors the same audio used by
 * `AssemblyAiLiveStreamingTest` from `app/src/integrationTest/resources/`).
 * See [FixturePickerDialog] and bead vn-edu.20.
 */
data class DebugFixture(val label: String, val assetFileName: String)

val DEBUG_FIXTURES = listOf(
    DebugFixture("Kitchen remodel", "kitchen-remodel.wav"),
    DebugFixture("Marathon training", "marathon-training.wav"),
    // Long-form (~3.5-4min) multi-topic rambles for exercising topic-chip
    // drift over time (bead vn-edu.39) -- see the fixtures table in
    // README.md for each one's topic ground truth.
    DebugFixture("Dog walk download", "dog-walk-download.wav"),
    DebugFixture("Drive home", "drive-home-hiring.wav"),
)

/**
 * Opened by a long-press on the "New Session" bottom-nav tab in debug
 * builds (see [BottomNavBar]) -- lets the user start a recording session
 * fed from a bundled fixture file instead of the live mic, so live
 * transcription can be exercised on an emulator without a working
 * microphone passthrough.
 */
@Composable
fun FixturePickerDialog(onPick: (DebugFixture) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record from a test fixture") },
        text = {
            Column {
                DEBUG_FIXTURES.forEach { fixture ->
                    Text(
                        text = fixture.label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(fixture) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

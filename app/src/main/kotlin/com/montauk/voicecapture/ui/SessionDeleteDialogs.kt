package com.montauk.voicecapture.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.montauk.voicecapture.session.DeleteConfirmPolicy
import com.montauk.voicecapture.session.UploadState

/**
 * Confirm dialog for the per-session "delete from phone" action (bead
 * vn-edu.55), shared by [SessionListScreen] (long-press on a row) and
 * [SessionDetailScreen] (the Delete action). Two variants driven by
 * [DeleteConfirmPolicy.requiresHardWarning]:
 *  - UPLOADED: a plain confirm -- the vault already has a copy.
 *  - LOCAL/QUEUED: a hard warning ("Not uploaded -- this recording will be
 *    lost forever") that requires the checkbox acknowledgment before Delete
 *    becomes clickable. Never silently discards unuploaded audio.
 */
@Composable
fun DeleteSessionConfirmDialog(uploadState: UploadState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val hardWarning = DeleteConfirmPolicy.requiresHardWarning(uploadState)
    var acknowledged by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hardWarning) "Delete unsaved recording?" else "Delete this session?") },
        text = {
            Column {
                if (hardWarning) {
                    Text(
                        text = "Not uploaded — this recording will be lost forever.",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        // toggleable on the whole row (not just the Checkbox) so tapping
                        // the label also acknowledges -- a small tap target on just the
                        // checkbox is an easy miss on the exact confirm that matters most.
                        modifier = Modifier.toggleable(value = acknowledged, onValueChange = { acknowledged = it }),
                    ) {
                        Checkbox(checked = acknowledged, onCheckedChange = null)
                        Text("I understand this can't be undone")
                    }
                } else {
                    Text("This removes the recording from your phone. The uploaded copy in the vault is unaffected.")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !hardWarning || acknowledged) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Confirm dialog for the Sessions screen's bulk "Archive all integrated
 * sessions" action (bead vn-edu.55). Always the plain-confirm variant --
 * eligibility (only [com.montauk.voicecapture.session.SessionStatus.INTEGRATED]
 * sessions ever reach this dialog, see [com.montauk.voicecapture.session.BulkArchiveEligibility])
 * already guarantees every affected session has a vault copy.
 */
@Composable
fun BulkArchiveConfirmDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Archive $count integrated session${if (count == 1) "" else "s"}?") },
        text = { Text("Removes local audio and transcripts for sessions the vault has already organized. The vault copy remains the permanent record.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Archive") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

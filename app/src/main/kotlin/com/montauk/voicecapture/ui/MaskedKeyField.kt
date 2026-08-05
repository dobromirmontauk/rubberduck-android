package com.montauk.voicecapture.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Shared masked-secret input: an [OutlinedTextField] defaulting to
 * [PasswordVisualTransformation] with a show/hide trailing icon, plus an
 * inline error line when [errorMessage] is non-null. Extracted (bead
 * vn-edu.48) from the pattern the asn-cww fix introduced independently in
 * [LoginScreen]'s token field and [SetupWizardScreen]'s AssemblyAI field --
 * this is now the one place that pattern lives, reused by the setup
 * wizard's Intelligence step (both key fields) and Settings' Replace flow
 * (both key rows). [LoginScreen]'s own token field is intentionally left
 * as-is (out of scope for vn-edu.48, and touching it would perturb the
 * `login` Roborazzi golden for no behavioral reason).
 */
@Composable
fun MaskedKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    errorMessage: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = errorMessage != null,
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (revealed) "Hide key" else "Show key",
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
    if (errorMessage != null) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = errorMessage, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
    }
}

/** [key]'s last 4 characters preceded by 4 mask dots, e.g. "••••3f2a" -- never renders more of the real value than that. */
fun maskedLast4(key: String): String {
    val last4 = key.takeLast(4)
    return "••••$last4"
}

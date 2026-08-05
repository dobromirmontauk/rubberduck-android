package com.montauk.voicecapture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.auth.GitHubAccountClient
import com.montauk.voicecapture.auth.GitHubRepoOption
import com.montauk.voicecapture.stt.AssemblyAiKeyError
import com.montauk.voicecapture.stt.AssemblyAiKeyValidator
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import kotlinx.coroutines.launch

private enum class WizardStep { ACCOUNT, VAULT, TRANSCRIPTION }

/**
 * Design frame 1b: a 3-segment progress rail over ① Account (already
 * satisfied by login -- just shows the signed-in identity), ② Choose your
 * vault (repo picker with silent per-repo validation), ③ Live transcription
 * (optional AssemblyAI key, always skippable). Shown once after a user's
 * first sign-in ([AppSecretsStore.setupWizardCompleted][com.montauk.voicecapture.settings.AppSecretsStore]),
 * re-runnable from Settings. The close action and per-step Back are always
 * available -- this screen must never be the only thing standing between a
 * user and Sessions/recording.
 */
@Composable
fun SetupWizardScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as VoiceCaptureApp
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(WizardStep.ACCOUNT) }
    val snackbarHostState = remember { SnackbarHostState() }

    fun finish() {
        app.secretsStore.setupWizardCompleted = true
        app.refreshBundleUploader()
        onFinished()
    }

    VoiceCaptureTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { contentPadding ->
            Surface(modifier = Modifier.fillMaxSize().padding(contentPadding), color = MaterialTheme.colorScheme.background) {
                Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    WizardTopBar(step = step, onClose = ::finish)
                    Spacer(modifier = Modifier.height(24.dp))
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (step) {
                            WizardStep.ACCOUNT -> AccountStep(
                                login = app.secretsStore.userGithubLogin,
                                onContinue = { step = WizardStep.VAULT },
                            )
                            WizardStep.VAULT -> VaultStep(
                                token = app.secretsStore.userGithubToken.orEmpty(),
                                onBack = { step = WizardStep.ACCOUNT },
                                onCreateNewTapped = {
                                    scope.launch { snackbarHostState.showSnackbar("Coming soon") }
                                },
                                onSelected = { owner, repo ->
                                    app.secretsStore.selectedVaultOwner = owner
                                    app.secretsStore.selectedVaultRepo = repo
                                    step = WizardStep.TRANSCRIPTION
                                },
                            )
                            WizardStep.TRANSCRIPTION -> TranscriptionStep(
                                onBack = { step = WizardStep.VAULT },
                                onSkip = ::finish,
                                onKeyValidated = { key ->
                                    app.secretsStore.userAssemblyAiKey = key
                                    finish()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WizardTopBar(step: WizardStep, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        ProgressRail(current = step, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(12.dp))
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProgressRail(current: WizardStep, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        WizardStep.entries.forEach { s ->
            val filled = s.ordinal <= current.ordinal
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(
                        color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

@Composable
private fun AccountStep(login: String?, onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Account", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(modifier = Modifier.height(20.dp))
        StatusChip(label = login?.uppercase() ?: "SIGNED IN", color = Color(0xFF5FBF6E))
        Spacer(modifier = Modifier.height(40.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    }
}

@Composable
private fun VaultStep(
    token: String,
    onBack: () -> Unit,
    onCreateNewTapped: () -> Unit,
    onSelected: (owner: String, repo: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val repos = remember { mutableStateListOf<GitHubRepoOption>() }
    var loadFailed by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    val validation = remember { mutableStateMapOf<String, Boolean?>() }

    LaunchedEffect(token) {
        val client = GitHubAccountClient()
        client.listRepos(token).fold(
            onSuccess = { list ->
                repos.clear()
                repos.addAll(list)
                loaded = true
                list.forEach { repo ->
                    validation[repo.fullName] = null
                    scope.launch {
                        validation[repo.fullName] = client.hasIngestContract(token, repo.owner, repo.name).getOrDefault(false)
                    }
                }
            },
            onFailure = { loadFailed = true },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Choose your vault", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(modifier = Modifier.height(16.dp))
        when {
            loadFailed -> Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    StatusChip(label = "COULDN'T LOAD REPOS", color = MaterialTheme.colorScheme.error)
                }
            }
            !loaded -> Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(repos, key = { it.fullName }) { repo ->
                    RepoRow(repo = repo, validated = validation[repo.fullName], onClick = { onSelected(repo.owner, repo.name) })
                }
                item { CreateNewVaultRow(onClick = onCreateNewTapped) }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun RepoRow(repo: GitHubRepoOption, validated: Boolean?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().alpha(if (validated == false) 0.55f else 1f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = repo.fullName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            when (validated) {
                null -> CircularProgressIndicator(
                    modifier = Modifier.height(16.dp).width(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                true -> Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF5FBF6E))
                false -> Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Color(0xFFE8A33D))
            }
        }
    }
}

/** Stub row per the design spec -- tapping it just shows a snackbar, no vault-creation flow yet. */
@Composable
private fun CreateNewVaultRow(onClick: () -> Unit) {
    val borderColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = borderColor,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                )
            }
            .then(Modifier.padding(2.dp)),
    ) {
        TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text("Create a new vault…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TranscriptionStep(onBack: () -> Unit, onSkip: () -> Unit, onKeyValidated: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var validating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Live transcription", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it; errorMessage = null },
            label = { Text("AssemblyAI API key") },
            singleLine = true,
            isError = errorMessage != null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = errorMessage!!, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onSkip, enabled = !validating) { Text("Skip") }
            Button(
                enabled = key.isNotBlank() && !validating,
                onClick = {
                    validating = true
                    scope.launch {
                        AssemblyAiKeyValidator().validate(key.trim()).fold(
                            onSuccess = { onKeyValidated(key.trim()) },
                            onFailure = { e ->
                                validating = false
                                errorMessage = when (e) {
                                    is AssemblyAiKeyError.Invalid -> "That key didn't work"
                                    else -> "Couldn't reach AssemblyAI"
                                }
                            },
                        )
                    }
                },
            ) { Text(if (validating) "Checking" else "Continue") }
        }
    }
}

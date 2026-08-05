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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.montauk.voicecapture.llm.AnthropicKeyError
import com.montauk.voicecapture.llm.AnthropicKeyValidator
import com.montauk.voicecapture.stt.AssemblyAiKeyError
import com.montauk.voicecapture.stt.AssemblyAiKeyValidator
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import kotlinx.coroutines.launch

private enum class WizardStep { ACCOUNT, VAULT, INTELLIGENCE }

/**
 * Design frame 1b: a 3-segment progress rail over ① Account (already
 * satisfied by login -- just shows the signed-in identity), ② Choose your
 * vault (repo picker with silent per-repo validation), ③ Intelligence (bead
 * vn-edu.48 -- two optional keys, each individually skippable: an AssemblyAI
 * key for "Live transcription" and an Anthropic key for "Word cloud &
 * titles"). Shown once after a user's first sign-in
 * ([AppSecretsStore.setupWizardCompleted][com.montauk.voicecapture.settings.AppSecretsStore]),
 * re-runnable from Settings, where both keys can also be viewed (masked,
 * last-4 visible) and replaced later. The close action and per-step Back are
 * always available -- this screen must never be the only thing standing
 * between a user and Sessions/recording.
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
                                    step = WizardStep.INTELLIGENCE
                                },
                            )
                            WizardStep.INTELLIGENCE -> IntelligenceStep(
                                onBack = { step = WizardStep.VAULT },
                                onSkip = ::finish,
                                onContinue = { assemblyKey, anthropicKey ->
                                    if (assemblyKey != null) app.secretsStore.userAssemblyAiKey = assemblyKey
                                    if (anthropicKey != null) app.secretsStore.userAnthropicKey = anthropicKey
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

/**
 * Bead vn-edu.48: two independently-skippable key fields, each labeled by
 * the feature it powers rather than the vendor mechanics ("Live
 * transcription" / "Word cloud & titles"), per the copy rules -- a user
 * only learns the specific vendor (AssemblyAI / Anthropic) from the field's
 * own placeholder text, matching how Settings labels the same two keys.
 * Continue validates only whichever field(s) are non-blank (a blank field is
 * simply skipped, never blocks); a validation failure on either field shows
 * that field's own inline error and does not advance, so a typo is never
 * silently accepted as "configured." Fields validate in order (AssemblyAI,
 * then Anthropic) -- a failure on the first stops before ever calling the
 * second's network check.
 *
 * [assemblyValidator]/[anthropicValidator] default to the real network
 * validators; `internal` (not `private`) and overridable purely for test
 * visibility -- same convention as [ApiKeyManagementRow] -- so
 * [IntelligenceStepTest] can point them at a [okhttp3.mockwebserver.MockWebServer]
 * instead of the real AssemblyAI/Anthropic APIs.
 */
@Composable
internal fun IntelligenceStep(
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onContinue: (assemblyKey: String?, anthropicKey: String?) -> Unit,
    assemblyValidator: AssemblyAiKeyValidator = AssemblyAiKeyValidator(),
    anthropicValidator: AnthropicKeyValidator = AnthropicKeyValidator(),
) {
    val scope = rememberCoroutineScope()
    var assemblyKey by remember { mutableStateOf("") }
    var assemblyError by remember { mutableStateOf<String?>(null) }
    var anthropicKey by remember { mutableStateOf("") }
    var anthropicError by remember { mutableStateOf<String?>(null) }
    var validating by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Intelligence", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Both are optional -- recording keeps working fully without either.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            MaskedKeyField(
                value = assemblyKey,
                onValueChange = { assemblyKey = it; assemblyError = null },
                label = "Live transcription",
                errorMessage = assemblyError,
                enabled = !validating,
            )
            Spacer(modifier = Modifier.height(24.dp))
            MaskedKeyField(
                value = anthropicKey,
                onValueChange = { anthropicKey = it; anthropicError = null },
                label = "Word cloud & titles",
                errorMessage = anthropicError,
                enabled = !validating,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, enabled = !validating) { Text("Back") }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onSkip, enabled = !validating) { Text("Skip") }
            Button(
                enabled = !validating,
                onClick = {
                    assemblyError = null
                    anthropicError = null
                    validating = true
                    scope.launch {
                        val assemblyTrimmed = assemblyKey.trim()
                        if (assemblyTrimmed.isNotEmpty()) {
                            val result = assemblyValidator.validate(assemblyTrimmed)
                            if (result.isFailure) {
                                validating = false
                                assemblyError = when (result.exceptionOrNull()) {
                                    is AssemblyAiKeyError.Invalid -> "That key didn't work"
                                    else -> "Couldn't reach AssemblyAI"
                                }
                                return@launch
                            }
                        }
                        val anthropicTrimmed = anthropicKey.trim()
                        if (anthropicTrimmed.isNotEmpty()) {
                            val result = anthropicValidator.validate(anthropicTrimmed)
                            if (result.isFailure) {
                                validating = false
                                anthropicError = when (result.exceptionOrNull()) {
                                    is AnthropicKeyError.Invalid -> "That key didn't work"
                                    else -> "Couldn't reach Anthropic"
                                }
                                return@launch
                            }
                        }
                        onContinue(
                            assemblyTrimmed.takeIf { it.isNotEmpty() },
                            anthropicTrimmed.takeIf { it.isNotEmpty() },
                        )
                    }
                },
            ) { Text(if (validating) "Checking" else "Continue") }
        }
    }
}

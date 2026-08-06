package com.montauk.voicecapture

import android.app.Application
import android.os.Build
import android.util.Log
import com.montauk.voicecapture.audio.AudioEngine
import com.montauk.voicecapture.auth.isGithubOAuthConfigured
import com.montauk.voicecapture.session.SessionStore
import com.montauk.voicecapture.session.TitleGenerator
import com.montauk.voicecapture.session.TitleGeneratorFactory
import com.montauk.voicecapture.settings.AppSecretsStore
import com.montauk.voicecapture.stt.SttClientFactory
import com.montauk.voicecapture.stt.StreamingSttClient
import com.montauk.voicecapture.summary.SummaryGenerator
import com.montauk.voicecapture.summary.SummaryGeneratorFactory
import com.montauk.voicecapture.tags.TagScorer
import com.montauk.voicecapture.tags.TagScorerFactory
import com.montauk.voicecapture.tags.TagTree
import com.montauk.voicecapture.tags.TagTreeCache
import com.montauk.voicecapture.tags.TagTreeRepository
import com.montauk.voicecapture.upload.BundleUploader
import com.montauk.voicecapture.upload.BundleUploaderFactory
import com.montauk.voicecapture.vault.VaultSessionCache
import com.montauk.voicecapture.vault.VaultSessionReader
import com.montauk.voicecapture.vault.VaultSessionSnapshot
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class VoiceCaptureApp : Application() {

    /**
     * Outlives any single [android.app.Service]/`Activity` -- scoped to the
     * process, cancelled only on process death. Bead vn-edu.42's post-
     * finalize title generation needs exactly this: [RecordingService]'s own
     * `lifecycleScope` is cancelled once the service stops itself right
     * after finalize, which would kill an async title call before a 5s
     * timeout ever got to elapse. [SupervisorJob] so one failing child (a
     * title-generation coroutine that throws despite [TitleGenerator]'s
     * "never throws" contract) can't cancel unrelated siblings.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var sessionStore: SessionStore
        private set

    lateinit var secretsStore: AppSecretsStore
        private set

    /**
     * Shared across sessions; safe to reuse since it's stateless beyond its
     * OkHttp connection pool. [refreshBundleUploader] rebuilds it whenever
     * [secretsStore]'s sign-in state changes (log out / restore from build
     * config) so an in-flight [com.montauk.voicecapture.upload.UploadWorker]
     * run picks up the new effective token on its next enqueue.
     *
     * `internal set` (same reasoning as [vaultSessionReader]): vn-edu.55's
     * delete-from-phone/bulk-archive tests swap this for a call-recording
     * fake to prove those LOCAL-ONLY actions never invoke the uploader --
     * a real behavioral guarantee (whatever's wired here goes untouched),
     * not just a fact about [SessionStore.deleteSession]'s own signature.
     */
    var bundleUploader: BundleUploader = BundleUploaderFactory.create(token = "", owner = "", repo = "")
        internal set

    /**
     * Bead vn-edu.47: fetch/cache/daily-refresh policy for the vault's
     * `tags.yaml` -- owned at the application level (like [bundleUploader])
     * since its on-disk cache should outlive any single recording session.
     * See [currentTagTree].
     */
    private lateinit var tagTreeRepository: TagTreeRepository

    /**
     * Bead vn-edu.54: fetch/cache policy for the vault's `sessions/` listing,
     * which drives Sessions-screen rows graduating from UPLOADED to
     * INTEGRATED. Owned at the application level like [tagTreeRepository] so
     * its on-disk cache outlives any single Sessions-screen visit. See
     * [currentVaultSessions] and [fetchVaultSessionArtifact].
     *
     * `internal set` (friend-module access from `src/test`, same as any
     * other Android unit-test source set) rather than `private set`: unlike
     * [tagTreeRepository] -- only ever driven from [RecordingService], never
     * started in a Robolectric test -- [SessionListScreen][com.montauk.voicecapture.ui.SessionListScreen]
     * calls [currentVaultSessions] unconditionally on every mount, so any
     * Compose interaction test that renders it with a non-blank effective
     * GitHub token (e.g. a fake token set purely to test a nav-tab label)
     * would otherwise make a real, unmocked HTTPS call to GitHub. Tests that
     * don't care about vault status swap this for one backed by a fake,
     * no-network [com.montauk.voicecapture.vault.VaultSessionSource] instead.
     */
    lateinit var vaultSessionReader: VaultSessionReader
        internal set

    override fun onCreate() {
        super.onCreate()
        sessionStore = SessionStore(File(filesDir, "sessions"))
        secretsStore = AppSecretsStore(this)
        tagTreeRepository = TagTreeRepository(TagTreeCache(File(filesDir, "tag-tree-cache")))
        vaultSessionReader = VaultSessionReader(VaultSessionCache(File(filesDir, "vault-session-cache")))
        refreshBundleUploader()
        recoverUnfinalizedSessions()
    }

    /** A fresh [StreamingSttClient] per recording session -- it owns one WebSocket connection's lifecycle. */
    fun newSttClient(): StreamingSttClient =
        SttClientFactory.create(secretsStore.effectiveAssemblyKey(BuildConfig.ASSEMBLYAI_API_KEY))

    /**
     * A fresh [TagScorer] per recording session (bead vn-edu.38). Bead
     * vn-edu.48: a runtime-entered Anthropic key (Settings' Replace flow or
     * the setup wizard's Intelligence step) now takes precedence over
     * `anthropic.apiKey`'s `local.properties`/`BuildConfig` dev-build
     * convenience, same pattern as [newSttClient] -- see
     * [AppSecretsStore.effectiveAnthropicKey]. [TagScorerFactory] falls back
     * to the keyless [com.montauk.voicecapture.tags.NoOpTagScorer] when the
     * effective key is blank (bead vn-edu.46 superseding decision -- no
     * heuristic guess, just no tags; see [isAnthropicKeyConfigured]).
     */
    fun newTagScorer(): TagScorer = TagScorerFactory.create(secretsStore.effectiveAnthropicKey(BuildConfig.ANTHROPIC_API_KEY))

    /**
     * The vault's current tag tree (bead vn-edu.47), resolved via
     * [tagTreeRepository]'s fetch/cache/daily-refresh policy against the
     * same effective GitHub token/owner/repo [refreshBundleUploader] uses.
     * A blank effective token (never signed in, or signed out) resolves to
     * [TagTree.EMPTY] inside the repository itself -- today's free-form
     * fallback -- without this call site needing its own blank-token check.
     * Never throws; a fetch failure degrades to the last on-disk cache, or
     * to [TagTree.EMPTY] if there's no cache yet either.
     */
    suspend fun currentTagTree(): TagTree = tagTreeRepository.currentTree(
        token = secretsStore.effectiveGithubToken(BuildConfig.GITHUB_TOKEN),
        owner = secretsStore.selectedVaultOwner ?: BuildConfig.VAULT_OWNER,
        repo = secretsStore.selectedVaultRepo ?: BuildConfig.VAULT_REPO,
    )

    /**
     * The vault's current organized-session listing (bead vn-edu.54),
     * resolved against the same effective GitHub token/owner/repo
     * [refreshBundleUploader] and [currentTagTree] use. A blank effective
     * token (never signed in, signed out) resolves to
     * [VaultSessionSnapshot.EMPTY] inside [vaultSessionReader] itself, so
     * [ui.SessionListScreen][com.montauk.voicecapture.ui.SessionListScreen]
     * doesn't need its own blank-token check before calling this. Never
     * throws; a fetch failure degrades to the last on-disk cache (with
     * [VaultSessionSnapshot.stale] set), or to EMPTY if there's no cache yet.
     */
    suspend fun currentVaultSessions(): VaultSessionSnapshot = vaultSessionReader.refresh(
        token = secretsStore.effectiveGithubToken(BuildConfig.GITHUB_TOKEN),
        owner = secretsStore.selectedVaultOwner ?: BuildConfig.VAULT_OWNER,
        repo = secretsStore.selectedVaultRepo ?: BuildConfig.VAULT_REPO,
    )

    /**
     * Decoded text of a vault artifact under `sessions/<sessionId>/<filename>`
     * (e.g. `organization.md`), disk-cached by [vaultSessionReader]. Backs
     * bead vn-edu.57's Filed-under tab.
     */
    suspend fun fetchVaultSessionArtifact(sessionId: String, filename: String): String? = vaultSessionReader.fetchSessionArtifact(
        token = secretsStore.effectiveGithubToken(BuildConfig.GITHUB_TOKEN),
        owner = secretsStore.selectedVaultOwner ?: BuildConfig.VAULT_OWNER,
        repo = secretsStore.selectedVaultRepo ?: BuildConfig.VAULT_REPO,
        sessionId = sessionId,
        filename = filename,
    )

    /**
     * Null when the effective Anthropic key isn't configured (bead
     * vn-edu.42, precedence updated by vn-edu.48) -- same keyless-means-
     * skip-the-feature contract as [TitleGeneratorFactory], one level up.
     * Callers ([com.montauk.voicecapture.service.RecordingService],
     * [com.montauk.voicecapture.ui.SessionDetailScreen]) treat null as "don't
     * even attempt title generation," matching today's keyless behavior
     * exactly.
     */
    fun newTitleGenerator(): TitleGenerator? = TitleGeneratorFactory.create(secretsStore.effectiveAnthropicKey(BuildConfig.ANTHROPIC_API_KEY))

    /**
     * Null when the effective Anthropic key isn't configured (bead
     * asn-evl) -- same keyless-means-skip-the-feature contract as
     * [newTitleGenerator]. [com.montauk.voicecapture.service.RecordingService]
     * treats null as "don't even start the summary pipeline for this
     * session," matching today's keyless behavior for tags/titles.
     */
    fun newSummaryGenerator(): SummaryGenerator? = SummaryGeneratorFactory.create(secretsStore.effectiveAnthropicKey(BuildConfig.ANTHROPIC_API_KEY))

    fun refreshBundleUploader() {
        bundleUploader = BundleUploaderFactory.create(
            token = secretsStore.effectiveGithubToken(BuildConfig.GITHUB_TOKEN),
            // The wizard's vault-picker step (vn-edu.15) can point this at a
            // different repo than the dev-build default; falls back to the
            // BuildConfig default until that step has run.
            owner = secretsStore.selectedVaultOwner ?: BuildConfig.VAULT_OWNER,
            repo = secretsStore.selectedVaultRepo ?: BuildConfig.VAULT_REPO,
        )
    }

    /** Booleans only for the settings screen -- never surface the actual key/token values. */
    fun isAssemblyKeyConfigured(): Boolean = secretsStore.effectiveAssemblyKey(BuildConfig.ASSEMBLYAI_API_KEY).isNotBlank()
    fun isGithubTokenConfigured(): Boolean = secretsStore.effectiveGithubToken(BuildConfig.GITHUB_TOKEN).isNotBlank()

    /**
     * Bead vn-edu.48/vn-edu.46: gates both Settings' "Word cloud & titles"
     * key row display and [com.montauk.voicecapture.ui.RecordingScreen]'s
     * tags slot -- keyless shows the exact register-key message there
     * instead of an empty (or heuristic-guessed) chips row.
     */
    fun isAnthropicKeyConfigured(): Boolean = secretsStore.effectiveAnthropicKey(BuildConfig.ANTHROPIC_API_KEY).isNotBlank()

    /**
     * False whenever no real GitHub OAuth App has been registered (bead
     * vn-edu.28): [BuildConfig.GITHUB_OAUTH_CLIENT_ID] is still the
     * placeholder from `app/build.gradle.kts`, which GitHub's device-flow
     * endpoint always 404s on. [ui.LoginScreen][com.montauk.voicecapture.ui.LoginScreen]
     * uses this to keep the primary sign-in button off of a path that can
     * never succeed, rather than let the user hit that 404. See
     * [com.montauk.voicecapture.auth.isGithubOAuthConfigured] for the pure
     * (JVM-testable) check this delegates to.
     */
    fun isGithubOAuthConfigured(): Boolean = isGithubOAuthConfigured(BuildConfig.GITHUB_OAUTH_CLIENT_ID)

    fun appVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "unknown"

    /**
     * Crash-recovery path from docs/audio-wal.md: a session whose process
     * died mid-recording leaves audio.wal but no audio.ogg/meta.json behind.
     * Finish finalizing it now so it shows up in the session list instead of
     * silently vanishing.
     */
    private fun recoverUnfinalizedSessions() {
        val unfinalized = sessionStore.findUnfinalizedSessions()
        if (unfinalized.isEmpty()) return

        thread(name = "session-recovery") {
            val engine = AudioEngine()
            for (handle in unfinalized) {
                runCatching {
                    val walFile = sessionStore.walFile(handle.dir)
                    val oggFile = sessionStore.oggFile(handle.dir)
                    engine.finalizeToOgg(walFile, oggFile)
                    val durationMs = oggFile.let { estimateDurationFromWalMtime(handle) }
                    sessionStore.writeMeta(
                        handle = handle,
                        durationMs = durationMs,
                        deviceModel = Build.MODEL,
                        appVersion = appVersionName(),
                    )
                    walFile.delete()
                }.onFailure { e ->
                    Log.e("VoiceCaptureApp", "Failed to recover session ${handle.sessionId}", e)
                }
            }
        }
    }

    private fun estimateDurationFromWalMtime(handle: com.montauk.voicecapture.session.SessionHandle): Long {
        val walFile = sessionStore.walFile(handle.dir)
        return (walFile.lastModified() - handle.startedAt.time).coerceAtLeast(0L)
    }
}

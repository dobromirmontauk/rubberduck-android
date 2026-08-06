package com.montauk.voicecapture.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.RecordingUiState
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.session.DetailTabPlaceholder
import com.montauk.voicecapture.session.UploadState
import com.montauk.voicecapture.testutil.FakeVaultSessionSource
import com.montauk.voicecapture.testutil.SessionFixtures
import com.montauk.voicecapture.vault.VaultSessionCache
import com.montauk.voicecapture.vault.VaultSessionReader
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * End-to-end (Robolectric, no emulator) coverage of the Transcript/Filed-under
 * tabs (bead vn-edu.57, redesigned to two tabs + processing_summary in
 * vn-edu.60) -- a real [VoiceCaptureApp] + [SessionDetailScreen], with
 * [FakeVaultSessionSource] standing in for the GitHub Contents API, same
 * approach as [SessionListVaultStatusTest] (bead vn-edu.54). The
 * `organization.json` payloads below are trimmed copies of the real fixture
 * this bead was designed against
 * (`voice-vault/tests/fixtures/organization-json/2026-08-05_0005_guem.sample.json`)
 * -- inlined rather than read from that submodule, which this app repo's CI
 * clone doesn't check out.
 *
 * Transcript is this app's own live-recorded transcript (bead vn-edu.60):
 * always locally available, so unlike Filed-under it never shows a
 * not-uploaded/awaiting-organization/load-failed placeholder -- those cases
 * are exercised here against Filed-under instead, which still fetches from
 * the vault and is gated on [com.montauk.voicecapture.session.SessionStatus].
 * The old Canonical tab (pass-2 `transcript.json`) is retired -- the app
 * transcript is now the canonical transcript product-wide -- so there is no
 * analogous vault-fetched-transcript coverage here anymore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionDetailTabsTest {

    companion object {
        private const val SESSION_ID = "2026-08-05_0005_guem"
        private const val TAB_TRANSCRIPT = "Transcript"
        private const val TAB_FILED_UNDER = "Filed under…"

        private val ORGANIZATION_JSON_NO_SUMMARY = """
            {
              "session_id": "$SESSION_ID",
              "organized_at": "2026-08-05T01:55:00-07:00",
              "source": "inbox/$SESSION_ID/",
              "fragments": [
                {
                  "index": 1,
                  "t_start_ms": 5000,
                  "t_end_ms": 28000,
                  "speaker": "A",
                  "summary": "Austin customer visit went well -- ops manager Denise was unprompted and receptive to the receipt-free flow.",
                  "tags": [
                    {"name": "ideas", "tag_id": "t_01KZ8AQPFPAZJ4YCMNXGW5DRYX"},
                    {"name": "work/mashgin", "tag_id": "t_01KZ8AQPFP71ENN6QR37Y5E9DC"}
                  ],
                  "destination": {
                    "path": "notes/ideas/receipt-free-kiosk-mode.md",
                    "section": "2026-08-05 (session $SESSION_ID)",
                    "mode": "appended"
                  },
                  "notes": "real-world validation for the receipt-free idea."
                }
              ],
              "unassigned_spans": [
                {"t_start_ms": 0, "t_end_ms": 5000, "reason": "opening chatter -- no content."}
              ],
              "totals": {"fragments": 1, "notes_created": 0, "notes_appended": 1, "new_tags": 0, "excluded": 0}
            }
        """.trimIndent()

        private val PROCESSING_SUMMARY = "Filed one fragment about the Austin customer visit under work/mashgin; " +
            "the opening chatter was left unassigned."

        private val ORGANIZATION_JSON_WITH_SUMMARY = """
            {
              "session_id": "$SESSION_ID",
              "processing_summary": "$PROCESSING_SUMMARY",
              "fragments": [
                {
                  "index": 1,
                  "t_start_ms": 5000,
                  "t_end_ms": 28000,
                  "summary": "Austin customer visit went well.",
                  "tags": [],
                  "destination": {"path": "notes/ideas/receipt-free-kiosk-mode.md", "section": "s", "mode": "appended"}
                }
              ]
            }
        """.trimIndent()

        private const val ORGANIZATION_MD = "# Organization\n\nFragment 1 (00:05-00:28, speaker A)\nSummary: Austin customer visit.\n"
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: VoiceCaptureApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RecordingStateHolder.update { RecordingUiState() }
        TranscriptStateHolder.reset()
        app.secretsStore.userGithubToken = "ghp_test_token"
        app.secretsStore.isSignedOut = false
    }

    private fun useVaultReader(integratedSessionIds: Set<String>, artifacts: Map<String, ByteArray> = emptyMap()) {
        app.vaultSessionReader = VaultSessionReader(
            VaultSessionCache(File(app.cacheDir, "vault-session-cache-test")),
            FakeVaultSessionSource(integratedSessionIds, artifacts),
        )
    }

    private fun openDetailAndSelectTab(tabLabel: String) {
        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.sessionDetail(SESSION_ID), onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(tabLabel).performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the Transcript tab shows the live-recorded transcript regardless of upload state`() {
        // A six-word line rather than a short one: DerivedTitle.from truncates
        // to its first five words for the header, so a short line would render
        // identically there and in the tab body -- assertExists would then
        // (correctly) find two matching nodes and fail on the count, not on
        // whether the tab itself rendered the line.
        useVaultReader(integratedSessionIds = emptySet())
        SessionFixtures.seedSession(app.sessionStore, sessionId = SESSION_ID, transcriptText = "purely local transcript text, six words", uploadState = UploadState.LOCAL)

        openDetailAndSelectTab(TAB_TRANSCRIPT)

        composeTestRule.onNodeWithText("purely local transcript text, six words").assertExists()
    }

    @Test
    fun `a LOCAL session shows Not uploaded yet on the Filed under tab`() {
        useVaultReader(integratedSessionIds = emptySet())
        SessionFixtures.seedSession(app.sessionStore, sessionId = SESSION_ID, transcriptText = "not uploaded yet", uploadState = UploadState.LOCAL)

        openDetailAndSelectTab(TAB_FILED_UNDER)

        composeTestRule.onNodeWithText(DetailTabPlaceholder.NOT_UPLOADED).assertExists()
    }

    @Test
    fun `an UPLOADED-but-not-integrated session shows Awaiting organization on the Filed under tab`() {
        useVaultReader(integratedSessionIds = emptySet())
        SessionFixtures.seedSession(app.sessionStore, sessionId = SESSION_ID, transcriptText = "awaiting organization", uploadState = UploadState.UPLOADED)

        openDetailAndSelectTab(TAB_FILED_UNDER)

        composeTestRule.onNodeWithText(DetailTabPlaceholder.AWAITING_ORGANIZATION).assertExists()
    }

    @Test
    fun `signed out never shows integrated content even with a matching vault listing`() {
        useVaultReader(
            integratedSessionIds = setOf(SESSION_ID),
            artifacts = mapOf("$SESSION_ID/organization.json" to ORGANIZATION_JSON_NO_SUMMARY.toByteArray()),
        )
        SessionFixtures.seedSession(app.sessionStore, sessionId = SESSION_ID, transcriptText = "keyless session", uploadState = UploadState.UPLOADED)
        app.secretsStore.isSignedOut = true

        openDetailAndSelectTab(TAB_FILED_UNDER)

        composeTestRule.onNodeWithText(DetailTabPlaceholder.AWAITING_ORGANIZATION).assertExists()
    }

    @Test
    fun `an integrated session with a processing summary renders the summary above the fragment cards`() {
        useVaultReader(
            integratedSessionIds = setOf(SESSION_ID),
            artifacts = mapOf("$SESSION_ID/organization.json" to ORGANIZATION_JSON_WITH_SUMMARY.toByteArray()),
        )
        SessionFixtures.seedSession(app.sessionStore, sessionId = SESSION_ID, transcriptText = "filed under with summary test", uploadState = UploadState.UPLOADED)

        openDetailAndSelectTab(TAB_FILED_UNDER)

        composeTestRule.onNodeWithText(PROCESSING_SUMMARY).assertExists()
        composeTestRule.onNodeWithText("Austin customer visit went well.").assertExists()
    }

    @Test
    fun `an integrated session without a processing summary shows the no-summary caption above the fragment cards`() {
        useVaultReader(
            integratedSessionIds = setOf(SESSION_ID),
            artifacts = mapOf("$SESSION_ID/organization.json" to ORGANIZATION_JSON_NO_SUMMARY.toByteArray()),
        )
        SessionFixtures.seedSession(app.sessionStore, sessionId = SESSION_ID, transcriptText = "filed under no summary test", uploadState = UploadState.UPLOADED)

        openDetailAndSelectTab(TAB_FILED_UNDER)

        composeTestRule.onNodeWithText("No summary recorded").assertExists()
        composeTestRule.onNodeWithText(
            "Austin customer visit went well -- ops manager Denise was unprompted and receptive to the receipt-free flow.",
        ).assertExists()
        composeTestRule.onNodeWithText("work/mashgin").assertExists()
        composeTestRule.onNodeWithText("notes/ideas/receipt-free-kiosk-mode.md (appended)").assertExists()
        composeTestRule.onNodeWithText("1 fragments · 0 created · 1 appended · 0 excluded").assertExists()
    }

    @Test
    fun `an integrated session with only organization md falls back to the legacy markdown render, with no summary caption`() {
        useVaultReader(
            integratedSessionIds = setOf(SESSION_ID),
            artifacts = mapOf("$SESSION_ID/organization.md" to ORGANIZATION_MD.toByteArray()),
        )
        SessionFixtures.seedSession(app.sessionStore, sessionId = SESSION_ID, transcriptText = "legacy fallback test", uploadState = UploadState.UPLOADED)

        openDetailAndSelectTab(TAB_FILED_UNDER)

        composeTestRule.onNodeWithText("Legacy format — organized before organization.json existed").assertExists()
        composeTestRule.onNodeWithText(ORGANIZATION_MD).assertExists()
        composeTestRule.onNodeWithText("No summary recorded").assertDoesNotExist()
    }

    @Test
    fun `an integrated session with no artifact and no cache shows the load-failed message on Filed under`() {
        useVaultReader(integratedSessionIds = setOf(SESSION_ID), artifacts = emptyMap())
        SessionFixtures.seedSession(app.sessionStore, sessionId = SESSION_ID, transcriptText = "load failed test", uploadState = UploadState.UPLOADED)

        openDetailAndSelectTab(TAB_FILED_UNDER)

        composeTestRule.onNodeWithText(DetailTabPlaceholder.LOAD_FAILED).assertExists()
    }
}

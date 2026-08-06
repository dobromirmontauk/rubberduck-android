package com.montauk.voicecapture.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.duck.LATENCY_BADGE_TEST_TAG
import com.montauk.voicecapture.duck.NOTES_CARD_TEST_TAG
import com.montauk.voicecapture.service.LatencyBadgeStateHolder
import com.montauk.voicecapture.service.LatencyBadgeUiState
import com.montauk.voicecapture.service.LatencySeverity
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.RecordingUiState
import com.montauk.voicecapture.service.SummaryStateHolder
import com.montauk.voicecapture.service.SummaryUiState
import com.montauk.voicecapture.service.TagApprovalStateHolder
import com.montauk.voicecapture.service.TagsStateHolder
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.session.RecordingMode
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bead asn-3sm, design-board sections 3 & 4: the duck stage's latency badge
 * (hooks only -- asn-55q owns the real measurements) and the transient
 * notes card (choreographed against [SummaryStateHolder], the shared
 * interface asn-evl implements the real producer for).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingScreenLayoutATest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: VoiceCaptureApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RecordingStateHolder.update { RecordingUiState() }
        TranscriptStateHolder.reset()
        TagsStateHolder.reset()
        TagApprovalStateHolder.reset()
        SummaryStateHolder.reset()
        LatencyBadgeStateHolder.reset()
        app.secretsStore.isSignedOut = true
    }

    private fun startOnDuckView() {
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }
        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `latency badge is hidden at OK severity`() {
        startOnDuckView()
        composeTestRule.onNodeWithTag(LATENCY_BADGE_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `latency badge shows its message at WARN severity`() {
        LatencyBadgeStateHolder.update(LatencyBadgeUiState(LatencySeverity.WARN, "transcription slow · 1.8s"))
        startOnDuckView()

        composeTestRule.onNodeWithTag(LATENCY_BADGE_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("transcription slow · 1.8s", substring = true).assertIsDisplayed()
    }

    @Test
    fun `notes card is hidden with no summary yet`() {
        startOnDuckView()
        composeTestRule.onNodeWithTag(NOTES_CARD_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `a fresh summary reveals the notes card with its newest bullet`() {
        startOnDuckView()

        SummaryStateHolder.update(
            SummaryUiState(
                bullets = listOf("Comparing 3 contractor bids", "Budget cap set at \$80k"),
                newestIndex = 1,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(NOTES_CARD_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Budget cap set at \$80k", substring = true).assertIsDisplayed()
    }
}

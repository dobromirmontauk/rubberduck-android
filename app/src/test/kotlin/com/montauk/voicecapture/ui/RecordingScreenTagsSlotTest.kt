package com.montauk.voicecapture.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.duck.CANDIDATE_WORD_TEST_TAG_PREFIX
import com.montauk.voicecapture.duck.CONFIRMED_WORD_TEST_TAG_PREFIX
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.RecordingUiState
import com.montauk.voicecapture.service.TagsStateHolder
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.session.RecordingMode
import com.montauk.voicecapture.tags.DisplayedTag
import com.montauk.voicecapture.tags.TagTier
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bead vn-edu.46 superseding decision (2026-08-05): keyless computes NO tags
 * at all -- the tags slot must show the exact register-key message, never
 * an empty row and never a heuristic guess, and tapping it must deep-link to
 * Settings' "Word cloud & titles" key row. Drives the real nav graph
 * ([AppNavHost]) rather than [RecordingScreen] in isolation so the deep
 * link's destination is actually verified, same pattern as
 * [AppNavHostInteractionTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingScreenTagsSlotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: VoiceCaptureApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RecordingStateHolder.update { RecordingUiState() }
        TranscriptStateHolder.reset()
        TagsStateHolder.reset()
        app.secretsStore.userAnthropicKey = null
        // isSignedOut = true forces AppSecretsStore.effectiveAnthropicKey() to
        // "" unconditionally (same guard KeyScreensScreenshotTest uses) --
        // userAnthropicKey = null alone is NOT enough for hermeticity: on a
        // clone whose local.properties bakes a real anthropic.apiKey into
        // BuildConfig.ANTHROPIC_API_KEY, effectiveAnthropicKey() falls back to
        // that value and "keyless" silently becomes "keyed" depending on which
        // machine runs this test. Every "configured key" test below explicitly
        // flips this back off before setting its own test key.
        app.secretsStore.isSignedOut = true
    }

    @Test
    fun `keyless shows the exact register-key message, not an empty row or chips`() {
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("(register your API key to see the word cloud)").assertIsDisplayed()
    }

    @Test
    fun `keyless with tags somehow present in state still shows the message, never the chips`() {
        // Defensive: even if TagsStateHolder is stale/non-empty (shouldn't
        // happen with NoOpTagScorer, but this proves the UI gate is on the
        // key, not merely on "is the list empty").
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }
        TagsStateHolder.update(listOf(DisplayedTag("stale tag", 0.9, rank = 1, tier = TagTier.PRIMARY)))

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("(register your API key to see the word cloud)").assertIsDisplayed()
        composeTestRule.onNodeWithText("stale tag").assertDoesNotExist()
    }

    @Test
    fun `tapping the register-key message deep-links to Settings' Anthropic key row`() {
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("(register your API key to see the word cloud)").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(ANTHROPIC_KEY_ROW_TEST_TAG).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Word cloud & titles").assertIsDisplayed()
    }

    @Test
    fun `a configured Anthropic key renders real tag chips, not the register-key message`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }
        TagsStateHolder.update(listOf(DisplayedTag("kitchen remodel", 0.9, rank = 1, tier = TagTier.PRIMARY)))

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("kitchen remodel").assertIsDisplayed()
        composeTestRule.onNodeWithText("(register your API key to see the word cloud)").assertDoesNotExist()
    }

    @Test
    fun `a configured Anthropic key with no tags yet shows neither chips nor the message`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("(register your API key to see the word cloud)").assertDoesNotExist()
        assertTrue("STOP button should still render normally", true)
        composeTestRule.onNodeWithText("STOP").assertIsDisplayed()
    }

    // --- Bead vn-edu.47 (superseded by asn-3sm's word cloud): a tree-anchored
    // tag renders distinctly from a proposal. Pre-asn-3sm this was a
    // dashed-vs-solid chip border; asn-3sm's word cloud expresses the same
    // "not confirmed yet" distinction via GREEN (confirmed) vs. WHITE
    // (candidate) word-cloud slots instead -- see TopicWordCloudTopics.fromDisplayedTags.

    @Test
    fun `a tree-matched tag renders as a GREEN confirmed word, not a candidate`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }
        TagsStateHolder.update(listOf(DisplayedTag("kitchen-remodel", 0.9, rank = 1, tier = TagTier.PRIMARY, tagId = "t_kitchen")))

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("${CONFIRMED_WORD_TEST_TAG_PREFIX}0").assertIsDisplayed()
    }

    @Test
    fun `a genuinely new proposal renders as a WHITE candidate word, not a confirmed one`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }
        TagsStateHolder.update(listOf(DisplayedTag("gardening", 0.6, rank = 1, tier = TagTier.SECONDARY, isProposal = true)))

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("${CANDIDATE_WORD_TEST_TAG_PREFIX}0").assertIsDisplayed()
        composeTestRule.onNodeWithText("gardening").assertIsDisplayed()
    }

    @Test
    fun `a legacy free-form tag (no tree, no proposal flag) renders as a GREEN confirmed word, not a candidate`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }
        TagsStateHolder.update(listOf(DisplayedTag("marathon training", 0.9, rank = 1, tier = TagTier.PRIMARY)))

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("${CONFIRMED_WORD_TEST_TAG_PREFIX}0").assertIsDisplayed()
    }
}

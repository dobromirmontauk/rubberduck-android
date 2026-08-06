package com.montauk.voicecapture.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.duck.APPROVED_WORD_TEST_TAG_PREFIX
import com.montauk.voicecapture.duck.EXISTING_WORD_TEST_TAG_PREFIX
import com.montauk.voicecapture.duck.PROPOSED_WORD_TEST_TAG_PREFIX
import com.montauk.voicecapture.service.RecordingActivityStateHolder
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.RecordingUiState
import com.montauk.voicecapture.service.TagRailStateHolder
import com.montauk.voicecapture.service.TagTreeStateHolder
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.session.RecordingMode
import com.montauk.voicecapture.tags.RailChipSource
import com.montauk.voicecapture.tags.TagRailChip
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bead vn-edu.46 superseding decision (2026-08-05), still in force after
 * bead asn-45m replaced the read-only tags slot with the editable tag rail
 * (see [RecordingScreenTagRailTest] for the rail/picker's own interaction
 * coverage) and bead asn-3sm demoted that rail into the double-tap debug
 * view behind the duck-stage thought cloud: keyless with nothing user-added
 * yet must show the exact register-key message, never an empty row and
 * never a heuristic guess, and tapping it must deep-link to Settings' "Word
 * cloud & titles" key row. The keyless message itself renders directly on
 * the duck view (bead asn-3sm; see [RecordingScreen]'s [REGISTER_KEY_MESSAGE_TEST_TAG]
 * usage) -- no double-tap needed to see it, unlike the thought-cloud/rail
 * content below it. Drives the real nav graph ([AppNavHost]) rather than
 * [RecordingScreen] in isolation so the deep link's destination is actually
 * verified, same pattern as [AppNavHostInteractionTest].
 *
 * Pinned to [RobolectricDeviceQualifiers.Pixel7] -- see
 * [RecordingScreenTranscriptSlotTest]'s KDoc for why the unqualified
 * Robolectric default window is too small once the tag rail sits above the
 * transcript pane in the debug view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class RecordingScreenTagsSlotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: VoiceCaptureApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RecordingStateHolder.update { RecordingUiState() }
        TranscriptStateHolder.reset()
        TagRailStateHolder.reset()
        TagTreeStateHolder.reset()
        RecordingActivityStateHolder.reset()
        // Bead asn-3sm: RecordingScreen now also reads these three
        // singletons -- reset so an earlier test class's state (a stale
        // approval set, a fake summary, a latency badge) never leaks into
        // this one.
        com.montauk.voicecapture.service.TagApprovalStateHolder.reset()
        com.montauk.voicecapture.service.SummaryStateHolder.reset()
        com.montauk.voicecapture.service.LatencyBadgeStateHolder.reset()
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
    fun `a configured Anthropic key renders real tag rail chips, not the register-key message`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }
        TagRailStateHolder.update(listOf(TagRailChip("kitchen remodel", source = RailChipSource.SUGGESTED)))

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        // substring = true: a tag with no tagId renders PROPOSED (bead asn-0jk),
        // which appends a " new?" affix to the word text itself.
        composeTestRule.onNodeWithText("kitchen remodel", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("(register your API key to see the word cloud)").assertDoesNotExist()
    }

    @Test
    fun `a configured Anthropic key with no tags yet shows neither the message nor a stray chip, and STOP still renders`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("(register your API key to see the word cloud)").assertDoesNotExist()
        composeTestRule.onNodeWithText("STOP").assertIsDisplayed()
    }

    // --- Bead asn-0jk's color semantics, on the duck view's thought cloud
    // (bead asn-3sm's replacement for the old dashed-chip UI, and now fed by
    // asn-45m's TagRailChip rather than the legacy DisplayedTag/TagsStateHolder
    // pair): BLUE/EXISTING is strictly "already in the tree" (TagRailChip.tagId
    // != null); anything else in the top set is PURPLE/PROPOSED until
    // approved -- see ThoughtCloudWords.fromTagRailChips.

    @Test
    fun `a tree-matched tag renders as an EXISTING (blue) word`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }
        TagRailStateHolder.update(listOf(TagRailChip("kitchen-remodel", tagId = "t_kitchen", source = RailChipSource.SUGGESTED)))

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("${EXISTING_WORD_TEST_TAG_PREFIX}kitchen-remodel").assertIsDisplayed()
    }

    @Test
    fun `a genuinely new proposal renders as a PROPOSED (purple, tappable) word`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }
        TagRailStateHolder.update(listOf(TagRailChip("gardening", tagId = null, source = RailChipSource.SUGGESTED)))

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("${PROPOSED_WORD_TEST_TAG_PREFIX}gardening").assertIsDisplayed()
        composeTestRule.onNodeWithText("gardening", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a free-form multi-word tag with no tree match also renders PROPOSED -- blue is strictly tree-matched`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }
        TagRailStateHolder.update(listOf(TagRailChip("marathon training", tagId = null, source = RailChipSource.SUGGESTED)))

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        // No tagId at all (no vault tree match) -> PROPOSED, not EXISTING --
        // asn-0jk's rule is "blue means tree-matched", full stop.
        composeTestRule.onNodeWithTag("${PROPOSED_WORD_TEST_TAG_PREFIX}marathon training").assertIsDisplayed()
    }

    @Test
    fun `tapping a PROPOSED word on the duck view approves it via onApproveTag, turning it GREEN`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }
        TagRailStateHolder.update(listOf(TagRailChip("kitchen remodel", tagId = null, source = RailChipSource.SUGGESTED)))

        composeTestRule.setContent {
            AppNavHost(
                startDestination = Routes.RECORDING,
                onNewSessionTapped = {},
                onStopRecording = {},
                // Mirrors what a real RecordingService-backed onApproveTag
                // does to TagRailStateHolder (see RecordingScreenTagRailTest's
                // identical fixture) -- this screen never mutates the rail
                // itself, it only calls the callback.
                onApproveTag = { tag -> TagRailStateHolder.update(listOf(TagRailChip(tag, tagId = null, source = RailChipSource.USER))) },
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("${PROPOSED_WORD_TEST_TAG_PREFIX}kitchen remodel").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("${APPROVED_WORD_TEST_TAG_PREFIX}kitchen remodel").assertIsDisplayed()
    }
}

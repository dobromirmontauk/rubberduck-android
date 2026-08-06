package com.montauk.voicecapture.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.RecordingUiState
import com.montauk.voicecapture.service.TagRailStateHolder
import com.montauk.voicecapture.service.TagTreeStateHolder
import com.montauk.voicecapture.session.RecordingMode
import com.montauk.voicecapture.tags.RailChipSource
import com.montauk.voicecapture.tags.TagRailChip
import com.montauk.voicecapture.tags.TagTree
import com.montauk.voicecapture.tags.TagTreeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose interaction tests for [RecordingScreen]'s editable tag rail + tag
 * picker (bead asn-45m) -- Robolectric/JVM, no service, no emulator. Mounts
 * [RecordingScreen] directly (not through [AppNavHost]) so [RecordingScreen]'s
 * onAddTag/onRemoveTag/onSwapTag callbacks -- which [MainActivity] would
 * otherwise wire to [com.montauk.voicecapture.service.RecordingService]
 * intents -- can be captured directly, mirroring [RecordingScreenTagsSlotTest]'s
 * seed-the-StateHolders-directly pattern for the rail's read side.
 *
 * A real (small, fixed) tag tree is seeded straight into [TagTreeStateHolder]
 * -- never via a real [com.montauk.voicecapture.tags.TagTreeRepository]
 * fetch, which only [com.montauk.voicecapture.service.RecordingService] ever
 * triggers (see [TagTreeStateHolder]'s KDoc) -- so [TagPickerSheet]'s search
 * results are deterministic and this suite never touches the network.
 *
 * Pinned to [RobolectricDeviceQualifiers.Pixel7] -- see
 * [RecordingScreenTranscriptSlotTest]'s KDoc for why the unqualified
 * Robolectric default window is too small for this screen with the rail in it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class RecordingScreenTagRailTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: VoiceCaptureApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RecordingStateHolder.update { RecordingUiState() }
        TagRailStateHolder.reset()
        // isSignedOut = true forces effectiveAnthropicKey() to blank
        // regardless of what BuildConfig was compiled with -- a machine
        // whose local.properties carries a real anthropic.apiKey would
        // otherwise make the "keyless" tests below spuriously see a
        // configured key. Tests that need a configured key flip this back
        // to false right before setting userAnthropicKey.
        app.secretsStore.isSignedOut = true
        TagTreeStateHolder.update(
            TagTree(
                listOf(
                    TagTreeNode(id = "t_work", name = "work", parentId = null, description = "Work."),
                    TagTreeNode(id = "t_mashgin", name = "mashgin", parentId = "t_work", description = "Mashgin."),
                ),
            ),
        )
    }

    private fun setContent(
        onAddTag: (String, String?) -> Unit = { _, _ -> },
        onRemoveTag: (String) -> Unit = {},
        onSwapTag: (String, String, String?) -> Unit = { _, _, _ -> },
        onApproveTag: (String) -> Unit = {},
    ) {
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-06_0900_ab12", mode = RecordingMode.LISTEN) }
        composeTestRule.setContent {
            RecordingScreen(
                onStopRecording = {},
                onAddTag = onAddTag,
                onRemoveTag = onRemoveTag,
                onSwapTag = onSwapTag,
                onApproveTag = onApproveTag,
            )
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `keyless with an empty rail shows the exact register-key message`() {
        app.secretsStore.userAnthropicKey = null
        setContent()

        composeTestRule.onNodeWithText("(register your API key to see the word cloud)").assertIsDisplayed()
        composeTestRule.onNodeWithTag(TAG_RAIL_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `keyless with a stale SUGGESTED chip in TagRailStateHolder still shows the message, never that chip`() {
        app.secretsStore.userAnthropicKey = null
        TagRailStateHolder.update(listOf(TagRailChip("stale tag", source = RailChipSource.SUGGESTED)))
        setContent()

        composeTestRule.onNodeWithText("(register your API key to see the word cloud)").assertIsDisplayed()
        composeTestRule.onNodeWithText("stale tag").assertDoesNotExist()
    }

    @Test
    fun `keyless with a USER chip shows the rail (and the add chip), not the register-key message`() {
        app.secretsStore.userAnthropicKey = null
        TagRailStateHolder.update(listOf(TagRailChip("kitchen remodel", source = RailChipSource.USER)))
        setContent()

        composeTestRule.onNodeWithText("(register your API key to see the word cloud)").assertDoesNotExist()
        composeTestRule.onNodeWithText("kitchen remodel").assertIsDisplayed()
        composeTestRule.onNodeWithTag(TAG_RAIL_ADD_CHIP_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun `a configured key with an empty rail shows no message but still shows the add chip and the inbox ribbon`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        setContent()

        composeTestRule.onNodeWithText("(register your API key to see the word cloud)").assertDoesNotExist()
        composeTestRule.onNodeWithTag(TAG_RAIL_ADD_CHIP_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Filing to: notes/inbox.md").assertIsDisplayed()
    }

    @Test
    fun `an approved free-form USER chip renders GREEN (APPROVED), an EXISTING suggested chip renders BLUE (EXISTING)`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        TagRailStateHolder.update(
            listOf(
                // No tagId, source USER -- PROPOSED_NEW + approved -- green.
                TagRailChip("kitchen remodel", source = RailChipSource.USER),
                // tagId set -- EXISTING regardless of source -- blue.
                TagRailChip("budget", tagId = "t_budget", source = RailChipSource.SUGGESTED),
            ),
        )
        setContent()

        composeTestRule.onNodeWithTag(TAG_RAIL_CHIP_APPROVED_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TAG_RAIL_CHIP_EXISTING_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun `an unapproved PROPOSED_NEW chip renders in the PROPOSED (purple) test tag, distinct from an EXISTING (blue) suggested chip`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        TagRailStateHolder.update(
            listOf(
                TagRailChip("gardening", tagId = null, source = RailChipSource.SUGGESTED),
                TagRailChip("budget", tagId = "t_budget", source = RailChipSource.SUGGESTED),
            ),
        )
        setContent()

        composeTestRule.onNodeWithTag(TAG_RAIL_CHIP_PROPOSED_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TAG_RAIL_CHIP_EXISTING_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun `approving a proposal transitions it from the PROPOSED (purple) to the APPROVED (green) test tag`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        TagRailStateHolder.update(listOf(TagRailChip("gardening", tagId = null, source = RailChipSource.SUGGESTED)))
        setContent(onApproveTag = { tag ->
            TagRailStateHolder.update(listOf(TagRailChip(tag, tagId = null, source = RailChipSource.USER)))
        })
        composeTestRule.onNodeWithTag(TAG_RAIL_CHIP_PROPOSED_TEST_TAG).assertIsDisplayed()

        composeTestRule.onNodeWithText("gardening").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_RAIL_CHIP_APPROVED_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TAG_RAIL_CHIP_PROPOSED_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `the filing ribbon reflects the rail's primary (first) chip's destination`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        TagRailStateHolder.update(listOf(TagRailChip("mashgin", tagId = "t_mashgin", source = RailChipSource.USER)))
        setContent()

        composeTestRule.onNodeWithText("Filing to: notes/work/mashgin.md").assertIsDisplayed()
    }

    @Test
    fun `tapping a chip's remove (X) invokes onRemoveTag with that chip's tag`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        TagRailStateHolder.update(listOf(TagRailChip("kitchen remodel", source = RailChipSource.SUGGESTED)))
        var removed: String? = null
        setContent(onRemoveTag = { removed = it })

        composeTestRule.onNodeWithContentDescription("Remove kitchen remodel").performClick()

        assertEquals("kitchen remodel", removed)
    }

    @Test
    fun `tapping the add chip opens the picker, picking a tree result invokes onAddTag with the node's id`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        var added: Pair<String, String?>? = null
        setContent(onAddTag = { tag, tagId -> added = tag to tagId })

        composeTestRule.onNodeWithTag(TAG_RAIL_ADD_CHIP_TEST_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TAG_PICKER_SHEET_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("work/mashgin").performClick()
        composeTestRule.waitForIdle()

        assertEquals("mashgin" to "t_mashgin", added)
        composeTestRule.onNodeWithTag(TAG_PICKER_SHEET_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `the picker's free-form Add row invokes onAddTag with a null tagId for unmatched text`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        var added: Pair<String, String?>? = null
        setContent(onAddTag = { tag, tagId -> added = tag to tagId })

        composeTestRule.onNodeWithTag(TAG_RAIL_ADD_CHIP_TEST_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TAG_PICKER_SEARCH_FIELD_TEST_TAG).performTextInput("gardening")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TAG_PICKER_ADD_FREEFORM_TEST_TAG).performClick()

        assertEquals("gardening", added?.first)
        assertNull(added?.second)
    }

    @Test
    fun `tapping an existing chip's body opens the picker in swap mode, picking a result invokes onSwapTag`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        // tagId set -- EXISTING, so a body tap opens the picker (swap) rather
        // than approving in place (that's the PROPOSED_NEW-only interaction).
        TagRailStateHolder.update(listOf(TagRailChip("budget", tagId = "t_budget", source = RailChipSource.SUGGESTED)))
        var swap: Triple<String, String, String?>? = null
        setContent(onSwapTag = { oldTag, newTag, newTagId -> swap = Triple(oldTag, newTag, newTagId) })

        composeTestRule.onNodeWithText("budget").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TAG_PICKER_SHEET_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("work/mashgin").performClick()

        assertEquals(Triple("budget", "mashgin", "t_mashgin"), swap)
    }

    @Test
    fun `tapping a still-unapproved PROPOSED_NEW chip's body approves it directly, no picker involved`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        // No tagId -- PROPOSED_NEW and still SUGGESTED (unapproved).
        TagRailStateHolder.update(listOf(TagRailChip("gardening", tagId = null, source = RailChipSource.SUGGESTED)))
        var approved: String? = null
        setContent(onApproveTag = { approved = it })

        composeTestRule.onNodeWithText("gardening").performClick()
        composeTestRule.waitForIdle()

        assertEquals("gardening", approved)
        composeTestRule.onNodeWithTag(TAG_PICKER_SHEET_TEST_TAG).assertDoesNotExist()
    }
}

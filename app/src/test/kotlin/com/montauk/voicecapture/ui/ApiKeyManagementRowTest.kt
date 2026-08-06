package com.montauk.voicecapture.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Bead vn-edu.48 acceptance criteria: masked-with-last-4 display, a reveal
 * toggle, a Replace-then-validate-before-storing flow. Exercises
 * [ApiKeyManagementRow] directly with fake `validate`/`onSave` lambdas
 * (no real network) -- [SettingsScreen] wires the real
 * [com.montauk.voicecapture.stt.AssemblyAiKeyValidator]/
 * [com.montauk.voicecapture.llm.AnthropicKeyValidator] in production, which
 * this test deliberately does not exercise.
 *
 * Bead vn-edu.52 (user-ratified invariant, 2026-08): baked-in dev
 * credentials are staying by design, so "Not configured" must never render
 * while a credential -- runtime or BuildConfig/`local.properties` dev
 * fallback -- is actually in effect. The `devFallbackActive` tests below
 * assert that string's *absence*, not just the dev-state label's presence,
 * so a regression that rendered both at once would still fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiKeyManagementRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `not configured shows the not-configured label and an Add affordance, not Replace`() {
        composeTestRule.setContent {
            ApiKeyManagementRow(
                label = "Word cloud & titles",
                initialValue = null,
                validate = { Result.success(Unit) },
                errorMessageFor = { "unused" },
                onSave = {},
            )
        }

        composeTestRule.onNodeWithText("Not configured").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add").assertIsDisplayed()
    }

    @Test
    fun `configured key shows masked last-4, not the raw value, until revealed`() {
        composeTestRule.setContent {
            ApiKeyManagementRow(
                label = "Live transcription",
                initialValue = "sk-secret-real-value-3f2a",
                validate = { Result.success(Unit) },
                errorMessageFor = { "unused" },
                onSave = {},
            )
        }

        composeTestRule.onNodeWithText("••••3f2a").assertIsDisplayed()
        composeTestRule.onNodeWithText("sk-secret-real-value-3f2a").assertDoesNotExist()
        composeTestRule.onNodeWithText("Replace").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Show Live transcription key").performClick()

        composeTestRule.onNodeWithText("sk-secret-real-value-3f2a").assertIsDisplayed()
        composeTestRule.onNodeWithText("••••3f2a").assertDoesNotExist()
    }

    @Test
    fun `replace flow validates before persisting -- a failure shows an inline error and does not call onSave`() {
        var saved: String? = null
        composeTestRule.setContent {
            ApiKeyManagementRow(
                label = "Word cloud & titles",
                initialValue = null,
                validate = { Result.failure(IllegalStateException("bad key")) },
                errorMessageFor = { "That key didn't work" },
                onSave = { saved = it },
            )
        }

        composeTestRule.onNodeWithText("Add").performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("sk-ant-bad")
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("That key didn't work").assertIsDisplayed()
        assertEquals("validation failure must never persist the key", null, saved)
        // Still showing "Not configured" -- the failed attempt didn't silently store anything.
        composeTestRule.onNodeWithText("Not configured").assertIsDisplayed()
    }

    @Test
    fun `dev fallback active with no runtime value shows the dev-key state and an Add affordance, not Replace`() {
        composeTestRule.setContent {
            ApiKeyManagementRow(
                label = "Live transcription",
                initialValue = null,
                validate = { Result.success(Unit) },
                errorMessageFor = { "unused" },
                onSave = {},
                devFallbackActive = true,
            )
        }

        composeTestRule.onNodeWithText("Using build-time key (dev)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not configured").assertDoesNotExist()
        composeTestRule.onNodeWithText("Add").assertIsDisplayed()
        composeTestRule.onNodeWithText("Replace").assertDoesNotExist()
    }

    @Test
    fun `dev fallback active is ignored once a runtime value is stored -- masked value wins`() {
        composeTestRule.setContent {
            ApiKeyManagementRow(
                label = "Live transcription",
                initialValue = "sk-secret-real-value-3f2a",
                validate = { Result.success(Unit) },
                errorMessageFor = { "unused" },
                onSave = {},
                devFallbackActive = true,
            )
        }

        composeTestRule.onNodeWithText("••••3f2a").assertIsDisplayed()
        composeTestRule.onNodeWithText("Using build-time key (dev)").assertDoesNotExist()
        composeTestRule.onNodeWithText("Replace").assertIsDisplayed()
    }

    @Test
    fun `no runtime value and no dev fallback shows Not configured, unchanged from before this bead`() {
        composeTestRule.setContent {
            ApiKeyManagementRow(
                label = "Live transcription",
                initialValue = null,
                validate = { Result.success(Unit) },
                errorMessageFor = { "unused" },
                onSave = {},
                devFallbackActive = false,
            )
        }

        composeTestRule.onNodeWithText("Not configured").assertIsDisplayed()
        composeTestRule.onNodeWithText("Using build-time key (dev)").assertDoesNotExist()
    }

    @Test
    fun `adding a runtime key while dev fallback is active switches display to the masked value`() {
        var saved: String? = null
        composeTestRule.setContent {
            ApiKeyManagementRow(
                label = "Live transcription",
                initialValue = null,
                validate = { Result.success(Unit) },
                errorMessageFor = { "unused" },
                onSave = { saved = it },
                devFallbackActive = true,
            )
        }

        composeTestRule.onNodeWithText("Add").performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("sk-live-goodkey1234")
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        assertEquals("sk-live-goodkey1234", saved)
        composeTestRule.onNodeWithText("••••1234").assertIsDisplayed()
        composeTestRule.onNodeWithText("Using build-time key (dev)").assertDoesNotExist()
    }

    @Test
    fun `replace flow persists and displays the new masked value only after validation succeeds`() {
        var saved: String? = null
        composeTestRule.setContent {
            ApiKeyManagementRow(
                label = "Word cloud & titles",
                initialValue = null,
                validate = { Result.success(Unit) },
                errorMessageFor = { "unused" },
                onSave = { saved = it },
            )
        }

        composeTestRule.onNodeWithText("Add").performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("sk-ant-goodkey1234")
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        assertEquals("sk-ant-goodkey1234", saved)
        composeTestRule.onNodeWithText("••••1234").assertIsDisplayed()
        composeTestRule.onNodeWithText("Replace").assertIsDisplayed()
    }
}

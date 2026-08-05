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

package com.montauk.voicecapture.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Bead asn-r60: the Settings auto-pause toggle + threshold picker, wired to
 * [com.montauk.voicecapture.settings.AppSecretsStore]. Exercises
 * [AutoPauseSection] directly with fake `onEnabledChange`/`onThresholdChange`
 * lambdas -- [SettingsScreen] wires the real store in production, same
 * convention as [ApiKeyManagementRowTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoPauseSectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `enabled shows the threshold picker with the current value selected`() {
        composeTestRule.setContent {
            AutoPauseSection(enabled = true, onEnabledChange = {}, thresholdMs = 30_000L, onThresholdChange = {})
        }

        composeTestRule.onNodeWithTag(AUTO_PAUSE_TOGGLE_TEST_TAG).assertIsDisplayed()
        // Bead asn-o63: 10s is now the default option (re-anchored down from 30s).
        composeTestRule.onNodeWithText("10s").assertIsDisplayed()
        composeTestRule.onNodeWithText("15s").assertIsDisplayed()
        composeTestRule.onNodeWithText("30s").assertIsDisplayed()
        composeTestRule.onNodeWithText("60s").assertIsDisplayed()
    }

    @Test
    fun `disabled hides the threshold picker entirely`() {
        composeTestRule.setContent {
            AutoPauseSection(enabled = false, onEnabledChange = {}, thresholdMs = 30_000L, onThresholdChange = {})
        }

        composeTestRule.onNodeWithTag(AUTO_PAUSE_TOGGLE_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("30s").assertDoesNotExist()
    }

    @Test
    fun `toggling off calls onEnabledChange(false)`() {
        var lastValue: Boolean? = null
        composeTestRule.setContent {
            AutoPauseSection(
                enabled = true,
                onEnabledChange = { lastValue = it },
                thresholdMs = 30_000L,
                onThresholdChange = {},
            )
        }

        composeTestRule.onNodeWithTag(AUTO_PAUSE_TOGGLE_TEST_TAG).performClick()

        assertEquals(false, lastValue)
    }

    @Test
    fun `tapping a different threshold chip calls onThresholdChange with its ms value`() {
        var lastThresholdMs: Long? = null
        composeTestRule.setContent {
            AutoPauseSection(
                enabled = true,
                onEnabledChange = {},
                thresholdMs = 30_000L,
                onThresholdChange = { lastThresholdMs = it },
            )
        }

        composeTestRule.onNodeWithText("60s").performClick()

        assertEquals(60_000L, lastThresholdMs)
    }
}

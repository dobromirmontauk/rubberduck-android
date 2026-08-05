package com.montauk.voicecapture.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.montauk.voicecapture.llm.AnthropicKeyValidator
import com.montauk.voicecapture.stt.AssemblyAiKeyValidator
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bead vn-edu.48 acceptance criteria: the wizard's Intelligence step's two
 * key fields are each individually skippable, and Continue validates before
 * ever calling [IntelligenceStep]'s `onContinue`. Points the injected
 * validators at [MockWebServer]s -- [SetupWizardScreen] wires the real
 * [AssemblyAiKeyValidator]/[AnthropicKeyValidator] in production, which this
 * test deliberately does not exercise (same boundary as
 * [ApiKeyManagementRowTest]).
 *
 * The validate call crosses a real dispatcher hop (`withContext(Dispatchers.IO)`
 * + a real (loopback) socket to [MockWebServer]) -- unlike
 * [ApiKeyManagementRowTest]'s synchronous fake lambdas, `composeTestRule.waitForIdle()`
 * alone isn't a reliable barrier for that, so assertions that depend on the
 * validate call having completed poll via `waitUntil` instead of asserting
 * immediately after a single `waitForIdle()`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntelligenceStepTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var assemblyServer: MockWebServer
    private lateinit var anthropicServer: MockWebServer

    @Before
    fun setUp() {
        assemblyServer = MockWebServer()
        assemblyServer.start()
        anthropicServer = MockWebServer()
        anthropicServer.start()
    }

    @After
    fun tearDown() {
        assemblyServer.shutdown()
        anthropicServer.shutdown()
    }

    private fun setContent(onSkip: () -> Unit = {}, onContinue: (String?, String?) -> Unit) {
        composeTestRule.setContent {
            IntelligenceStep(
                onBack = {},
                onSkip = onSkip,
                onContinue = onContinue,
                assemblyValidator = AssemblyAiKeyValidator(baseUrl = assemblyServer.url("/").toString().trimEnd('/')),
                anthropicValidator = AnthropicKeyValidator(baseUrl = anthropicServer.url("/v1").toString().trimEnd('/')),
            )
        }
    }

    private fun typeInto(label: String, text: String) {
        composeTestRule.onAllNodes(hasSetTextAction())[if (label == "Live transcription") 0 else 1].performTextInput(text)
    }

    private fun waitUntilSettled(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        composeTestRule.waitUntil(timeoutMillis) { condition() }
    }

    @Test
    fun `leaving both fields blank and tapping Continue skips both -- never blocks on an empty field`() {
        var result: Pair<String?, String?>? = null
        var invoked = false
        setContent { assembly, anthropic -> result = assembly to anthropic; invoked = true }

        composeTestRule.onNodeWithText("Continue").performClick()
        waitUntilSettled { invoked }

        assertEquals(null to null, result)
        assertEquals(0, assemblyServer.requestCount)
        assertEquals(0, anthropicServer.requestCount)
    }

    @Test
    fun `filling only the Anthropic field validates only Anthropic, leaves AssemblyAI untouched`() {
        anthropicServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
        var result: Pair<String?, String?>? = null
        setContent { assembly, anthropic -> result = assembly to anthropic }

        typeInto("Word cloud & titles", "sk-ant-onlyme")
        composeTestRule.onNodeWithText("Continue").performClick()
        waitUntilSettled { result != null }

        assertEquals(null, result?.first)
        assertEquals("sk-ant-onlyme", result?.second)
        assertEquals(0, assemblyServer.requestCount)
        assertEquals(1, anthropicServer.requestCount)
    }

    @Test
    fun `a failing AssemblyAI key shows its own error and never calls Anthropic's validator`() {
        assemblyServer.enqueue(MockResponse().setResponseCode(401))
        var result: Pair<String?, String?>? = null
        setContent { assembly, anthropic -> result = assembly to anthropic }

        typeInto("Live transcription", "bad-key")
        typeInto("Word cloud & titles", "sk-ant-shouldnt-be-tried")
        composeTestRule.onNodeWithText("Continue").performClick()
        waitUntilSettled { composeTestRule.onAllNodesWithText("That key didn't work").fetchSemanticsNodes().isNotEmpty() }

        assertNull("a validation failure must never invoke onContinue", result)
        assertEquals(0, anthropicServer.requestCount)
    }

    @Test
    fun `both keys valid completes with both values`() {
        assemblyServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"transcripts":[]}"""))
        anthropicServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
        var result: Pair<String?, String?>? = null
        setContent { assembly, anthropic -> result = assembly to anthropic }

        typeInto("Live transcription", "good-assembly-key")
        typeInto("Word cloud & titles", "good-anthropic-key")
        composeTestRule.onNodeWithText("Continue").performClick()
        waitUntilSettled { result != null }

        assertEquals("good-assembly-key", result?.first)
        assertEquals("good-anthropic-key", result?.second)
    }

    @Test
    fun `Skip completes without validating either field`() {
        var skipped = false
        setContent(onSkip = { skipped = true }) { _, _ -> }

        composeTestRule.onNodeWithText("Skip").performClick()
        waitUntilSettled { skipped }

        assertEquals(0, assemblyServer.requestCount)
        assertEquals(0, anthropicServer.requestCount)
    }
}

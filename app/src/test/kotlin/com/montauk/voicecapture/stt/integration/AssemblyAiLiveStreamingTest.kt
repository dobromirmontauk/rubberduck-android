package com.montauk.voicecapture.stt.integration

import com.montauk.voicecapture.stt.AssemblyAiStreamingSttClient
import com.montauk.voicecapture.stt.SttConnectionState
import com.montauk.voicecapture.stt.TranscriptPartial
import com.montauk.voicecapture.tags.HeuristicTagScorer
import com.montauk.voicecapture.tags.TagCoordinator
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

import org.junit.Test

/**
 * Mic-free integration test for the live-transcription path (plan bead
 * vn-edu.21): text -> synthesized WAV fixture -> the real
 * [AssemblyAiStreamingSttClient] -> AssemblyAI's live Universal-Streaming
 * endpoint -> word-overlap assertion against the source script. No
 * emulator, no microphone, no fakes on the STT boundary -- the only thing
 * standing between this test and a real on-device recording is
 * `AudioEngine` feeding PCM into [AssemblyAiStreamingSttClient.sendPcm]
 * instead of a WAV file read off disk.
 *
 * Skips (never fails) when `ASSEMBLYAI_API_KEY` isn't configured -- see
 * [resolveAssemblyAiApiKey]. Costs real AssemblyAI usage (~$0.01/run) when it
 * does run, and needs network, so it's excluded from the default `test`
 * task and only runs via `./gradlew integrationTest` -- see
 * `docs/stt-harness.md` for how to invoke it and what to expect.
 */
class AssemblyAiLiveStreamingTest {

    @Test
    fun `kitchen remodel script streams and transcribes with high word overlap`() {
        runFixtureThroughAssemblyAi(scriptResource = "kitchen-remodel.txt", wavResource = "kitchen-remodel.wav")
    }

    @Test
    fun `marathon training script streams and transcribes with high word overlap`() {
        runFixtureThroughAssemblyAi(scriptResource = "marathon-training.txt", wavResource = "marathon-training.wav")
    }

    /**
     * Bead vn-edu.45 follow-up ("can we have it take less than 60s?"):
     * measures how often AssemblyAI's own end-of-turn detector closes turns
     * on this fixture's 225s continuous monologue -- the exact scenario from
     * the user's original bug report (one 60s open turn, the next closing
     * ~160s in). [AssemblyAiStreamingSttClient] now tunes
     * `end_of_turn_confidence_threshold` / `max_turn_silence` for monologue
     * capture (see its own KDoc for the full rationale). Prints
     * finals-per-minute and every turn's boundaries on every run (not just
     * failures), which is how the real before/after numbers below were
     * established -- temporarily revert the tuning constants, rerun, compare.
     *
     * Measured directly against the live endpoint (real API usage, not
     * simulated), four separate runs total: untuned defaults measured 8
     * finals / 2.13 per minute once; the shipped tuning measured 8 once and
     * 9 / 2.39 per minute once, both at the *same* 0.984 word-overlap
     * quality as the baseline -- AssemblyAI's own end-of-turn model has real
     * run-to-run variance even for identical audio+params, so this reads as
     * "at least as good, sometimes modestly better," not a guaranteed win.
     * Also tried explicitly pinning `min_turn_silence=400`, which measured
     * *worse* (6 finals / 1.59 per minute clean, plus one run where a
     * mid-stream reconnect further scrambled ordering) -- that parameter's
     * un-set default is evidently already reasonable for this case, hence
     * it's deliberately left unset in the shipped code.
     *
     * Loose floor assertion rather than an exact count: AssemblyAI's own
     * model isn't under this repo's control and shows real run-to-run
     * variance (see above), and this fixture's narration has almost no true
     * acoustic silence between sentences (amplitude-envelope analysis found
     * only 3 gaps >=400ms in the whole 225s file) -- the first ~60s-long
     * turn in particular closes at essentially the same point tuned or not,
     * since there's no earlier acoustic pause for any confidence/silence
     * threshold to key off. [EXPECTED_MIN_FINALS_DRIVE_HOME] sits below both
     * of the *shipped* config's own clean measurements (8 and 9) with margin
     * for that variance, while still comfortably above the abandoned
     * `min_turn_silence=400` variant's worse result (6) -- catching a
     * regression back toward that, not just a total breakage.
     */
    @Test
    fun `drive-home-hiring monologue closes turns often enough for per-turn jsonl granularity`() {
        var captured: List<TranscriptPartial> = emptyList()
        runFixtureThroughAssemblyAi(
            scriptResource = "drive-home-hiring.txt",
            wavResource = "drive-home-hiring.wav",
            onFinals = { finals -> captured = finals },
        )
        val sorted = captured.sortedBy { it.startMs }
        val audioDurationMinutes = (sorted.lastOrNull()?.endMs ?: 0L) / 60_000.0
        val finalsPerMinute = if (audioDurationMinutes > 0) sorted.size / audioDurationMinutes else 0.0
        println("[drive-home-hiring] finals=${sorted.size} finalsPerMinute=${"%.2f".format(finalsPerMinute)}")
        sorted.forEachIndexed { i, f ->
            println("  turn $i: ${f.startMs}-${f.endMs}ms (${f.endMs - f.startMs}ms) \"${f.text.take(70)}\"")
        }
        assertTrue(
            "expected at least $EXPECTED_MIN_FINALS_DRIVE_HOME finals on the 225s drive-home-hiring " +
                "monologue with tuned end-of-turn params, got ${sorted.size}",
            sorted.size >= EXPECTED_MIN_FINALS_DRIVE_HOME,
        )
    }

    /**
     * Bead vn-edu.38 (live tags v2) replaced the old top-terms word cloud
     * this test used to exercise ([com.montauk.voicecapture.tags.TagTracker]
     * supersedes `topics/TopicCloud.kt`) with a confidence-ranked, hysteresis-
     * gated MAJOR-topic tracker. This wires the same kitchen-remodel run's
     * final transcript segments through the real end-to-end keyless path --
     * [HeuristicTagScorer] feeding a [TagCoordinator] exactly as
     * [com.montauk.voicecapture.service.RecordingService] does -- and asserts
     * at least one of the script's topic words ("kitchen", "remodel",
     * "contractor", "countertop", "cabinets") is still visible in whatever
     * [TagCoordinator] was last displaying once the whole script has played.
     * Deliberately a lower bar than the old test's ">=2 in the top 5": the
     * new tracker is conservative by design ("one strong tag beats three
     * weak ones") and only surfaces up to 3 slots with a very high bar for
     * slots 2/3, so demanding multiple simultaneous hits would fight the
     * feature's own stated goal rather than verify it.
     */
    @Test
    fun `kitchen remodel tags surface at least one expected topic via the keyless heuristic scorer`() {
        val coordinator = TagCoordinator(HeuristicTagScorer())
        var lastDisplayedTags: List<String> = emptyList()
        runFixtureThroughAssemblyAi(
            scriptResource = "kitchen-remodel.txt",
            wavResource = "kitchen-remodel.wav",
            onFinals = { finals ->
                runBlocking {
                    finals.sortedBy { it.startMs }.forEach { final ->
                        coordinator.onFinalLine(final.text, final.endMs)?.let { displayed ->
                            lastDisplayedTags = displayed.map { it.tag.lowercase() }
                        }
                    }
                }
            },
        )
        val hits = EXPECTED_KITCHEN_TOPICS.count { topic -> lastDisplayedTags.any { it.contains(topic) } }
        assertTrue("expected >=1 of $EXPECTED_KITCHEN_TOPICS reflected in displayed tags $lastDisplayedTags", hits >= 1)
    }

    private fun runFixtureThroughAssemblyAi(
        scriptResource: String,
        wavResource: String,
        onFinals: (List<TranscriptPartial>) -> Unit = {},
    ) {
        val apiKey = resolveAssemblyAiApiKey()
        assumeTrue(
            "ASSEMBLYAI_API_KEY not configured (env or ~/.config/voice-notes/env) -- skipping live STT integration test",
            apiKey != null,
        )
        val script = readResourceText(scriptResource)
        val wav = WavPcmReader.read(openResource(wavResource))
        require(wav.sampleRateHz == 16_000 && wav.channelCount == 1 && wav.bitsPerSample == 16) {
            "fixture $wavResource is ${wav.sampleRateHz}Hz/${wav.channelCount}ch/${wav.bitsPerSample}bit; " +
                "harness assumes 16kHz mono PCM16"
        }

        val client = AssemblyAiStreamingSttClient(apiKey!!)
        val finals = mutableListOf<TranscriptPartial>()

        runBlocking {
            val collector = launch {
                client.partials().collect { partial -> if (partial.isFinal) finals += partial }
            }
            client.connect(wav.sampleRateHz, wav.channelCount)
            val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                client.connectionState().first { it == SttConnectionState.CONNECTED }
            }
            assertTrue("did not reach CONNECTED within ${CONNECT_TIMEOUT_MS}ms", connected != null)

            streamPcmRealTime(client, wav.pcm)
            // Trailing silence gives AssemblyAI's own end-of-turn detector a
            // real signal to finalize the last turn, rather than relying
            // solely on the explicit Terminate message inside client.close().
            streamPcmRealTime(client, ByteArray(TRAILING_SILENCE_BYTES))
            delay(FINAL_TURN_GRACE_MS)

            client.close()
            collector.cancelAndJoin()
        }

        val transcript = finals.sortedBy { it.startMs }.joinToString(" ") { it.text }
        onFinals(finals)

        val score = wordOverlap(script, transcript)
        // Printed on every run, not just failures -- this test costs real
        // AssemblyAI usage, so whoever ran it manually gets to see what they
        // paid for without re-running with -i.
        println("[$scriptResource] word-overlap score=$score transcript=\"$transcript\"")
        assertTrue(
            "word-overlap score $score (threshold $OVERLAP_THRESHOLD) between script and transcript\n" +
                "script:     $script\n" +
                "transcript: $transcript",
            score >= OVERLAP_THRESHOLD,
        )
    }

    private suspend fun streamPcmRealTime(client: AssemblyAiStreamingSttClient, pcm: ByteArray) {
        var offset = 0
        while (offset < pcm.size) {
            val length = minOf(CHUNK_BYTES, pcm.size - offset)
            client.sendPcm(pcm, offset, length)
            offset += length
            delay(CHUNK_PACE_MS)
        }
    }

    private fun readResourceText(name: String): String =
        openResource(name).bufferedReader().readText()

    private fun openResource(name: String) =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing test resource $name" }

    private companion object {
        const val OVERLAP_THRESHOLD = 0.75
        const val CONNECT_TIMEOUT_MS = 10_000L
        // Established empirically (bead vn-edu.45 follow-up) against the
        // live endpoint across four runs: untuned defaults measured 8
        // finals once; the shipped end-of-turn tuning measured 8 once and 9
        // once (see the test's own KDoc for the full before/after data and
        // why this floor sits at 7 -- below the shipped config's own two
        // clean runs, above the abandoned min_turn_silence=400 variant's 6).
        const val EXPECTED_MIN_FINALS_DRIVE_HOME = 7

        // 100ms @ 16kHz mono 16-bit PCM = 3200 bytes -- matches the production
        // client's own CHUNK_TARGET_BYTES so this test exercises the same
        // framing the app uses, paced at real-time (AssemblyAI's v3 endpoint
        // tolerates faster delivery too, but this test isn't latency-sensitive
        // so real-time is the safest default).
        const val CHUNK_BYTES = 3_200
        const val CHUNK_PACE_MS = 100L

        const val BYTES_PER_SECOND_16K_MONO_PCM16 = 16_000 * 2
        const val TRAILING_SILENCE_BYTES = BYTES_PER_SECOND_16K_MONO_PCM16 * 2 // 2s of silence
        const val FINAL_TURN_GRACE_MS = 3_000L

        val EXPECTED_KITCHEN_TOPICS = setOf("kitchen", "remodel", "contractor", "countertop", "cabinets")
    }
}

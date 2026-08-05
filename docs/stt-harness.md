# STT integration harness

Mic-free integration test for the live-transcription path (plan bead
vn-edu.21): **text -> synthesized WAV fixture -> the real
`AssemblyAiStreamingSttClient` -> AssemblyAI's live Universal-Streaming
endpoint -> word-overlap assertion against the source script.** No emulator,
no microphone, no fakes at the STT boundary -- this exercises the same
production class (`app/src/main/kotlin/com/montauk/voicecapture/stt/AssemblyAiStreamingSttClient.kt`)
that `AudioEngine` feeds real microphone PCM into, just with a WAV file
standing in for the microphone.

## Files

- `app/src/test/kotlin/com/montauk/voicecapture/stt/integration/` -- the test
  itself (`AssemblyAiLiveStreamingTest.kt`), plus small self-contained
  helpers (`WavPcmReader.kt`, `AssemblyAiApiKey.kt`, `WordOverlap.kt`). Lives
  in the ordinary `test` source set (so it compiles with the rest of the
  test suite and can call `internal` production code if it ever needs to),
  in a package clearly separated from every other unit test.
- `app/src/integrationTest/resources/` -- fixtures: `kitchen-remodel.{txt,wav}`
  and `marathon-training.{txt,wav}`. Wired into the `test` source set's
  resources via `sourceSets { "test" { resources.srcDirs(...) } }` in
  `app/build.gradle.kts`, so they land on the test classpath without needing
  a whole separate Gradle source set.

## Running it

```
JAVA_HOME=<jdk 17/21> ASSEMBLYAI_API_KEY=... ./gradlew integrationTest
```

or, with `assemblyai.apiKey` in `local.properties`, or a key at
`~/.config/voice-notes/env` (`ASSEMBLYAI_API_KEY=...`, one line):

```
JAVA_HOME=<jdk 17/21> ./gradlew integrationTest
```

Key resolution order (see `AssemblyAiApiKey.kt`): process env
`ASSEMBLYAI_API_KEY` first, then `~/.config/voice-notes/env`. If neither is
set, **both test methods skip via `org.junit.Assume` -- the build stays
green, not red.** `local.properties`' `assemblyai.apiKey` (used to build the
app itself) is deliberately *not* consulted here; that key only reaches the
app via `BuildConfig`, which this JVM test doesn't build against.

Typical run: **~30-35 seconds per script, ~$0.01 total for both** (AssemblyAI
streaming is billed per second of audio; each fixture is ~27s of speech plus
2s of trailing silence). Output includes the received transcript and the
overlap score for each fixture even on success:

```
AssemblyAiLiveStreamingTest > kitchen remodel script streams and transcribes with high word overlap STANDARD_OUT
    [kitchen-remodel.txt] word-overlap score=0.9743589743589743 transcript="Our kitchen remodel is finally underway..."
AssemblyAiLiveStreamingTest > kitchen remodel script streams and transcribes with high word overlap PASSED
```

## Why the default `test` task never runs this

`app/build.gradle.kts` excludes `**/stt/integration/**` from every `Test`
task except `integrationTest`, which is registered separately and includes
only that package. This is enforced at the Gradle task level (not just a
runtime skip inside the test), so `./gradlew test` never touches the network
even if `ASSEMBLYAI_API_KEY` happens to be set in the ambient environment --
the integration test class isn't even compiled into `testDebugUnitTest`'s
execution set, let alone run.

`testOptions.unitTests.isReturnDefaultValues = true` (also added to the
`android {}` block) is a related, separate fix: `AssemblyAiStreamingSttClient`
logs via `android.util.Log` on its failure/reconnect paths, which crashes
with `RuntimeException: Method w in android.util.Log not mocked` in a plain
JVM unit test the moment a real socket hiccups -- exactly the kind of
transient event this test needs to tolerate, not choke on. This setting is
the standard AGP fix and affects no other test (none of the others call
through code paths that log).

## Assertion

Word overlap is scored as **recall against the source script**: fraction of
the script's distinct, normalized content words that appear anywhere in the
concatenated transcript. Normalization (see `WordOverlap.kt`): lowercase,
letters-only tokenization (which already drops digit tokens like `30,000`),
a stopword list, and a spelled-out-number-word list (`thirty`, `thousand`,
`six`, ...) -- AssemblyAI's formatter renders numbers as digits while the
fixture scripts spell them out in speech, so both forms are excluded from
scoring rather than trying to reconcile "thirty five thousand" with
"$35,000". Threshold is `>= 0.75`; both fixtures currently score **~0.97**.
On failure, the assertion message includes the actual score, the full
script, and the full transcript.

## Live-tags hook

Bead vn-edu.38 replaced the old plain word-frequency `topics/TopicCloud.kt`
with a confidence-ranked, hysteresis-gated MAJOR-topic tracker
(`com.montauk.voicecapture.tags.TagTracker`). `AssemblyAiLiveStreamingTest`
has `kitchen remodel tags surface at least one expected topic via the
keyless heuristic scorer`, which feeds the kitchen-remodel run's final
transcript segments through the real end-to-end keyless path --
`HeuristicTagScorer` driving a `TagCoordinator` exactly as
`RecordingService` does -- and asserts at least one of `{kitchen, remodel,
contractor, countertop, cabinets}` is still visible in whatever tags were
last displayed once the whole script has played. Deliberately a lower bar
than the old topic-cloud test's ">=2 in the top 5": the new tracker only
surfaces up to 3 slots with a very high bar for slots 2/3 by design ("one
strong tag beats three weak ones"), so demanding multiple simultaneous hits
would fight the feature's own stated goal. Enabled (not `@Ignore`d) since
it only requires *one* topic word to survive tracking, which has held up
across live runs.

## AssemblyAI streaming behaviors worth knowing for future tests

- **Pacing:** the harness sends 100ms chunks (3,200 bytes @ 16kHz mono
  PCM16 -- the same `CHUNK_TARGET_BYTES` the production client itself uses)
  paced at real-time (`delay(100ms)` between sends). This was not pushed
  faster in this harness; real-time is the safest default and this test
  isn't latency-sensitive. AssemblyAI's v3 docs describe tolerance for
  faster-than-real-time delivery, but that wasn't empirically exercised
  here -- a future test that wants a faster wall-clock runtime should try
  ~1.5-2x and watch for `end_of_turn` timing drift before trusting it.
- **End-of-turn needs real silence, not just `Terminate`.** The production
  `close()` sends `{"type":"Terminate"}` and waits only 300ms before closing
  the socket -- not enough time for AssemblyAI to finalize a turn that ends
  mid-utterance. This harness appends 2 seconds of zero-valued PCM ("silence")
  after the spoken audio, paced the same as real audio, plus a further 3
  second grace period before calling `close()`. That combination reliably
  produces a final `Turn` for the whole script in one shot in this harness's
  runs; skipping the silence tail is not recommended for anything that needs
  a complete final transcript.
- **Connect handshake:** typically completes well under 1 second once the
  WebSocket is dialed; the harness's 10-second connect timeout was never
  close to being exercised in testing.
- **No connection-limit issues observed** running the two fixtures
  back-to-back (sequential, not concurrent) in the same `integrationTest`
  invocation -- each test opens and cleanly closes its own connection.
  Concurrent connections from the same API key were not tested.

# rubberduck

[![CI](https://github.com/dobromirmontauk/voice-capture-android/actions/workflows/ci.yml/badge.svg)](https://github.com/dobromirmontauk/voice-capture-android/actions/workflows/ci.yml)

Someone to talk to to help think through your own thoughts. rubberduck is a
minimal voice-first Android capture app: record, live-transcribe, ship
sessions to voice-vault.

The recording core (crash-safe WAL, session store, foreground service),
live streaming transcription (AssemblyAI Universal-Streaming), confidence-
ranked live topic tags (Claude Haiku, keyless fallback), and bundle upload
(to the git-backed voice-vault, pure HTTPS) are all wired up.

## Build

```
./gradlew assembleDebug
./gradlew test
```

Requires a JDK compatible with Android Gradle Plugin 8.7 (JDK 17 or 21 --
newer JDKs are not yet supported by AGP; see "Toolchain notes" below if
`./gradlew` picks the wrong one).

## Testing without a working microphone (debug builds only)

The macOS emulator's host-mic passthrough is unreliable (it delivers a few
seconds of real audio, then goes silent), which makes it hard to exercise
live transcription end-to-end from Android Studio. `AudioEngine` captures
from an `AudioSource` interface rather than `AudioRecord` directly (see
`audio/AudioSource.kt`), so a debug-only `FileAudioSource` can stand in for
the mic and feed a recording session from a WAV file instead -- WAL, live
transcript, mic-level meter, and tag chips all behave exactly as they
would with a real mic, and the recording screen's source chip shows `FILE`
instead of `PHONE MIC`/`BLUETOOTH` so it's obvious which one is active. A
handful of bundled fixtures (`app/src/debug/assets/fixtures/`, all 16kHz
mono WAVs) ship in every debug build only -- never in release. See the
table below for what each one covers.

**From the emulator UI (no adb needed):** long-press the "New Session" tab
in the bottom nav. A picker lists the bundled fixtures; pick one and
recording starts immediately, fed from that file instead of the mic.

| Fixture (picker label) | File | Duration | Topic ground truth |
| --- | --- | --- | --- |
| Kitchen remodel | `kitchen-remodel.wav` | ~26s | kitchen remodel |
| Marathon training | `marathon-training.wav` | ~28s | marathon training |
| Dog walk download | `dog-walk-download.wav` | ~3m41s | kitchen remodel, work launch, vacation -- single-topic phases with a return to the remodel decision after the launch/vacation detour (bead vn-edu.39) |
| Drive home | `drive-home-hiring.wav` | ~3m45s | hiring decision (dominant, whole clip) -- dinner plans and car noise are brief digressions that should *not* earn their own topic chip; the single-strong-tag test case (bead vn-edu.39) |

The two long-form fixtures exist to exercise topic-chip dynamics (growth,
reordering, shrinking) that only show up once a session runs several
minutes and drifts across multiple major topics with natural transitions,
filler, and a few multi-second pauses (`say`'s `[[slnc N]]`). Their source
scripts -- the exact text fed to `say`, and the ground truth for what's
said and when the pauses land -- are committed alongside the WAVs as
`<name>.txt` in the same `app/src/debug/assets/fixtures/` directory, and
`scripts/generate-fixtures.sh` regenerates any fixture's WAV from its `.txt`
(macOS `say` + `afconvert`, pinned to the `Samantha` voice for reproducible
regeneration).

**From the command line, injecting your own file:** push any 16kHz mono
16-bit PCM WAV to the device and pass its path as the `inject_audio` extra
on the start action:

```
adb push my-test-clip.wav /sdcard/my-test-clip.wav
adb shell am start-foreground-service \
  -a com.montauk.voicecapture.action.START \
  -e inject_audio /sdcard/my-test-clip.wav \
  com.montauk.voicecapture/.service.RecordingService
```

Either path only works in a debug build (`FileAudioSource` is never wired up
when `BuildConfig.DEBUG` is false, regardless of what extras a crafted
intent carries). Recording continues normally once the file is exhausted --
end-of-file behaves like silence rather than stopping the session, so tap
Stop in the app when you're done. Non-16kHz-mono or non-16-bit WAVs fail
fast with a clear exception instead of silently misdecoding.

## Configuring secrets for the live demo

Both live transcription and bundle upload are optional at runtime -- with no
key configured, recording still works end to end (WAL -> `audio.ogg`,
session list, upload-state chip just never leaves `LOCAL`). To turn them on,
add these keys to `local.properties` (gitignored, never committed):

```properties
sdk.dir=/path/to/android-sdk
assemblyai.apiKey=<your AssemblyAI API key>
anthropic.apiKey=<your Anthropic API key>
github.token=<a token with repo access to dobromirmontauk/voice-vault>
# optional overrides, default to the values below:
vault.owner=dobromirmontauk
vault.repo=voice-vault
```

Each key also has an environment-variable fallback for one-off builds
without writing a secret to disk (`local.properties` wins if both are set):

```
ASSEMBLYAI_API_KEY=... ANTHROPIC_API_KEY=... GITHUB_TOKEN=$(gh auth token) ./gradlew assembleDebug
```

`github.token` needs `repo` scope on the private `dobromirmontauk/voice-vault`
repo (a `gh auth token` from an account with access works for local testing;
use a fine-grained PAT scoped to just that repo for anything longer-lived).

## Architecture

```
com.montauk.voicecapture
├── audio/
│   ├── OpusFrameWal.kt    append-only, crash-safe log of encoded Opus packets
│   ├── AudioSource.kt     start/read/stop PCM interface -- AudioEngine and everything
│   │                      downstream is agnostic to where the PCM comes from
│   ├── MicAudioSource.kt  AudioSource backed by a real AudioRecord (production path)
│   ├── FileAudioSource.kt debug-only AudioSource that paces a decoded WAV file in
│   │                      real time instead of a live mic -- see "Testing without a
│   │                      working microphone" above
│   └── AudioEngine.kt     AudioSource -> MediaCodec (Opus) -> OpusFrameWal;
│                          finalizes a WAL into a playable audio.ogg via MediaMuxer
├── session/
│   ├── SessionId.kt       YYYY-MM-DD_HHMM_<4 random lowercase alnum> ids
│   ├── SessionMeta.kt     meta.json schema (mirrors the voice-vault ingest contract)
│   └── SessionStore.kt    owns files/sessions/<id>/ on disk: create, finalize,
│                          list, and recover sessions orphaned by a process death
├── service/
│   ├── RecordingService.kt   foreground service (microphone type), persistent
│   │                         notification with elapsed time + Stop action
│   └── RecordingState.kt     publishes recording state to the UI
├── stt/
│   ├── StreamingSttClient.kt          interface + factory (real impl vs. no-op by API key)
│   ├── AssemblyAiStreamingSttClient.kt  AssemblyAI Universal-Streaming v3 WebSocket client
│   └── TurnMessage.kt                 wire-format parsing, no OkHttp dep -- plain JVM testable
├── tags/
│   ├── TagTracker.kt         pure-Kotlin confidence ranking/hysteresis/decay, store 10 show <=3
│   ├── TagScorer.kt          pluggable scoring interface
│   ├── HeuristicTagScorer.kt  keyless noun-phrase-ish fallback, no network
│   ├── AnthropicTagScorer.kt  Claude Haiku via the Messages API, degrades to the heuristic
│   ├── TagCoordinator.kt     rolling-tail + scorer-cadence glue in front of TagTracker
│   └── TagsEventLine.kt      `{"event":"tags",...}` live-transcript.jsonl event line
├── upload/
│   ├── BundleUploader.kt          interface + factory (real impl vs. no-op by token)
│   ├── GitHubBundleUploader.kt    Git Data API + Git LFS batch API, one commit per session
│   ├── GitHubLfsPointer.kt        LFS oid/sha256 + pointer-file text, no OkHttp dep
│   ├── GitHubApiModels.kt         minimal request/response shapes for both GitHub HTTP surfaces
│   └── UploadWorker.kt            WorkManager retry queue (network constraint + backoff)
└── ui/
    ├── MainActivity.kt        permission handling + service start/stop
    ├── VoiceCaptureScreen.kt  record button, elapsed time, session list,
    │                          live-transcript placeholder pane
    └── theme/Theme.kt         dark-by-default, large glanceable type
```

**Why a WAL instead of writing straight into an OGG/MediaMuxer container
during recording:** see `docs/audio-wal.md`. Short version -- a muxer
container isn't safely readable until it's closed; a length-prefixed,
flush-per-frame log is readable (up to the last complete frame) at every
point in its life, which is what "process death loses at most ~1s" requires.

## Live transcription and bundle upload

- **Live transcription** (`stt/AssemblyAiStreamingSttClient.kt`): streams
  16kHz mono PCM16 to AssemblyAI's Universal-Streaming v3 WebSocket
  (`wss://streaming.assemblyai.com/v3/ws`), batched into ~100ms binary
  frames. `Turn` messages map to `TranscriptPartial`s: `end_of_turn=false`
  is a live, revisable partial (UI-only, dimmed); `end_of_turn=true` is
  immutable and gets appended to `live-transcript.jsonl` *and* shown solid
  in the UI. A dropped socket reconnects with exponential backoff (up to 5
  attempts); missed audio during the gap has no live line, which is fine --
  `live-transcript.jsonl` is explicitly best-effort per the ingest contract,
  and pass-2 transcription is ground truth. No API key configured -> the
  "live transcription off" chip shows and recording is otherwise unaffected.
- **Bundle upload** (`upload/GitHubBundleUploader.kt`): lands
  `inbox/<session-id>/{audio.ogg, live-transcript.jsonl, meta.json}` in one
  commit on `main`, pure HTTPS (no git binary) -- the audio file via the Git
  LFS batch API (checking `.gitattributes` first; falls back to a regular
  blob and logs a warning if `*.ogg` somehow isn't LFS-tracked, rather than
  editing the vault's `.gitattributes` from here), the small files via the
  Git Data API. `UploadWorker` (WorkManager, network-constrained,
  exponential backoff) drives LOCAL -> QUEUED -> UPLOADED; the local session
  is never deleted regardless of upload outcome. Idempotent: a contents-API
  check on `inbox/<session-id>` before doing any work means a retry after a
  crash mid-upload just no-ops instead of double-committing.

Both fall back to a no-op implementation (via `SttClientFactory` /
`BundleUploaderFactory`) whenever their secret isn't configured -- see
"Configuring secrets for the live demo" above.

## UI tests (Robolectric)

`app/src/test/kotlin/com/montauk/voicecapture/ui/AppNavHostInteractionTest.kt`
(bead vn-edu.35) drives the real `AppNavHost` nav graph + real screens on the
JVM via Robolectric + Compose's `createComposeRule()` -- no emulator, no
device. It runs in the default `./gradlew test` (part of `testDebugUnitTest`).

- Seeds on-disk session fixtures directly through `SessionStore` (see
  `testutil/SessionFixtures.kt`) and drives `RecordingStateHolder` /
  `TranscriptStateHolder` (the same global singletons `RecordingService`
  publishes to) instead of starting a real foreground service.
- Pinned to `@Config(sdk = [34])` for a deterministic Robolectric Android
  version, independent of `compileSdk`/`targetSdk`.
- Excluded from `testReleaseUnitTest` specifically (see the `build.gradle.kts`
  comment next to `exclude("**/AppNavHostInteractionTest.class")`) --
  `androidx.compose.ui:ui-test-manifest` is `debugImplementation`-only on
  purpose, since shipping its test-only host `Activity` declaration in the
  *release* manifest would be worse than skipping this one suite under the
  release unit-test variant. The same code is already fully exercised by
  `testDebugUnitTest`.
- `sessionsListRendersAboveBottomAnchoredNav`, `tappingASessionOpensDetail`,
  and `stopReturnsToSessionsWithNewSessionVisible` are the tests that catch
  the vn-edu.33 nav regression. Reproduced while writing this suite (before
  vn-edu.33's fix had landed): the debug-only "New Session" bottom-nav tab
  measured/placed itself across the *entire* screen height instead of the
  nav bar's own ~80dp band. That both misplaced the nav bar (caught by the
  first test's bounds assertion, e.g. `nav bottom=275.0, root bottom=470.0`)
  and silently intercepted taps meant for content behind it (the second and
  third tests failed with `The component is not displayed!` on the row/tab
  they clicked past). To replay that failure locally: `git stash`, `git
  revert -n <vn-edu.33 commit>` (or hand-revert `BottomNavBar.kt`'s
  `DebugNewSessionItem` to drop its `Modifier.fillMaxHeight()`), rerun
  `./gradlew testDebugUnitTest --tests "*AppNavHostInteractionTest"`, then
  `git checkout -- app/src/main/kotlin/com/montauk/voicecapture/ui/BottomNavBar.kt`
  (or `git stash pop`) to restore the fix.

## Screenshot tests (Roborazzi)

`app/src/test/kotlin/com/montauk/voicecapture/screenshot/KeyScreensScreenshotTest.kt`
(bead vn-edu.34) renders five key screens -- Sessions list + bottom nav, the
full Recording stack (timer, chips, loudness meter, mode switcher, live
transcript, tag chips, STOP), Session detail, Settings, and Login -- via
Robolectric's native-graphics renderer and diffs each against a committed
golden PNG under `src/test/screenshot/goldens/`. No emulator, no device.

**Determinism.** Every screen is driven from fixed fixture state: fixed
session ids/dates/transcript text via `testutil/SessionFixtures.kt` (no
`Date()`/`System.currentTimeMillis()`/`Random`), a fixed device
(`@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)`), and the app's
theme is always dark regardless of system setting (`VoiceCaptureTheme`).
None of these screens have a persistent animation running at first render,
so no explicit clock-advance/animation-disable is needed beyond
`composeTestRule.waitForIdle()`.

**Hermeticity (bead vn-edu.40).** Fixed fixture state has to cover
`AppSecretsStore` too, not just session/transcript data: `SettingsScreen`
reads `effectiveGithubToken()` / `effectiveAssemblyKey()` / `selectedVault*`,
which fall back to `BuildConfig.ASSEMBLYAI_API_KEY` / `GITHUB_TOKEN` /
`VAULT_OWNER` / `VAULT_REPO` -- values baked in at build time from
*whichever machine's* `local.properties` ran the build. The `settings`
golden originally encoded "Configured" vs. "Not configured" for whichever
developer's real keys happened to be present, so `verifyRoborazziDebug`
passed on a keyless clone but failed on a clone with real
`assemblyai.apiKey` / `github.token` set. Fixed by pinning
`secretsStore.isSignedOut = true` (forces both `effective*()` getters to `""`
unconditionally, regardless of `BuildConfig`) and
`selectedVaultOwner`/`selectedVaultRepo` to fixed values in
`KeyScreensScreenshotTest.setUp()`. Verified green on both a keyless clone
and a clone carrying real keys. `LoginScreen`'s
`BuildConfig.GITHUB_OAUTH_CLIENT_ID` dependency has no equivalent
`AppSecretsStore` seam to override (see the KDoc on the `login` test) --
documented as a residual, currently-dormant risk rather than silently
ignored.

**Where the goldens live.** `build.gradle.kts` sets
`roborazzi { outputDir.set(file("src/test/screenshot/goldens")) }`, but that
extension only auto-prefixes `captureRoboImage()` calls that *omit* a
filename; each test here passes an explicit one (`GOLDEN_DIR + "<name>.png"`,
resolved against the test JVM's working directory, which Gradle sets to the
`app/` module root), so the directory is spelled out in both places rather
than relying on the extension alone -- see the comment on
`KeyScreensScreenshotTest.GOLDEN_DIR`.

**Running it.**

- `./gradlew verifyRoborazziDebug` -- re-renders every screen and fails the
  build on any pixel diff (writes a `<name>_compare.png` diff overlay under
  `app/build/outputs/roborazzi/` on failure, not committed). This is the gate;
  run it after any change that touches a screen these tests cover.
- `./gradlew recordRoborazziDebug` -- re-renders and *overwrites* the
  committed goldens. Run this once you've reviewed a deliberate visual change
  and confirmed it's correct, then `git diff` the resulting PNGs (or eyeball
  them) and commit the updated goldens **in the same commit** as the code
  change that caused them to move -- a golden update with no accompanying
  code change, or a code change with no golden update, should read as
  suspicious in review.
- `./gradlew test` / `./gradlew testDebugUnitTest` deliberately do **not**
  run these: `captureRoboImage()` with no `roborazzi.test.{record,verify,
  compare}` mode active just writes the PNG unconditionally, which would
  silently rewrite committed goldens (and dirty the working tree) on every
  ordinary test run instead of asserting anything. `build.gradle.kts` excludes
  `**/screenshot/**` from `Test` tasks unless one of Roborazzi's own tasks
  (`recordRoborazziDebug` / `verifyRoborazziDebug` / `compareRoborazziDebug`)
  was what was actually requested -- see the comment above
  `roborazziTaskRequested` there for how that's detected.
- Also excluded from `testReleaseUnitTest` regardless of task name, same
  reason as `AppNavHostInteractionTest` above (`ui-test-manifest` is
  `debugImplementation`-only).

**Proof it catches a regression.** Verified while building this suite:
temporarily changed `SettingsScreen.kt`'s headline text (`"Settings"` ->
`"SETTINGS BROKEN"`), ran `./gradlew verifyRoborazziDebug`, and got:
```
java.lang.AssertionError: Roborazzi: .../src/test/screenshot/goldens/settings.png is changed.
See the compare image at .../build/outputs/roborazzi/settings_compare.png
```
Reverting the change (`git checkout -- app/src/main/kotlin/com/montauk/voicecapture/ui/SettingsScreen.kt`)
and rerunning `verifyRoborazziDebug` goes green again -- no golden update
needed since nothing legitimately changed.

## Live tags (MAJOR-topic chips)

The recording screen shows up to 3 confidence-ranked chips for the MAJOR
topics of the current conversation (`tags/TagTracker.kt`, replacing an
earlier plain word-frequency cloud). Chip size scales with confidence;
chips reorder, grow, shrink, and get replaced as the conversation moves on
-- gated by hysteresis (a real entry bar for the 1st chip, a *very* high one
for the 2nd/3rd, a much lower exit bar, and a minimum on-screen dwell) so a
brief dip in confidence doesn't make a chip flicker away and back.

- **Scoring** (`tags/AnthropicTagScorer.kt`): every ~25s, a rolling tail of
  the recent final transcript (plus the tags currently being tracked, so
  the model can prefer sticking with an existing label) is sent to Claude
  Haiku's Messages API, asking for up to 5 MAJOR topics with a 0.0-1.0
  confidence each. At that cadence, even a 30-60 minute session costs
  pennies. `anthropic.apiKey` in `local.properties`/`BuildConfig` follows
  the exact same secret pattern as `assemblyai.apiKey` -- see "Configuring
  secrets for the live demo" above.
- **Keyless fallback** (`tags/HeuristicTagScorer.kt`): with no Anthropic key
  configured, or if any call to it fails/returns unparseable output,
  tagging degrades silently to a local, offline noun-phrase-ish extractor --
  no network, no cost, conservative by design ("one strong tag beats three
  weak ones"). Recording, transcription, and upload are all completely
  unaffected either way; a scorer only ever feeds `TagTracker`, never the
  audio/STT pipeline.
- **Persistence** (`tags/TagsEventLine.kt`): every time the *displayed* set
  changes, a `{"t_ms":...,"event":"tags","tags":[{"tag":"marathon
  training","confidence":0.87,"rank":1}]}` line is appended to
  `live-transcript.jsonl` -- same contract-tolerant event-line pattern as
  mode-change events (see `session/ModeEventLine.kt`): a reader decoding
  only transcript segments simply fails to parse this line's shape and
  skips it. An offline organizer that cares about tags derives per-tag
  intervals from consecutive "tags" lines.

## Toolchain notes for the next agent

- Built and tested on this machine with **Temurin JDK 21** (the system
  default was OpenJDK 26 via Homebrew, which AGP 8.7 does not yet support).
  If `./gradlew` complains about the JDK, point `org.gradle.java.home` in
  `gradle.properties` (or `JAVA_HOME`) at a JDK 17/21 install.
- The Gradle wrapper is pinned to a version compatible with AGP 8.7 --
  always build via `./gradlew`, not a system-installed Gradle, which may be
  a newer major version (9.x) that doesn't yet support this AGP.
- `local.properties` (not committed) must contain `sdk.dir=<path to Android
  SDK>`. compileSdk is 36; only `platforms;android-36` needs to be installed
  locally (targetSdk 35 doesn't require its own platform package).
- No emulator/device was available to actually record audio during
  scaffolding, so `AudioEngine`'s MediaCodec Opus encode path and the
  WAL-to-OGG remux in `finalizeToOgg` are unverified at runtime -- the WAL
  framing itself (the crash-safety-critical part) is covered by JVM unit
  tests in `OpusFrameWalTest`. On-device verification of the audio path is
  the first thing to do before recording real sessions.

# rubberduck

Someone to talk to to help think through your own thoughts. rubberduck is a
minimal voice-first Android capture app: record, live-transcribe, ship
sessions to voice-vault.

The recording core (crash-safe WAL, session store, foreground service) plus
live streaming transcription (AssemblyAI Universal-Streaming) and bundle
upload (to the git-backed voice-vault, pure HTTPS) are all wired up.

## Build

```
./gradlew assembleDebug
./gradlew test
```

Requires a JDK compatible with Android Gradle Plugin 8.7 (JDK 17 or 21 --
newer JDKs are not yet supported by AGP; see "Toolchain notes" below if
`./gradlew` picks the wrong one).

## Configuring secrets for the live demo

Both live transcription and bundle upload are optional at runtime -- with no
key configured, recording still works end to end (WAL -> `audio.ogg`,
session list, upload-state chip just never leaves `LOCAL`). To turn them on,
add these keys to `local.properties` (gitignored, never committed):

```properties
sdk.dir=/path/to/android-sdk
assemblyai.apiKey=<your AssemblyAI API key>
github.token=<a token with repo access to dobromirmontauk/voice-vault>
# optional overrides, default to the values below:
vault.owner=dobromirmontauk
vault.repo=voice-vault
```

Each key also has an environment-variable fallback for one-off builds
without writing a secret to disk (`local.properties` wins if both are set):

```
ASSEMBLYAI_API_KEY=... GITHUB_TOKEN=$(gh auth token) ./gradlew assembleDebug
```

`github.token` needs `repo` scope on the private `dobromirmontauk/voice-vault`
repo (a `gh auth token` from an account with access works for local testing;
use a fine-grained PAT scoped to just that repo for anything longer-lived).

## Architecture

```
com.montauk.voicecapture
├── audio/
│   ├── OpusFrameWal.kt    append-only, crash-safe log of encoded Opus packets
│   └── AudioEngine.kt     AudioRecord -> MediaCodec (Opus) -> OpusFrameWal;
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

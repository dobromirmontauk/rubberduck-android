# voice-capture-android

Minimal voice-first Android capture app: record, live-transcribe, ship sessions to voice-vault.

Tonight's scope is the **skeleton**: a real, crash-safe recording core with
stub interfaces for the parts that get wired up next (streaming
transcription, upload to the vault).

## Build

```
./gradlew assembleDebug
./gradlew test
```

Requires a JDK compatible with Android Gradle Plugin 8.7 (JDK 17 or 21 --
newer JDKs are not yet supported by AGP; see "Toolchain notes" below if
`./gradlew` picks the wrong one).

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
│   └── StreamingSttClient.kt   stub -- real-time transcription, not wired up yet
├── upload/
│   └── BundleUploader.kt       stub -- ships finished session bundles to the
│                                git-backed vault, not wired up yet
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

## What's stubbed, on purpose

- **Live transcription** (`stt/StreamingSttClient.kt`): interface + no-op
  impl only. Real implementation will stream 16kHz PCM to AssemblyAI's
  Universal-Streaming API and surface partial/final transcript events.
- **Bundle upload** (`upload/BundleUploader.kt`): interface + no-op impl
  only. Real implementation will push `audio.ogg` via the Git LFS batch API
  and the small text files via the GitHub REST contents API.

Both are wired in behind their interfaces so the real implementations are a
drop-in swap, not a refactor.

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

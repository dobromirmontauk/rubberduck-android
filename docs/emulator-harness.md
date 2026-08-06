# Local emulator test harness

Regression-tests the app end to end -- real foreground service, real
`AudioEngine`/WAL, real finalize -- on a headless Android emulator, with no
physical device and no working microphone required (plan bead vn-edu.7).
It boots its own AVD, installs the debug APK, drives a recording session
from a bundled debug fixture (see README.md's "Testing without a working
microphone" section), stops it, and asserts the finalized bundle matches
the voice-vault ingest contract.

## Files

- `scripts/emulator-smoke.sh` -- the harness itself.
- `scripts/ui_find.py` -- parses a `uiautomator dump` XML for a node by
  exact text/content-description, prints its bounds' center as `X Y`.
- `scripts/validate_meta.py` -- validates a `meta.json` against
  `voice-vault/docs/ingest-contract.md`'s required keys and shapes. Also
  runnable standalone: `validate_meta.py <meta.json> [expected-session-id]`.

## Prerequisites

- macOS (uses `/usr/libexec/java_home`, matches the rest of this repo's
  toolchain notes).
- Android SDK with `platform-tools`, `emulator`, and `cmdline-tools/latest`
  on disk. Resolved in this order: `$ANDROID_SDK_ROOT`, `$ANDROID_HOME`,
  `sdk.dir` in `local.properties`, else
  `/opt/homebrew/share/android-commandlinetools`.
- The `system-images;android-35;google_apis;arm64-v8a` system image already
  installed (`sdkmanager "system-images;android-35;google_apis;arm64-v8a"`).
  The script will not download packages itself -- see "Disk guard" below.
- JDK 21 (`/usr/libexec/java_home -v 21`) for the Gradle build.
- `python3` (used for `ui_find.py` / `validate_meta.py`; no extra
  dependencies, stdlib only).

## Running it

```
scripts/emulator-smoke.sh
```

Options:

- `--fixture-label "Marathon training"` -- pick a different bundled fixture
  by its picker label (see README.md's fixtures table). Default: `"Kitchen
  remodel"` (shortest, ~26s).
- `--record-seconds N` -- how long to actually record before stopping.
  Default `8` -- long enough to produce real WAL content, short enough to
  iterate quickly. Doesn't need to reach the fixture's full duration; a
  fixture keeps feeding audio until the file is exhausted, and recording
  continues (as silence) past that point regardless.
- `--keep-emulator` -- leave the harness emulator running after the script
  exits (success or failure) instead of shutting it down. Handy for
  poking around with `adb -s <serial> shell` afterward.

Exits nonzero with a clear message on the first failed assertion. Artifacts
(logs, the pulled bundle, the last `uiautomator` dump) land under
`build/emulator-smoke/` (gitignored, part of `build/`).

## What it does

1. **Disk guard.** Before creating a new AVD, checks free disk on `$HOME`'s
   volume and aborts with a clear message below 5GB free. Never applies to
   booting an AVD that already exists -- only to creating one.
2. **AVD.** Creates (if missing) and boots its own AVD, `vc-harness`
   (`android-35`, `google_apis`, `arm64-v8a`, `pixel_7` profile), headless
   (`-no-window -no-audio -no-boot-anim`). Reuses an already-running
   `vc-harness` instance if one is found instead of booting a second one.
3. **Build + install.** `./gradlew assembleDebug` under JDK 21, then a clean
   `adb uninstall` + `adb install -r -g` (the `-g` auto-grants
   `RECORD_AUDIO`/`POST_NOTIFICATIONS` at install time -- no permission
   dialog to drive).
4. **Drive a session via the debug fixture picker.** Launches `MainActivity`,
   long-presses "New Session" (`adb shell input swipe` held past the
   long-press threshold), taps the requested fixture label, waits for the
   new `files/sessions/<id>/` directory to appear, records for
   `--record-seconds`, then taps "STOP". Node coordinates come from parsing
   a live `uiautomator dump`, not hardcoded pixel offsets, via
   `ui_find.py`.

   **Why the UI and not the README's `adb shell am start-foreground-service
   ... RecordingService` extras path:** `RecordingService` is
   `android:exported="false"`, and empirically (android-35) the shell UID
   gets `Permission Denial: ... not exported from uid ...` trying to start
   it directly from outside the app. The fixture-picker long-press flow is
   the only externally driveable path into a recording session, so that's
   what this harness automates.
5. **WAL-intact assertion.** Pulls `audio.wal` (via `adb exec-out run-as
   <package> cat ...` -- app-private storage, works on a debuggable build
   without root) **before** tapping STOP and asserts it's non-empty. This
   has to happen before Stop: a successful finalize deletes `audio.wal`
   (`RecordingService.runFinalizeWithWatchdog`), so mid-recording is the
   only window where "WAL present and non-empty" is a meaningful check.
6. **Finalize assertion.** Polls for `meta.json` to appear for up to 90s
   after Stop (comfortably past `RecordingService`'s own 20s finalize
   watchdog + 20s tag-pump shutdown timeout). If it never appears, fails
   with a pointer to a saved logcat tail -- this is the harness's regression
   guard for the exact failure this bead's notes describe (finalize hangs:
   0-byte ogg, no meta.json, WAL left in place).
7. **Bundle assertions.** Pulls `meta.json`, `audio.ogg`, and
   `live-transcript.jsonl`, and asserts: `audio.ogg` is non-empty;
   `audio.wal` is actually gone (finalize really completed, not just
   "meta.json exists"); `meta.json` validates against the ingest contract
   via `validate_meta.py` (required keys present with the right shapes,
   `session_id` matches its directory name, `started_at` is ISO 8601 with a
   timezone offset/`Z`, `schema_version == 1`, `duration_ms > 0`).

## The `voicecap` / physical-device guard

This harness runs on the same Mac as the user's own manually-driven
`voicecap` AVD (and sometimes an attached physical phone) -- **the script
must never issue a mutating adb command against either.** Every `adb -s
$SERIAL` call that can change device state is preceded by
`assert_safe_serial`, which queries `adb -s $SERIAL emu avd name` and
aborts the whole script unless the answer is exactly `vc-harness`. A
physical device doesn't implement the `emu` console command at all, so it
fails this check the same way `voicecap` does (empty/error response, not a
match) -- it's excluded by construction, not by special-casing serial
numbers. `find_serial_for_avd` (used to locate/reuse the harness's own
emulator) only *queries* `emu avd name` against every attached serial,
which is read-only and safe to run even against `voicecap` or a real phone.

If you ever see the harness's log mention any serial other than the one it
reports having booted as `vc-harness`, that's a bug -- stop it
(`Ctrl-C`/`kill`) and file an issue rather than letting it continue.

## Cleanup

On exit (success, failure, or interrupt) the script shuts down the
`vc-harness` emulator process it booted (skipped with `--keep-emulator`).
It does not delete the `vc-harness` AVD itself -- rerunning the script
reuses it. To reclaim the disk it uses, remove it manually:
`avdmanager delete avd -n vc-harness` (never point that command at
`voicecap`).

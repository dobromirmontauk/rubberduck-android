#!/usr/bin/env bash
# Headless-emulator regression smoke test (bead vn-edu.7).
#
# Boots this harness's OWN AVD ("vc-harness", never the user's "voicecap"
# AVD -- see the guards below), installs the current debug APK, drives the
# app's debug fixture picker (long-press "New Session" -> pick a bundled
# fixture -- see README.md's "Testing without a working microphone" section;
# no real mic, no physical device needed) to start a recording session, lets
# it run a few seconds, taps STOP, and asserts:
#   1. audio.wal was non-empty (intact) partway through recording.
#   2. The finalized bundle (audio.ogg + meta.json) appears within a
#      generous timeout after Stop -- catches the known "finalize hangs"
#      failure mode (bead vn-edu.7 notes: 0-byte ogg, no meta.json, WAL
#      left in place).
#   3. meta.json validates against the voice-vault ingest contract
#      (voice-vault/docs/ingest-contract.md) via validate_meta.py.
#   4. audio.wal was cleaned up once finalize actually succeeded.
#
# Why UI-driven and not the README's `adb shell am start-foreground-service
# ... RecordingService` extras path: RecordingService is
# `android:exported="false"`, and empirically (android-35) the shell UID
# gets "Permission Denial: ... not exported from uid ..." trying to start it
# directly. The fixture-picker long-press flow is the only externally
# driveable path, so that's what this harness automates via `uiautomator
# dump` + tap/long-press coordinates (see ui_find.py) rather than hardcoded
# pixel offsets.
#
# Usage:
#   scripts/emulator-smoke.sh [--fixture-label "Kitchen remodel"] [--record-seconds N] [--keep-emulator]
#
# Exits nonzero with a clear message on any failure. Safe to run alongside
# an already-booted 'voicecap' emulator (or an attached physical device) --
# this script only ever issues mutating adb commands against a serial it has
# confirmed reports avd name "vc-harness".
set -euo pipefail

HARNESS_AVD_NAME="vc-harness"
FORBIDDEN_AVD_NAME="voicecap" # the user's AVD -- this script must NEVER touch it
PACKAGE="com.montauk.voicecapture"
ACTIVITY_COMPONENT="$PACKAGE/.ui.MainActivity"
MIN_FREE_DISK_GB=5
BOOT_TIMEOUT_SECONDS=180
FINALIZE_TIMEOUT_SECONDS=90
UI_DUMP_RETRIES=15
UI_DUMP_RETRY_DELAY_SECONDS=2

FIXTURE_LABEL="Kitchen remodel"
RECORD_SECONDS=8
KEEP_EMULATOR=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --fixture-label) FIXTURE_LABEL="$2"; shift 2 ;;
    --record-seconds) RECORD_SECONDS="$2"; shift 2 ;;
    --keep-emulator) KEEP_EMULATOR=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/build/emulator-smoke}"
mkdir -p "$OUT_DIR"

log() { echo "[emulator-smoke] $*"; }
fail() { echo "[emulator-smoke] FAIL: $*" >&2; exit 1; }

# ---------------------------------------------------------------- SDK setup
resolve_sdk_root() {
  if [ -n "${ANDROID_SDK_ROOT:-}" ]; then echo "$ANDROID_SDK_ROOT"; return; fi
  if [ -n "${ANDROID_HOME:-}" ]; then echo "$ANDROID_HOME"; return; fi
  if [ -f "$REPO_ROOT/local.properties" ]; then
    local from_props
    from_props="$(sed -n 's/^sdk\.dir=//p' "$REPO_ROOT/local.properties" | tail -1)"
    if [ -n "$from_props" ]; then echo "$from_props"; return; fi
  fi
  echo "/opt/homebrew/share/android-commandlinetools"
}

SDK_ROOT="$(resolve_sdk_root)"
[ -d "$SDK_ROOT" ] || fail "Android SDK not found at '$SDK_ROOT' -- set ANDROID_SDK_ROOT or sdk.dir in local.properties"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$SDK_ROOT/emulator:$PATH"

for tool in adb emulator avdmanager; do
  command -v "$tool" >/dev/null || fail "'$tool' not found under $SDK_ROOT"
done

# -------------------------------------------------------------- disk guard
free_gb_at() {
  # macOS df -Pk: POSIX format, KB blocks. $4 is available space in KB.
  df -Pk "$1" | tail -1 | awk '{printf "%.1f", $4/1024/1024}'
}

# --------------------------------------------------- voicecap safety guard
# Only ever allow further commands against a serial whose avd name is
# exactly $HARNESS_AVD_NAME. Called before every adb -s call that can mutate
# device state (install, shell am/run-as, emu kill). A serial that reports
# the forbidden name -- or any name other than the harness's own -- aborts
# the whole script rather than proceeding.
assert_safe_serial() {
  local serial="$1" name
  name="$(adb -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r')"
  if [ "$name" = "$FORBIDDEN_AVD_NAME" ]; then
    fail "refusing to touch serial $serial -- it is the user's '$FORBIDDEN_AVD_NAME' AVD"
  fi
  if [ "$name" != "$HARNESS_AVD_NAME" ]; then
    fail "refusing to touch serial $serial -- reports avd name '$name', not '$HARNESS_AVD_NAME'"
  fi
}

find_serial_for_avd() {
  local target="$1" serial name
  for serial in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
    name="$(adb -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r')"
    if [ "$name" = "$target" ]; then echo "$serial"; return 0; fi
  done
  return 1
}

# --------------------------------------------------------------- AVD setup
ensure_avd() {
  if avdmanager list avd 2>/dev/null | grep -q "Name: $HARNESS_AVD_NAME\$"; then
    log "AVD '$HARNESS_AVD_NAME' already exists"
    return
  fi

  local free_gb
  free_gb="$(free_gb_at "$HOME")"
  log "free disk before creating AVD: ${free_gb}GB"
  if awk -v f="$free_gb" -v min="$MIN_FREE_DISK_GB" 'BEGIN{exit !(f<min)}'; then
    fail "only ${free_gb}GB free (< ${MIN_FREE_DISK_GB}GB floor) -- aborting AVD creation rather than risk filling the disk. Free up space and re-run."
  fi

  local image="system-images;android-35;google_apis;arm64-v8a"
  local image_dir="$SDK_ROOT/system-images/android-35/google_apis/arm64-v8a"
  [ -d "$image_dir" ] || fail "system image '$image' is not installed under $SDK_ROOT -- install it manually first (sdkmanager \"$image\"); this script will not download packages"

  log "creating AVD '$HARNESS_AVD_NAME' ($image, pixel_7 profile)"
  echo "no" | avdmanager create avd -n "$HARNESS_AVD_NAME" -k "$image" -d pixel_7
}

# ------------------------------------------------------------- boot/teardown
SERIAL=""
EMULATOR_PID=""

cleanup() {
  local ec=$?
  if [ "$KEEP_EMULATOR" -eq 0 ] && [ -n "$EMULATOR_PID" ] && kill -0 "$EMULATOR_PID" 2>/dev/null; then
    log "shutting down harness emulator (pid $EMULATOR_PID)"
    if [ -n "$SERIAL" ] && assert_safe_serial "$SERIAL" 2>/dev/null; then
      adb -s "$SERIAL" emu kill >/dev/null 2>&1 || true
    fi
    wait "$EMULATOR_PID" 2>/dev/null || true
  elif [ "$KEEP_EMULATOR" -eq 1 ]; then
    log "leaving harness emulator running (--keep-emulator), serial=$SERIAL"
  fi
  exit "$ec"
}
trap cleanup EXIT

boot_harness_emulator() {
  local existing
  if existing="$(find_serial_for_avd "$HARNESS_AVD_NAME")" && [ -n "$existing" ]; then
    log "reusing already-running harness emulator, serial=$existing"
    SERIAL="$existing"
    assert_safe_serial "$SERIAL"
    return
  fi

  log "booting headless '$HARNESS_AVD_NAME'"
  emulator -avd "$HARNESS_AVD_NAME" -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect \
    >"$OUT_DIR/emulator.log" 2>&1 &
  EMULATOR_PID=$!

  local waited=0
  while [ "$waited" -lt "$BOOT_TIMEOUT_SECONDS" ]; do
    kill -0 "$EMULATOR_PID" 2>/dev/null || fail "emulator process died during boot -- see $OUT_DIR/emulator.log"
    if SERIAL="$(find_serial_for_avd "$HARNESS_AVD_NAME" || true)" && [ -n "$SERIAL" ]; then
      if [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
        break
      fi
    fi
    sleep 3
    waited=$((waited + 3))
  done
  [ -n "${SERIAL:-}" ] || fail "no adb serial for '$HARNESS_AVD_NAME' appeared within ${BOOT_TIMEOUT_SECONDS}s -- see $OUT_DIR/emulator.log"
  [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || \
    fail "'$HARNESS_AVD_NAME' did not finish booting within ${BOOT_TIMEOUT_SECONDS}s -- see $OUT_DIR/emulator.log"
  assert_safe_serial "$SERIAL"
  log "harness emulator booted, serial=$SERIAL"
  sleep 5 # settle: package manager / system services can lag boot_completed briefly
}

# ------------------------------------------------------------- build/install
build_and_install() {
  local jdk21
  jdk21="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  [ -n "$jdk21" ] || fail "JDK 21 not found via '/usr/libexec/java_home -v 21' -- required for Gradle/AGP 8.7"

  log "assembling debug APK (JDK 21)"
  (cd "$REPO_ROOT" && JAVA_HOME="$jdk21" ./gradlew -q assembleDebug)
  local apk="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
  [ -f "$apk" ] || fail "expected APK not found at $apk after assembleDebug"

  assert_safe_serial "$SERIAL"
  log "uninstalling any previous build (clean slate) and installing $apk"
  adb -s "$SERIAL" uninstall "$PACKAGE" >/dev/null 2>&1 || true
  # -g: auto-grant all requested runtime permissions (RECORD_AUDIO,
  # POST_NOTIFICATIONS) at install time -- no UI permission dance needed.
  adb -s "$SERIAL" install -r -g "$apk" >"$OUT_DIR/install.log" 2>&1 || fail "adb install failed -- see $OUT_DIR/install.log"
}

# ------------------------------------------------------------ UI automation
# uiautomator-dump-based tap/long-press helpers -- see ui_find.py's header
# for why this drives the UI instead of hitting RecordingService directly.
DUMP_LOCAL="$OUT_DIR/window_dump.xml"

dump_ui() {
  adb -s "$SERIAL" shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || return 1
  adb -s "$SERIAL" pull /sdcard/window_dump.xml "$DUMP_LOCAL" >/dev/null 2>&1
}

# find_ui_center <text>: retries dump+search up to $UI_DUMP_RETRIES times
# (the app may still be animating/cold-starting), echoes "X Y" on success.
find_ui_center() {
  local target="$1" attempt=0 coords
  while [ "$attempt" -lt "$UI_DUMP_RETRIES" ]; do
    if dump_ui && coords="$(python3 "$SCRIPT_DIR/ui_find.py" "$DUMP_LOCAL" "$target" 2>/dev/null)"; then
      echo "$coords"
      return 0
    fi
    sleep "$UI_DUMP_RETRY_DELAY_SECONDS"
    attempt=$((attempt + 1))
  done
  return 1
}

ui_tap_text() {
  local target="$1" coords
  coords="$(find_ui_center "$target")" || fail "could not find '$target' in the UI within $((UI_DUMP_RETRIES * UI_DUMP_RETRY_DELAY_SECONDS))s -- see $DUMP_LOCAL"
  log "tapping '$target' at ($coords)"
  adb -s "$SERIAL" shell input tap $coords
}

ui_long_press_text() {
  local target="$1" coords
  coords="$(find_ui_center "$target")" || fail "could not find '$target' in the UI within $((UI_DUMP_RETRIES * UI_DUMP_RETRY_DELAY_SECONDS))s -- see $DUMP_LOCAL"
  log "long-pressing '$target' at ($coords)"
  # A zero-distance swipe held for >long-press-timeout is the standard adb
  # idiom for a long press; Compose's combinedClickable treats it the same
  # as a real long-press-and-release-in-place.
  adb -s "$SERIAL" shell input swipe $coords $coords 700
}

# ---------------------------------------------------------------- session
SESSION_ID=""

discover_session_dirs() {
  adb -s "$SERIAL" shell run-as "$PACKAGE" ls -1 files/sessions 2>/dev/null | tr -d '\r' | grep -v '^$' || true
}

run_session() {
  assert_safe_serial "$SERIAL"
  local before
  before="$(discover_session_dirs)"

  log "launching app"
  adb -s "$SERIAL" shell am start -n "$ACTIVITY_COMPONENT" >"$OUT_DIR/start.log" 2>&1 || \
    fail "am start (MainActivity) failed -- see $OUT_DIR/start.log"
  sleep 3

  ui_long_press_text "New Session"
  ui_tap_text "$FIXTURE_LABEL"

  local waited=0 after
  while [ "$waited" -lt 20 ]; do
    after="$(discover_session_dirs)"
    SESSION_ID="$(comm -13 <(printf '%s\n' "$before" | sort) <(printf '%s\n' "$after" | sort) | head -1)"
    [ -n "$SESSION_ID" ] && break
    sleep 1
    waited=$((waited + 1))
  done
  [ -n "$SESSION_ID" ] || fail "no new session directory appeared under files/sessions within 20s of picking '$FIXTURE_LABEL'"
  log "session id: $SESSION_ID"

  log "recording for ${RECORD_SECONDS}s"
  sleep "$RECORD_SECONDS"

  # WAL-intact check: must run BEFORE Stop. A successful finalize deletes
  # audio.wal (RecordingService.runFinalizeWithWatchdog -> walFile.delete()
  # on success), so this is the only window where "WAL present and
  # non-empty" is a meaningful mid-recording assertion.
  local wal_local="$OUT_DIR/${SESSION_ID}.audio.wal"
  adb -s "$SERIAL" exec-out run-as "$PACKAGE" cat "files/sessions/$SESSION_ID/audio.wal" > "$wal_local" 2>/dev/null || true
  [ -s "$wal_local" ] || fail "audio.wal is empty or missing mid-recording ($wal_local) -- WAL is not intact"
  log "WAL intact mid-recording: $(wc -c < "$wal_local" | tr -d ' ') bytes"

  log "stopping recording"
  ui_tap_text "STOP"

  log "waiting up to ${FINALIZE_TIMEOUT_SECONDS}s for finalize (meta.json to appear)"
  waited=0
  while [ "$waited" -lt "$FINALIZE_TIMEOUT_SECONDS" ]; do
    if adb -s "$SERIAL" shell run-as "$PACKAGE" test -f "files/sessions/$SESSION_ID/meta.json" 2>/dev/null; then
      break
    fi
    sleep 3
    waited=$((waited + 3))
  done
  if ! adb -s "$SERIAL" shell run-as "$PACKAGE" test -f "files/sessions/$SESSION_ID/meta.json" 2>/dev/null; then
    adb -s "$SERIAL" logcat -d -t 500 > "$OUT_DIR/logcat-finalize-timeout.txt" 2>&1 || true
    fail "meta.json never appeared within ${FINALIZE_TIMEOUT_SECONDS}s of Stop -- finalize hung (this is the known failure mode from the bead's notes: 0-byte ogg, no meta.json, WAL left in place). Logcat saved to $OUT_DIR/logcat-finalize-timeout.txt"
  fi
}

# --------------------------------------------------------- pull + validate
pull_and_validate_bundle() {
  assert_safe_serial "$SERIAL"
  local dir="$OUT_DIR/$SESSION_ID"
  mkdir -p "$dir"
  for f in meta.json audio.ogg live-transcript.jsonl; do
    adb -s "$SERIAL" exec-out run-as "$PACKAGE" cat "files/sessions/$SESSION_ID/$f" > "$dir/$f" 2>/dev/null || true
  done

  [ -s "$dir/meta.json" ] || fail "meta.json is missing or empty after finalize ($dir/meta.json)"
  [ -s "$dir/audio.ogg" ] || fail "audio.ogg is missing or empty after finalize ($dir/audio.ogg)"
  log "audio.ogg: $(wc -c < "$dir/audio.ogg" | tr -d ' ') bytes"

  # A successful finalize deletes audio.wal -- if it's still there, finalize
  # didn't really complete cleanly even though meta.json showed up.
  if adb -s "$SERIAL" shell run-as "$PACKAGE" test -f "files/sessions/$SESSION_ID/audio.wal" 2>/dev/null; then
    fail "audio.wal is still present after finalize -- finalize did not clean up the WAL as expected"
  fi

  log "validating meta.json against the ingest contract (voice-vault/docs/ingest-contract.md)"
  python3 "$SCRIPT_DIR/validate_meta.py" "$dir/meta.json" "$SESSION_ID" || fail "meta.json failed ingest-contract validation (see above)"
}

main() {
  log "repo: $REPO_ROOT"
  log "SDK root: $SDK_ROOT"
  ensure_avd
  boot_harness_emulator
  build_and_install
  run_session
  pull_and_validate_bundle
  log "PASS -- session $SESSION_ID: WAL was intact mid-recording, bundle finalized and validated against the ingest contract. Artifacts under $OUT_DIR/$SESSION_ID/"
}

main

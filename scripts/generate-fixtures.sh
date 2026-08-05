#!/usr/bin/env bash
# Regenerates the long-form debug audio-injection fixtures (bead vn-edu.39)
# from their source scripts. Each fixture is a pair:
#   app/src/debug/assets/fixtures/<name>.txt  source monologue script, fed
#                                              to `say` -- doubles as the
#                                              ground truth for what was
#                                              spoken and when the embedded
#                                              [[slnc N]] pauses land.
#   app/src/debug/assets/fixtures/<name>.wav  16kHz mono 16-bit PCM WAV
#                                              generated from that script.
#
# macOS only (uses the built-in `say` and `afconvert`). Run from anywhere;
# paths are resolved relative to this script's location.
#
# Usage: scripts/generate-fixtures.sh [name ...]
#   No args: regenerates every *.txt found in the fixtures directory.
#   One or more names: regenerates just those (e.g. "dog-walk-download").

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURES_DIR="$SCRIPT_DIR/../app/src/debug/assets/fixtures"
VOICE="Samantha" # pinned so regeneration is reproducible across machines

if ! command -v say >/dev/null 2>&1 || ! command -v afconvert >/dev/null 2>&1; then
  echo "error: this script requires macOS's 'say' and 'afconvert'" >&2
  exit 1
fi

if [ "$#" -gt 0 ]; then
  names=("$@")
else
  names=()
  for txt in "$FIXTURES_DIR"/*.txt; do
    [ -e "$txt" ] || continue
    names+=("$(basename "$txt" .txt)")
  done
fi

if [ "${#names[@]}" -eq 0 ]; then
  echo "no fixture scripts (*.txt) found under $FIXTURES_DIR" >&2
  exit 1
fi

for name in "${names[@]}"; do
  txt="$FIXTURES_DIR/$name.txt"
  wav="$FIXTURES_DIR/$name.wav"
  if [ ! -f "$txt" ]; then
    echo "error: no source script $txt" >&2
    exit 1
  fi

  tmp_aiff="$(mktemp -t "$name").aiff"
  echo "generating $wav from $txt (voice: $VOICE)..."
  say -v "$VOICE" -o "$tmp_aiff" -f "$txt"
  afconvert "$tmp_aiff" "$wav" -d LEI16@16000 -c 1
  rm -f "$tmp_aiff"

  # Verify the format FileAudioSource/WavDecoder require: 16kHz mono 16-bit.
  info="$(afinfo "$wav")"
  if ! echo "$info" | grep -q "1 ch,  16000 Hz, Int16"; then
    echo "error: $wav is not 16kHz mono 16-bit PCM:" >&2
    echo "$info" >&2
    exit 1
  fi
  duration_sec="$(echo "$info" | sed -n 's/estimated duration: \([0-9.]*\) sec/\1/p')"
  size_bytes="$(stat -f%z "$wav" 2>/dev/null || stat -c%s "$wav")"
  echo "  ok: ${duration_sec}s, ${size_bytes} bytes"
done

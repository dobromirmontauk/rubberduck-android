#!/usr/bin/env python3
"""Validates a rubberduck `meta.json` against the voice-vault ingest contract
(voice-vault repo: docs/ingest-contract.md). Used by
scripts/emulator-smoke.sh, but also runnable standalone:

    validate_meta.py <path/to/meta.json> [expected-session-id]

Exits 0 and prints a one-line confirmation on success; exits 1 and lists
every violation found on failure (all checks run before failing, so a single
invocation reports everything wrong at once).
"""
from __future__ import annotations

import json
import re
import sys

# expected wire type per required key -- 'stt' is checked separately since
# its valid values are `null` or an object, not a single Python type.
REQUIRED_KEY_TYPES = {
    "session_id": str,
    "started_at": str,
    "duration_ms": int,
    "device": str,
    "app_version": str,
    "schema_version": int,
}

SESSION_ID_RE = re.compile(r"^\d{4}-\d{2}-\d{2}_\d{4}_[a-z0-9]{4}$")
# ISO 8601 with an explicit UTC/offset marker -- either literal 'Z' or a
# +HH:MM / -HH:MM suffix. The contract's own examples use "-07:00"; this app
# currently writes 'Z' (UTC), which is an equally valid offset designator.
ISO_TZ_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$")


def validate(meta: dict, expected_session_id: str | None = None) -> list[str]:
    errors = []

    if "stt" not in meta:
        errors.append("missing required key 'stt' (value may be null, but the key must be present)")
    elif meta["stt"] is not None and not isinstance(meta["stt"], dict):
        errors.append(f"'stt' must be null or an object, got {type(meta['stt']).__name__}")

    for key, expected_type in REQUIRED_KEY_TYPES.items():
        if key not in meta:
            errors.append(f"missing required key '{key}'")
            continue
        value = meta[key]
        # bool is a subclass of int in Python -- reject it explicitly so a
        # stray `true`/`false` doesn't pass an `int` check.
        if expected_type is int and isinstance(value, bool):
            errors.append(f"'{key}' must be an int, got bool")
        elif not isinstance(value, expected_type):
            errors.append(f"'{key}' must be a {expected_type.__name__}, got {type(value).__name__}")

    session_id = meta.get("session_id")
    if isinstance(session_id, str) and not SESSION_ID_RE.match(session_id):
        errors.append(f"'session_id' {session_id!r} doesn't match the contract's YYYY-MM-DD_HHMM_xxxx shape")
    if expected_session_id is not None and session_id != expected_session_id:
        errors.append(f"'session_id' {session_id!r} does not match its directory name {expected_session_id!r}")

    started_at = meta.get("started_at")
    if isinstance(started_at, str) and not ISO_TZ_RE.match(started_at):
        errors.append(f"'started_at' {started_at!r} is not ISO 8601 with a timezone offset or 'Z'")

    duration_ms = meta.get("duration_ms")
    if isinstance(duration_ms, int) and not isinstance(duration_ms, bool) and duration_ms <= 0:
        errors.append(f"'duration_ms' {duration_ms} must be positive")

    schema_version = meta.get("schema_version")
    if schema_version is not None and schema_version != 1:
        errors.append(f"'schema_version' {schema_version!r} != 1 (contract currently only defines version 1)")

    return errors


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: validate_meta.py <meta.json> [expected-session-id]", file=sys.stderr)
        return 2
    path = argv[1]
    expected_session_id = argv[2] if len(argv) > 2 else None

    try:
        with open(path) as f:
            meta = json.load(f)
    except (OSError, json.JSONDecodeError) as e:
        print(f"could not read/parse {path} as JSON: {e}", file=sys.stderr)
        return 1

    errors = validate(meta, expected_session_id)
    if errors:
        print(f"{path} FAILED ingest-contract validation:", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1

    print(f"{path} is valid per the ingest contract (session_id={meta.get('session_id')})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

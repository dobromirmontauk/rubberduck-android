# Audio write-ahead log format

## The invariant

Sessions run 30-60 minutes while the user walks or drives. The process can die at
any moment: OOM kill, battery-saver kill, a crash in an unrelated part of the app,
the phone locking and Doze deciding the foreground service isn't foreground enough.
Whatever happens, **at most the last ~1s of audio is lost, and the file on disk is
always structurally valid** up to the point of death. No "corrupt container,
whole session unrecoverable" failure mode is acceptable.

## Why not MediaMuxer/OGG as the live recording format

`MediaMuxer` (writing an OGG container incrementally) was the other option on the
table. It was rejected as the *live* format because a muxer container has global
structure that is only finalized on `stop()`/`release()`: index tables, page
continuation bits, stream end markers. If the process dies mid-write, the OGG
file can be left in a state where standard decoders refuse to read *any* of it,
not just the tail — the exact failure mode the invariant forbids.

## The chosen format: length-prefixed Opus packet WAL

Recording writes a flat, append-only file: a small fixed header, followed by a
sequence of self-describing frames. Each frame is written and flushed to the
OS in one call before the next frame starts encoding. This means:

- Every complete frame in the file is independently decodable — no dependency
  on frames written after it, or on a footer/index that hasn't been written yet.
- A crash mid-frame-write leaves at most one incomplete trailing frame, which
  the reader detects (short read at EOF) and discards. Since encoded Opus
  frames represent ~20-60ms of audio each and are flushed individually, the
  maximum loss is one frame's worth of audio — comfortably under the ~1s bound
  even accounting for OS write buffering.
- No global structure ever needs to be "closed." The file is valid to read at
  any byte offset that ends on a frame boundary, including offset zero (header
  only, empty session) and mid-recording.

### On-disk layout (`audio.wal`)

```
Header (16 bytes):
  magic       4 bytes   "VCW1"
  sampleRate  4 bytes   little-endian Int32 (Hz), e.g. 16000
  channels    1 byte    channel count, e.g. 1
  reserved    7 bytes   zero, reserved for future use (bit depth, codec id, ...)

Frame (repeated until EOF):
  length        4 bytes   little-endian Int32, byte length of payload
  timestampUs   8 bytes   little-endian Int64, encoder presentation timestamp
  payload       <length> bytes   one encoded Opus packet (as produced by
                                   MediaCodec's OMX.google.opus / c2.android.opus)
```

`OpusFrameWal` (`audio/OpusFrameWal.kt`) implements the writer and reader. It has
no Android framework dependency — it operates on plain `java.io` streams — so its
framing logic is covered by plain JVM unit tests (`OpusFrameWalTest`) without
needing Robolectric or an emulator.

## Finalization

On a clean `stop()`, `AudioEngine` reads the WAL back with `OpusFrameWal.Reader`
and remuxes the packets into a real `.ogg` (Ogg Opus) container via
`MediaMuxer`, producing the `audio.ogg` file the vault ingest contract expects.
The WAL file itself is deleted only after the muxed file is fully written and
released.

If the process dies before a clean stop, the session directory is left with
`audio.wal` present and no `audio.ogg`. `SessionStore.recoverUnfinalizedSessions()`
scans for this state on next app start and re-runs the same remux step, so a
crash costs at most the last incomplete frame — never the whole session.

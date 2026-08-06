package com.montauk.voicecapture.audio

/**
 * Fixed-byte-capacity, drop-oldest ring buffer of raw PCM chunks (bead
 * asn-r60): backs auto-pause's "keep the trailing 3-5s of audio while
 * quiet" requirement so a resume-on-speech can prepend it, without ever
 * growing unbounded while the mic stays open through a long quiet span.
 *
 * Pure Kotlin, no Android dependency -- see [CapturePauseGate], the only
 * caller, for how this fits into [AudioEngine]'s capture loop.
 *
 * Chunk-granular rather than a raw byte ring: [write] drops whole oldest
 * chunks (never splits one) once [capacityBytes] would otherwise be
 * exceeded, so [drainOrdered] always hands back intact, chronologically
 * ordered PCM frames exactly as they were captured -- simpler to feed back
 * into an encoder than reassembling arbitrary byte-offset slices would be.
 */
class PcmRingBuffer(private val capacityBytes: Int) {
    init {
        require(capacityBytes > 0) { "capacityBytes must be positive, got $capacityBytes" }
    }

    private val chunks = ArrayDeque<ByteArray>()
    private var totalBytes = 0

    /** Appends [chunk], dropping the oldest buffered chunk(s) until at/under [capacityBytes]. */
    fun write(chunk: ByteArray) {
        chunks.addLast(chunk)
        totalBytes += chunk.size
        while (totalBytes > capacityBytes && chunks.size > 1) {
            totalBytes -= chunks.removeFirst().size
        }
        // A single chunk larger than capacityBytes is kept whole rather than
        // truncated -- an oversized single read is vanishingly unlikely (PCM
        // reads are ~100ms windows against a multi-second capacity) and
        // dropping it entirely would silently discard the most recent audio,
        // exactly what this buffer exists to retain.
    }

    /** Returns every buffered chunk, oldest-first, and clears the buffer. */
    fun drainOrdered(): List<ByteArray> {
        val result = chunks.toList()
        clear()
        return result
    }

    fun clear() {
        chunks.clear()
        totalBytes = 0
    }

    fun isEmpty(): Boolean = chunks.isEmpty()

    /** Total bytes currently buffered -- exposed for tests; never exceeds [capacityBytes] except per [write]'s single-oversized-chunk note. */
    fun sizeBytes(): Int = totalBytes
}

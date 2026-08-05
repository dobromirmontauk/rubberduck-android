package com.montauk.voicecapture.audio

/**
 * Abstracts PCM16 acquisition behind start/read/stop so [AudioEngine] --
 * and everything downstream of it (the WAL, the STT tee, [MicLevelMeter],
 * [com.montauk.voicecapture.service.SilenceDetector]) -- doesn't care
 * whether frames come from a live microphone ([MicAudioSource]) or an
 * injected audio file ([FileAudioSource], debug-only; bead vn-edu.20).
 *
 * [sampleRateHz]/[channelCount] only need to be accurate once [start] has
 * returned -- [MicAudioSource] knows them upfront from its constructor
 * arguments, but [FileAudioSource] only learns them once it has decoded the
 * file's header. [AudioEngine] is configured for a fixed rate/channel count
 * (currently always 16kHz mono, matching the STT client and [MicLevelMeter]
 * hardcodes) and fails fast in [AudioEngine.start] if a source reports
 * anything else, rather than silently resampling or mis-encoding.
 */
interface AudioSource {
    val sampleRateHz: Int
    val channelCount: Int

    /** Short label for the recording screen's source chip, e.g. "MIC", "FILE". */
    val deviceLabel: String

    /**
     * The chunk size, in bytes, [AudioEngine]'s capture loop should read at a
     * time -- mirrors how real [android.media.AudioRecord] hardware buffers
     * dictate a natural read cadence, so [FileAudioSource] can pace its
     * delivery to match instead of handing over the whole file in one read.
     * Valid only once [start] has returned.
     */
    val recommendedReadBufferSize: Int

    /** Called once before the first [read]; throws if the underlying device/file can't be opened. */
    fun start()

    /**
     * Blocking read of up to [length] bytes into [buffer] starting at
     * [offset]. Returns the number of bytes actually read. Unlike raw
     * [android.media.AudioRecord.read], implementations here always return a
     * non-negative count -- there is no "error" return value in this
     * interface's contract; a source that can't produce more real audio
     * (e.g. [FileAudioSource] past end-of-file) fills the rest of the buffer
     * with silence rather than returning fewer bytes or a negative code.
     */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /** Releases any underlying device/file resources. Safe to call more than once. */
    fun stop()
}

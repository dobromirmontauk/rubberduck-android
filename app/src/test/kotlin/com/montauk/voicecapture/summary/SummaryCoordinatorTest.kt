package com.montauk.voicecapture.summary

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryCoordinatorTest {

    private class StubGenerator(private val resultsQueue: MutableList<List<String>?>) : SummaryGenerator {
        val calls = mutableListOf<Pair<String, List<String>>>()
        val discardedArgs = mutableListOf<List<String>>()

        override suspend fun generate(fullTranscript: String, previousBullets: List<String>, discardedBullets: List<String>): List<String>? {
            calls += fullTranscript to previousBullets
            discardedArgs += discardedBullets
            return if (resultsQueue.isNotEmpty()) resultsQueue.removeAt(0) else null
        }
    }

    private fun longEnough(vararg words: String): String {
        // SummaryCoordinator.MIN_TRANSCRIPT_WORDS words of padding plus the real content, so tests
        // that aren't specifically about the too-short gate don't have to think about it.
        val padding = (1..SummaryCoordinator.MIN_TRANSCRIPT_WORDS).joinToString(" ") { "word$it" }
        return "$padding ${words.joinToString(" ")}"
    }

    @Test
    fun `a keyless coordinator (null generator) never does anything`() = runBlocking {
        val coordinator = SummaryCoordinator(generator = null, intervalMs = 0L)

        val result = coordinator.onTick(nowMs = 0L, fullTranscript = longEnough("plenty of real content here"))

        assertNull(result)
    }

    @Test
    fun `first eligible tick always runs regardless of intervalMs`() = runBlocking {
        val generator = StubGenerator(mutableListOf(listOf("first bullet")))
        val coordinator = SummaryCoordinator(generator, intervalMs = 60_000L)

        val result = coordinator.onTick(nowMs = 0L, fullTranscript = longEnough("first bullet content"))

        assertEquals(listOf("first bullet"), result?.bullets)
        assertEquals(0, result?.newestIndex)
        assertFalse(result!!.stale)
        assertEquals(listOf("first bullet"), result.added)
        assertEquals(1, generator.calls.size)
    }

    @Test
    fun `a tick before intervalMs elapses is skipped even if the transcript grew`() = runBlocking {
        val generator = StubGenerator(mutableListOf(listOf("first bullet"), listOf("first bullet", "second bullet")))
        val coordinator = SummaryCoordinator(generator, intervalMs = 60_000L)
        coordinator.onTick(nowMs = 0L, fullTranscript = longEnough("first bullet content"))

        val result = coordinator.onTick(nowMs = 5_000L, fullTranscript = longEnough("first bullet content", "plus more new content"))

        assertNull(result)
        assertEquals(1, generator.calls.size)
    }

    @Test
    fun `a tick after intervalMs elapses with no transcript growth is skipped -- never calls the generator`() = runBlocking {
        val generator = StubGenerator(mutableListOf(listOf("first bullet")))
        val coordinator = SummaryCoordinator(generator, intervalMs = 60_000L)
        val transcript = longEnough("first bullet content")
        coordinator.onTick(nowMs = 0L, fullTranscript = transcript)

        val result = coordinator.onTick(nowMs = 70_000L, fullTranscript = transcript)

        assertNull(result)
        assertEquals(1, generator.calls.size)
    }

    @Test
    fun `a due tick with transcript growth calls the generator and appends new bullets`() = runBlocking {
        val generator = StubGenerator(mutableListOf(listOf("first bullet"), listOf("first bullet", "second bullet")))
        val coordinator = SummaryCoordinator(generator, intervalMs = 60_000L)
        coordinator.onTick(nowMs = 0L, fullTranscript = longEnough("first bullet content"))

        val result = coordinator.onTick(nowMs = 60_000L, fullTranscript = longEnough("first bullet content", "and second bullet content too"))

        assertEquals(listOf("first bullet", "second bullet"), result?.bullets)
        assertEquals(1, result?.newestIndex)
        assertEquals(listOf("second bullet"), result?.added)
        assertEquals(2, generator.calls.size)
    }

    @Test
    fun `a too-short transcript is skipped quietly -- never calls the generator, never flips stale`() = runBlocking {
        val generator = StubGenerator(mutableListOf(listOf("a bullet")))
        val coordinator = SummaryCoordinator(generator, intervalMs = 0L)

        val result = coordinator.onTick(nowMs = 0L, fullTranscript = "just a few words")

        assertNull(result)
        assertEquals(0, generator.calls.size)
        assertFalse(coordinator.isStale())
    }

    @Test
    fun `a blank transcript is skipped quietly`() = runBlocking {
        val generator = StubGenerator(mutableListOf(listOf("a bullet")))
        val coordinator = SummaryCoordinator(generator, intervalMs = 0L)

        val result = coordinator.onTick(nowMs = 0L, fullTranscript = "")

        assertNull(result)
        assertEquals(0, generator.calls.size)
    }

    @Test
    fun `a failed round (generator returns null) keeps the last good bullets and flips stale`() = runBlocking {
        val generator = StubGenerator(mutableListOf(listOf("first bullet"), null))
        val coordinator = SummaryCoordinator(generator, intervalMs = 0L)
        coordinator.onTick(nowMs = 0L, fullTranscript = longEnough("first bullet content"))

        val result = coordinator.onTick(nowMs = 1_000L, fullTranscript = longEnough("first bullet content", "more content that grew"))

        assertEquals(listOf("first bullet"), result?.bullets)
        assertNull(result?.newestIndex)
        assertTrue(result!!.stale)
        assertEquals(emptyList<String>(), result.added)
        assertTrue(coordinator.isStale())
    }

    @Test
    fun `a second consecutive failure is silent -- already stale, nothing changed`() = runBlocking {
        val generator = StubGenerator(mutableListOf(listOf("first bullet"), null, null))
        val coordinator = SummaryCoordinator(generator, intervalMs = 0L)
        coordinator.onTick(nowMs = 0L, fullTranscript = longEnough("first bullet content"))
        coordinator.onTick(nowMs = 1_000L, fullTranscript = longEnough("first bullet content", "more"))

        val result = coordinator.onTick(nowMs = 2_000L, fullTranscript = longEnough("first bullet content", "more", "even more"))

        assertNull(result)
        assertTrue(coordinator.isStale())
    }

    @Test
    fun `recovering after a failure clears stale and is reported even with no new bullets`() = runBlocking {
        val generator = StubGenerator(mutableListOf(listOf("first bullet"), null, listOf("first bullet")))
        val coordinator = SummaryCoordinator(generator, intervalMs = 0L)
        coordinator.onTick(nowMs = 0L, fullTranscript = longEnough("first bullet content"))
        coordinator.onTick(nowMs = 1_000L, fullTranscript = longEnough("first bullet content", "more"))

        val result = coordinator.onTick(nowMs = 2_000L, fullTranscript = longEnough("first bullet content", "more", "even more"))

        assertEquals(listOf("first bullet"), result?.bullets)
        assertNull(result?.newestIndex)
        assertFalse(result!!.stale)
        assertEquals(emptyList<String>(), result.added)
        assertFalse(coordinator.isStale())
    }

    @Test
    fun `a gratuitous rewrite from the generator never reaches the reported result`() = runBlocking {
        val generator = StubGenerator(
            mutableListOf(
                listOf("first bullet"),
                listOf("first bullet, reworded by the model", "second bullet"),
            ),
        )
        val coordinator = SummaryCoordinator(generator, intervalMs = 0L)
        coordinator.onTick(nowMs = 0L, fullTranscript = longEnough("first bullet content"))

        val result = coordinator.onTick(nowMs = 1_000L, fullTranscript = longEnough("first bullet content", "and second bullet content"))

        assertEquals(listOf("first bullet", "second bullet"), result?.bullets)
        assertEquals(listOf("second bullet"), result?.added)
    }

    @Test
    fun `currentBullets reflects the last settled state without waiting on another tick`() = runBlocking {
        val generator = StubGenerator(mutableListOf(listOf("first bullet")))
        val coordinator = SummaryCoordinator(generator, intervalMs = 0L)

        coordinator.onTick(nowMs = 0L, fullTranscript = longEnough("first bullet content"))

        assertEquals(listOf("first bullet"), coordinator.currentBullets())
    }

    // Bead asn-rrw: swipe-left discard.

    @Test
    fun `discardBullet on the current newest bullet removes it and returns true`() = runBlocking {
        val generator = StubGenerator(mutableListOf(listOf("first bullet")))
        val coordinator = SummaryCoordinator(generator, intervalMs = 0L)
        coordinator.onTick(nowMs = 0L, fullTranscript = longEnough("first bullet content"))

        val discarded = coordinator.discardBullet("first bullet")

        assertTrue(discarded)
        assertEquals(emptyList<String>(), coordinator.currentBullets())
    }

    @Test
    fun `discardBullet only ever matches the CURRENT newest bullet -- a stale text is a no-op`() = runBlocking {
        val generator = StubGenerator(mutableListOf(listOf("first bullet"), listOf("first bullet", "second bullet")))
        val coordinator = SummaryCoordinator(generator, intervalMs = 0L)
        coordinator.onTick(nowMs = 0L, fullTranscript = longEnough("first bullet content"))
        coordinator.onTick(nowMs = 1_000L, fullTranscript = longEnough("first bullet content", "and second bullet content"))

        // "first bullet" was newest a round ago -- a late/stale discard tap
        // on it must not delete the WRONG (now-older) entry.
        val discarded = coordinator.discardBullet("first bullet")

        assertFalse(discarded)
        assertEquals(listOf("first bullet", "second bullet"), coordinator.currentBullets())
    }

    @Test
    fun `discardBullet on an empty summary is a no-op`() = runBlocking {
        val coordinator = SummaryCoordinator(generator = null, intervalMs = 0L)

        assertFalse(coordinator.discardBullet("anything"))
    }

    @Test
    fun `a discarded bullet is fed to the next round's generator context and never regenerated even if the model re-proposes it`() = runBlocking {
        val generator = StubGenerator(
            mutableListOf(
                listOf("first bullet"),
                // The model ignores the discard instruction and re-proposes
                // the exact same text -- AppendOnlyBulletMerge's hard filter
                // must still keep it out.
                listOf("first bullet"),
            ),
        )
        val coordinator = SummaryCoordinator(generator, intervalMs = 0L)
        coordinator.onTick(nowMs = 0L, fullTranscript = longEnough("first bullet content"))
        assertTrue(coordinator.discardBullet("first bullet"))

        val result = coordinator.onTick(nowMs = 1_000L, fullTranscript = longEnough("first bullet content", "more new content"))

        assertEquals(listOf("first bullet"), generator.discardedArgs.last())
        // AppendOnlyBulletMerge's hard filter strips the re-proposed bullet
        // entirely -- a round that ends up adding nothing (and wasn't
        // already stale) is reported as "nothing changed," same as any other
        // no-op round (see SummaryCoordinator.onSuccessfulRound).
        assertNull(result)
        assertEquals(emptyList<String>(), coordinator.currentBullets())
    }
}

package com.montauk.voicecapture.topics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicCloudTest {

    @Test
    fun `stopwords and short words are excluded`() {
        val lines = listOf(TopicCloud.Line("the cat and the dog are so big", endMs = 0L))
        val words = TopicCloud.compute(lines, nowMs = 0L).map { it.word }
        assertTrue(words.contains("big"))
        assertTrue(words.contains("dog"))
        assertTrue(words.contains("cat"))
        // "the", "and", "are", "so" are stopwords; none should appear.
        assertTrue(words.none { it in setOf("the", "and", "are", "so") })
    }

    @Test
    fun `frequency drives ranking`() {
        val lines = listOf(
            TopicCloud.Line("kubernetes kubernetes kubernetes deployment", endMs = 0L),
            TopicCloud.Line("deployment rollback", endMs = 0L),
        )
        val topics = TopicCloud.compute(lines, nowMs = 0L)
        assertEquals("kubernetes", topics.first().word)
        assertEquals(TopicCloud.Tier.LARGE, topics.first().tier)
    }

    @Test
    fun `recent mentions are double-weighted within the 90s window`() {
        val lines = listOf(
            // "budget" said 100s ago: outside the 90s recency window, weight 1.
            TopicCloud.Line("budget review", endMs = 0L),
            // "timeline" said 10s ago: inside the window, weight 2.
            TopicCloud.Line("timeline update", endMs = 90_000L),
        )
        val topics = TopicCloud.compute(lines, nowMs = 100_000L)
        val timelineWeight = topics.first { it.word == "timeline" }.weight
        val budgetWeight = topics.first { it.word == "budget" }.weight
        assertEquals(2.0, timelineWeight, 0.0001)
        assertEquals(1.0, budgetWeight, 0.0001)
    }

    @Test
    fun `caps at 5 topics with tiers 1-2-2`() {
        val lines = listOf(TopicCloud.Line("alpha bravo charlie delta echo foxtrot golf", endMs = 0L))
        val topics = TopicCloud.compute(lines, nowMs = 0L)
        assertEquals(5, topics.size)
        assertEquals(TopicCloud.Tier.LARGE, topics[0].tier)
        assertEquals(TopicCloud.Tier.MEDIUM, topics[1].tier)
        assertEquals(TopicCloud.Tier.MEDIUM, topics[2].tier)
        assertEquals(TopicCloud.Tier.SMALL, topics[3].tier)
        assertEquals(TopicCloud.Tier.SMALL, topics[4].tier)
    }

    @Test
    fun `empty transcript yields no topics`() {
        assertEquals(0, TopicCloud.compute(emptyList(), nowMs = 0L).size)
    }
}

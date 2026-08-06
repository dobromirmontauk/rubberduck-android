package com.montauk.voicecapture.duck

import com.montauk.voicecapture.tags.DisplayedTag
import com.montauk.voicecapture.tags.TagTier
import org.junit.Assert.assertEquals
import org.junit.Test

class TopicWordCloudTopicsTest {

    @Test
    fun `PRIMARY and SECONDARY tags are confirmed (green)`() {
        val tags = listOf(
            DisplayedTag("kitchen remodel", 0.92, rank = 1, tier = TagTier.PRIMARY),
            DisplayedTag("budget", 0.6, rank = 2, tier = TagTier.SECONDARY),
        )
        val topics = TopicWordCloudTopics.fromDisplayedTags(tags)
        assertEquals(listOf("kitchen remodel", "budget"), topics.confirmed)
        assertEquals(emptyList<String>(), topics.candidates)
    }

    @Test
    fun `TERTIARY tags are candidates (white)`() {
        val tags = listOf(DisplayedTag("vendor", 0.35, rank = 3, tier = TagTier.TERTIARY))
        val topics = TopicWordCloudTopics.fromDisplayedTags(tags)
        assertEquals(emptyList<String>(), topics.confirmed)
        assertEquals(listOf("vendor"), topics.candidates)
    }

    @Test
    fun `an explicit proposal tag is a candidate even at PRIMARY-tier confidence`() {
        val tags = listOf(DisplayedTag("new topic", 0.8, rank = 1, tier = TagTier.PRIMARY, isProposal = true))
        val topics = TopicWordCloudTopics.fromDisplayedTags(tags)
        assertEquals(emptyList<String>(), topics.confirmed)
        assertEquals(listOf("new topic"), topics.candidates)
    }

    @Test
    fun `confirmed is capped at MAX_CONFIRMED and candidates at MAX_CANDIDATES`() {
        val confirmedTags = (1..5).map { DisplayedTag("confirmed-$it", 0.9, rank = it, tier = TagTier.PRIMARY) }
        val candidateTags = (1..5).map { DisplayedTag("candidate-$it", 0.4, rank = it, tier = TagTier.TERTIARY) }
        val topics = TopicWordCloudTopics.fromDisplayedTags(confirmedTags + candidateTags)
        assertEquals(TopicWordCloudTopics.MAX_CONFIRMED, topics.confirmed.size)
        assertEquals(TopicWordCloudTopics.MAX_CANDIDATES, topics.candidates.size)
    }

    @Test
    fun `empty input yields EMPTY-equivalent topics`() {
        assertEquals(TopicWordCloudTopics.EMPTY, TopicWordCloudTopics.fromDisplayedTags(emptyList()))
    }
}

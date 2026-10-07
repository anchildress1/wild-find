package dev.anchildress1.wildfind.core.verify

import dev.anchildress1.wildfind.core.tensor.FloatMatrix
import dev.anchildress1.wildfind.core.verify.LabelSet.Entry
import dev.anchildress1.wildfind.core.verify.LabelSet.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LabelSetTest {
    private val tutorial = listOf(
        "Poaceae", "Quercus", "Polypodiopsida", "Trifolium", "Pinus", "Taraxacum",
        "Toxicodendron radicans", "Toxicodendron pubescens", "Toxicodendron vernix", "Phytolacca americana",
        "Solanum carolinense",
    ).map { Entry(it, Kind.TUTORIAL, it) }
    private val words = listOf(Entry("oak", Kind.WORD, "Quercus"), Entry("fern", Kind.WORD, "Polypodiopsida"))

    private fun set(entries: List<Entry>) =
        LabelSet(entries, FloatMatrix(entries.size, 2, FloatArray(entries.size * 2)))

    @Test
    fun `words and tutorial labels with the same taxon stay separate rows`() {
        val labels = set(words + tutorial)

        assertEquals(0, labels.wordRow("oak"))
        assertEquals(1, labels.wordRow("fern"))
        assertThrows<IllegalArgumentException> { labels.wordRow("Quercus") }
    }

    @Test
    fun `goals come from the right rows`() {
        val labels = set(words + tutorial)
        val embedding = floatArrayOf(1f, 0f)

        assertEquals(1, labels.targetGoal("oak", listOf("oak", "fern"), null, null).score(embedding).rank)
        assertEquals(1, labels.tutorialGoal().score(embedding).rank)
        assertThrows<IllegalArgumentException> { labels.targetGoal("pine", listOf("oak", "fern"), null, null) }
    }

    @Test
    fun `the label set must match its vectors and carry the full tutorial set`() {
        assertThrows<IllegalArgumentException> { LabelSet(words + tutorial, FloatMatrix(1, 2, FloatArray(2))) }
        assertThrows<IllegalArgumentException> { set(words + tutorial.drop(1)) }
        assertThrows<IllegalArgumentException> { set(words + tutorial.drop(1) + Entry("Acer", Kind.TUTORIAL, "Acer")) }
        assertThrows<IllegalArgumentException> { set(words + words.take(1) + tutorial) }
    }
}

package dev.anchildress1.wildfind.core.hint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HintPromptsTest {
    @Test
    fun `tags come from the first JSON array, allowed only, in order, once each`() {
        assertEquals(
            listOf("shade", "woods edge", "fence"),
            HintPrompts.parseTags("""Sure! ["Shade", "woods edge", "car", "fence", "shade"] and ["sun"]"""),
        )
    }

    @Test
    fun `a reply without a quoted list has no tags`() {
        assertEquals(emptyList<String>(), HintPrompts.parseTags("shade, fence"))
        assertEquals(emptyList<String>(), HintPrompts.parseTags("box_2d [0, 500, 497, 1000]"))
    }

    @Test
    fun `a native box before the tag list is skipped`() {
        assertEquals(listOf("tree"), HintPrompts.parseTags("[0, 500, 497, 1000] [\"tree\"]"))
    }

    @Test
    fun `a list cut off at the token cap keeps its complete tags`() {
        assertEquals(listOf("shade"), HintPrompts.parseTags("[\"shade\", \"fen"))
    }

    @Test
    fun `single-quoted tags count`() {
        assertEquals(listOf("sun", "path"), HintPrompts.parseTags("['sun', 'path']"))
    }

    @Test
    fun `the hint prompt keeps the safety ban and the plant`() {
        val prompt = HintPrompts.levelTwo("fern", "x", emptyList())

        assertTrue("Never say a plant is safe, harmless, not poisonous, or okay to touch." in prompt)
        assertTrue("# Plant\nfern\n" in prompt)
    }

    @Test
    fun `the scene prompt lists every allowed tag`() {
        assertTrue(HintPrompts.SCENE_TAGS.all { it in HintPrompts.scene() })
    }

    @Test
    fun `the hint prompt carries the card, the tags, and the word limit`() {
        val prompt = HintPrompts.levelTwo("fern", "Ferns like shady, damp spots.", listOf("shade", "fence"))

        assertTrue("Ferns like shady, damp spots." in prompt)
        assertTrue("shade, fence" in prompt)
        assertTrue("${HintPrompts.MAX_WORDS} words or fewer" in prompt)
        assertTrue("nothing tagged" in HintPrompts.levelTwo("fern", "x", emptyList()))
    }
}

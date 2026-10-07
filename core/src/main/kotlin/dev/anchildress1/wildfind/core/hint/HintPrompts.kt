package dev.anchildress1.wildfind.core.hint

/**
 * Gemma prompts for the level-2 hint: a scene call tags the camera frame, then a hint call writes one line from the
 * fact card and those tags (PRD hint table). S35's guards check the line before a kid sees it.
 */
object HintPrompts {
    /** The fixed scene tags from the PRD. */
    val SCENE_TAGS = listOf("shade", "sun", "water", "tree", "lawn", "rocks", "fence", "path", "woods edge")

    /** Longest hint, in words (R6). */
    const val MAX_WORDS = 20

    // A bracketed list, closed or not: a reply cut off at the token cap may never reach its ']'.
    private val ARRAY = Regex("\\[([^\\[\\]]*)\\]?")
    private val QUOTED = Regex("[\"']([^\"']*)[\"']")

    /** The scene call's text, sent with the camera frame. */
    fun scene(): String = """
        |# Task
        |Tag the outdoor scene in this photo for a plant-finding game.
        |
        |# Allowed tags
        |${SCENE_TAGS.joinToString(", ")}
        |
        |# Rules
        |- Use only allowed tags, and only for things you can see.
        |- Output a JSON array of tags and nothing else, for example ["shade", "fence"].
    """.trimMargin()

    /**
     * Scene tags from a scene-call [reply]: the allowed ones, in reply order, each once.
     *
     * Reads the first bracketed list that holds a quoted string, since Gemma E2B may lead with a numeric `box_2d`.
     */
    fun parseTags(reply: String): List<String> {
        val array = ARRAY.findAll(reply).map { it.groupValues[1] }.firstOrNull { QUOTED.containsMatchIn(it) }
            ?: return emptyList()
        return QUOTED.findAll(array)
            .map { it.groupValues[1].trim().lowercase() }
            .filter { it in SCENE_TAGS }
            .distinct()
            .toList()
    }

    /**
     * The level-2 hint call's text.
     *
     * @param word the target's menu word; the model must not say it
     * @param where the fact card's where field
     * @param tags scene tags from the current camera frame
     */
    fun levelTwo(word: String, where: String, tags: List<String>): String = """
        |# Task
        |Write one hint that helps a child, age 8, find a plant outdoors.
        |
        |# Plant
        |$word
        |
        |# Fact card: where it grows
        |$where
        |
        |# What the child's camera sees
        |${tags.ifEmpty { listOf("nothing tagged") }.joinToString(", ")}
        |
        |# Rules
        |- One sentence, $MAX_WORDS words or fewer, in words an 8-year-old reads easily.
        |- Use only the fact card and what the camera sees. Add no other facts and no numbers.
        |- Never write the plant's name. Never start with "I see" or "There is".
        |- Never say a plant is safe, harmless, not poisonous, or okay to touch.
        |
        |# Output
        |The hint sentence only.
    """.trimMargin()
}

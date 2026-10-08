package dev.anchildress1.wildfind.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.wildfind.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** R1: no copy the app ships ever calls a plant safe, harmless, not poisonous, or okay to touch. */
@RunWith(AndroidJUnit4::class)
class BannedCopyTest {
    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val banned =
        Regex("""\b(safe|harmless|not poisonous|okay to touch|ok to touch)\b""", RegexOption.IGNORE_CASE)

    // Every string and every plural form, resolved the way the device shows them.
    private fun copy(): Map<String, String> {
        val strings = R.string::class.java.fields.associate { it.name to resources.getText(it.getInt(null)).toString() }
        val plurals = R.plurals::class.java.fields.flatMap { field ->
            listOf(1, 2).map { n -> "${field.name}[$n]" to resources.getQuantityText(field.getInt(null), n).toString() }
        }
        return strings + plurals
    }

    @Test
    fun noShippedCopySaysAPlantIsSafe() {
        val all = copy()
        assertTrue("found only ${all.size} strings", all.size > 50)
        assertEquals(emptyMap<String, String>(), all.filterValues { banned.containsMatchIn(it) })
    }

    @Test
    fun theHazardLineAndTheRuleAreExact() {
        assertEquals("That might be a plant we leave extra space around.", resources.getString(R.string.hazard))
        assertEquals("Look. Photograph. Leave it where it grows.", resources.getString(R.string.leave_it_rule))
    }

    @Test
    fun theCheckCatchesEveryBannedPhrase() {
        listOf("It is safe", "harmless fern", "Not poisonous!", "okay to touch").forEach {
            assertTrue(it, banned.containsMatchIn(it))
        }
    }
}

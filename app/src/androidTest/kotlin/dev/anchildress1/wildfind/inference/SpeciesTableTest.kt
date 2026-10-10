package dev.anchildress1.wildfind.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.wildfind.ui.BANNED_COPY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.sqrt

/** S08b: the bundled species table loads from the APK, lines up with its labels, and flags every hazard. */
@RunWith(AndroidJUnit4::class)
class SpeciesTableTest {
    private val bundled = BundledAssets(InstrumentationRegistry.getInstrumentation().targetContext.assets)

    @Test
    fun tableRowsMatchLabelsAndCarryTheHazards() {
        val table = bundled.speciesTable()
        val labels = bundled.speciesLabels(table)

        assertEquals(labels.size, table.rows)
        assertEquals(EMBEDDING_SIZE, table.cols)
        // Atlantic poison oak is missing upstream; make assets appends it.
        val hazards = labels.filter { it.hazard }.map { it.scientific }
        assertTrue("Toxicodendron pubescens" in hazards)
        assertTrue("Phytolacca americana" in hazards)
        assertTrue("Solanum carolinense" in hazards)
        assertTrue(hazards.all { it.startsWith("Toxicodendron ") || it in OTHER_HAZARDS })
        val lastRow = table.data.copyOfRange((table.rows - 1) * table.cols, table.rows * table.cols)
        assertTrue("appended row is not a unit vector", abs(sqrt(table.dot(table.rows - 1, lastRow)) - 1.0) < 1e-3)
    }

    @Test
    fun shippedHintsLoadForPlayableRowsOnlyAndKeepTheKidCopyRules() {
        val labels = bundled.speciesLabels(bundled.speciesTable())
        val hinted = labels.filter { it.hints.isNotEmpty() }

        assertTrue("only ${hinted.size} rows have hints", hinted.size > MIN_HINTED_ROWS)
        assertTrue("a toxic or hazard row ships hints", hinted.all { it.playable })
        assertTrue("a row ships more than its picks", hinted.all { it.hints.size <= MAX_HINTS_PER_ROW })
        assertTrue("no season hint parsed", hinted.any { row -> row.hints.any { it.season != null } })
        val banned = hinted.flatMap { it.hints }.map { it.text }.filter { BANNED_COPY.containsMatchIn(it) }
        assertEquals(emptyList<String>(), banned)
    }

    private companion object {
        const val MIN_HINTED_ROWS = 1000
        const val MAX_HINTS_PER_ROW = 8
        const val EMBEDDING_SIZE = 1024
        val OTHER_HAZARDS = setOf("Phytolacca americana", "Solanum carolinense")
    }
}

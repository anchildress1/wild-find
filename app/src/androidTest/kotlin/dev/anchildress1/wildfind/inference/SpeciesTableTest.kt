package dev.anchildress1.wildfind.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        val labels = bundled.speciesLabels()

        assertEquals(labels.size, table.rows)
        assertEquals(EMBEDDING_SIZE, table.cols)
        // Atlantic poison oak is missing upstream; make assets appends it.
        assertTrue(Species("Toxicodendron pubescens", hazard = true) in labels)
        assertTrue(Species("Phytolacca americana", hazard = true) in labels)
        assertTrue(Species("Solanum carolinense", hazard = true) in labels)
        assertTrue(
            labels.filter { it.hazard }.all {
                it.scientific.startsWith("Toxicodendron ") ||
                    it.scientific in OTHER_HAZARDS
            },
        )
        val lastRow = table.data.copyOfRange((table.rows - 1) * table.cols, table.rows * table.cols)
        assertTrue("appended row is not a unit vector", abs(sqrt(table.dot(table.rows - 1, lastRow)) - 1.0) < 1e-3)
    }

    private companion object {
        const val EMBEDDING_SIZE = 1024
        val OTHER_HAZARDS = setOf("Phytolacca americana", "Solanum carolinense")
    }
}

private typealias Species = BundledAssets.Species

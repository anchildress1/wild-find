package dev.anchildress1.wildfind.core.verify

import dev.anchildress1.wildfind.core.tensor.FloatMatrix
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.cos
import kotlin.math.sin

class ScoringTest {
    /** Unit 2-d rows at the given angles; an embedding at angle 0 scores cos(angle) against each. */
    private fun rows(vararg degrees: Double) = FloatMatrix(
        degrees.size,
        2,
        degrees.flatMap { listOf(cos(Math.toRadians(it)).toFloat(), sin(Math.toRadians(it)).toFloat()) }.toFloatArray(),
    )

    private val east = floatArrayOf(1f, 0f)

    private fun everywhere(rows: Int) = BooleanArray(rows) { true }

    @Test
    fun `hazard rank counts every species scoring above the best hazard`() {
        val table = rows(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0)
        val check = HazardCheck(table, booleanArrayOf(false, false, true, false, false, true, false), everywhere(7))

        assertEquals(3, check.rank(east).hazardRank)
        assertTrue(check.rank(east).warns)
    }

    @Test
    fun `ranking names the top species and the best hazard rows`() {
        val check = HazardCheck(rows(30.0, 10.0, 20.0, 40.0), booleanArrayOf(false, false, false, true), everywhere(4))

        assertEquals(HazardCheck.Ranking(topRow = 1, hazardRow = 3, hazardRank = 4), check.rank(east))
    }

    @Test
    fun `the top species is local, but the hazard rank still counts every row`() {
        val check = HazardCheck(
            rows(5.0, 10.0, 20.0, 30.0),
            booleanArrayOf(false, false, false, true),
            booleanArrayOf(false, true, false, false),
        )

        assertEquals(HazardCheck.Ranking(topRow = 1, hazardRow = 3, hazardRank = 4), check.rank(east))
    }

    @Test
    fun `with no local row the top species falls back to the whole table`() {
        val check = HazardCheck(rows(5.0, 10.0, 30.0), booleanArrayOf(false, false, true), BooleanArray(3))

        assertEquals(HazardCheck.Ranking(topRow = 0, hazardRow = 2, hazardRank = 3), check.rank(east))
    }

    @Test
    fun `a hazard below the top five does not warn`() {
        val check =
            HazardCheck(
                rows(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
                booleanArrayOf(false, false, false, false, false, true),
                everywhere(6),
            )

        assertEquals(6, check.rank(east).hazardRank)
        assertFalse(check.rank(east).warns)
    }

    @Test
    fun `a safe species tied with the hazard does not push it down`() {
        val check = HazardCheck(rows(10.0, 10.0), booleanArrayOf(false, true), everywhere(2))

        assertEquals(1, check.rank(east).hazardRank)
    }

    @Test
    fun `hazard check validates its table and the embedding`() {
        assertThrows<IllegalArgumentException> { HazardCheck(rows(1.0), booleanArrayOf(false), everywhere(1)) }
        assertThrows<IllegalArgumentException> { HazardCheck(rows(1.0), booleanArrayOf(true, false), everywhere(2)) }
        assertThrows<IllegalArgumentException> { HazardCheck(rows(1.0), booleanArrayOf(true), everywhere(2)) }
        assertThrows<IllegalArgumentException> {
            HazardCheck(rows(1.0), booleanArrayOf(true), everywhere(1)).rank(floatArrayOf(1f))
        }
    }

    // Rows 0-1 are oaks, 2 a maple, 3 a toxic oak, 4 a toxic holly; an embedding at 0 degrees ranks lower angles first.
    private val genera = listOf("Quercus", "Quercus", "Acer", "Quercus", "Ilex")

    private fun target(
        vararg degrees: Double,
        eligible: IntArray = intArrayOf(0, 1, 2),
        blockers: IntArray = intArrayOf(),
    ) = TargetGoal(rows(*degrees), genera, 0, eligible, blockers)

    @Test
    fun `the target passes as top-1 and reports its own score and rank`() {
        val table = rows(10.0, 30.0, 50.0, 70.0, 80.0)

        assertEquals(
            GoalScore(true, table.dot(0, east), 1),
            TargetGoal(table, genera, 0, intArrayOf(0, 1, 2), intArrayOf()).score(east),
        )
    }

    @Test
    fun `another species in the target's genus passes as a look-alike`() {
        val score = target(30.0, 10.0, 50.0, 70.0, 80.0).score(east)

        assertTrue(score.met)
        assertEquals(2, score.rank)
    }

    @Test
    fun `a species from another genus on top is no pass`() {
        assertFalse(target(30.0, 40.0, 10.0, 70.0, 80.0).score(east).met)
    }

    @Test
    fun `a toxic blocker on top is no pass, even inside the target's genus`() {
        val blockers = intArrayOf(3, 4)

        assertFalse(target(30.0, 40.0, 50.0, 70.0, 10.0, blockers = blockers).score(east).met)
        assertFalse(target(30.0, 40.0, 50.0, 10.0, 70.0, blockers = blockers).score(east).met)
        assertTrue(target(10.0, 40.0, 50.0, 30.0, 70.0, blockers = blockers).score(east).met)
    }

    @Test
    fun `the top species must lead the best blocker by the margin`() {
        val blockers = intArrayOf(3, 4)
        // cos 10 - cos 20 is 0.045, under the margin; cos 10 - cos 21 is 0.051, past it.
        assertFalse(target(10.0, 40.0, 50.0, 20.0, 80.0, blockers = blockers).score(east).met)
        assertTrue(target(10.0, 40.0, 50.0, 21.0, 80.0, blockers = blockers).score(east).met)
    }

    @Test
    fun `a tie for top-1 is no pass, even inside the target's genus`() {
        assertFalse(target(10.0, 10.0, 50.0, 70.0, 80.0).score(east).met)
        assertFalse(target(10.0, 40.0, 10.0, 70.0, 80.0).score(east).met)
    }

    @Test
    fun `only the hunt's rows compete with the target`() {
        assertTrue(target(20.0, 40.0, 50.0, 10.0, 5.0).score(east).met)
    }

    @Test
    fun `target goal validates its rows`() {
        val table = rows(1.0, 2.0, 3.0, 4.0, 5.0)
        assertThrows<IllegalArgumentException> { TargetGoal(table, genera.take(4), 0, intArrayOf(0, 2), intArrayOf()) }
        assertThrows<IllegalArgumentException> { TargetGoal(table, genera, 0, intArrayOf(1, 2), intArrayOf()) }
        assertThrows<IllegalArgumentException> { TargetGoal(table, genera, 0, intArrayOf(0, 2, 2), intArrayOf()) }
        assertThrows<IllegalArgumentException> { TargetGoal(table, genera, 0, intArrayOf(0, 2), intArrayOf(2)) }
        assertThrows<IllegalArgumentException> { TargetGoal(table, genera, 0, intArrayOf(0, 7), intArrayOf()) }
        // With no rival genus and no blocker, any plant would pass.
        assertThrows<IllegalArgumentException> { TargetGoal(table, genera, 0, intArrayOf(0, 1), intArrayOf()) }
        TargetGoal(table, genera, 0, intArrayOf(0, 1), intArrayOf(3))
    }

    @Test
    fun `grass passes the tutorial anywhere in the top three and skips the hazard row`() {
        val labels = rows(30.0, 10.0, 20.0, 40.0, 25.0)
        val all = intArrayOf(0, 1, 2, 3, 4)

        assertEquals(4, TutorialGoal(labels, 0, all).score(east).rank)
        assertFalse(TutorialGoal(labels, 0, all).score(east).met)
        assertTrue(TutorialGoal(labels, 4, all).score(east).met)
        assertFalse(TutorialGoal(labels, 4, all).checksHazards)
        assertTrue(TargetGoal(labels, genera, 0, intArrayOf(0, 2), intArrayOf()).checksHazards)
    }

    @Test
    fun `tutorial goal validates its rows`() {
        val labels = rows(1.0, 2.0)
        assertThrows<IllegalArgumentException> { TutorialGoal(labels, 0, intArrayOf(1)) }
        assertThrows<IllegalArgumentException> { TutorialGoal(labels, 0, intArrayOf(0, 7)) }
    }
}

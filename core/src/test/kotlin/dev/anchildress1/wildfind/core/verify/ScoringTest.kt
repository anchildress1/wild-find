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

    @Test
    fun `hazard rank counts every species scoring above the best hazard`() {
        val table = rows(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0)
        val check = HazardCheck(table, booleanArrayOf(false, false, true, false, false, true, false))

        assertEquals(3, check.rank(east).hazardRank)
        assertTrue(check.rank(east).warns)
    }

    @Test
    fun `ranking names the top species and the best hazard rows`() {
        val check = HazardCheck(rows(30.0, 10.0, 20.0, 40.0), booleanArrayOf(false, false, false, true))

        assertEquals(HazardCheck.Ranking(topRow = 1, hazardRow = 3, hazardRank = 4), check.rank(east))
    }

    @Test
    fun `a hazard below the top five does not warn`() {
        val check =
            HazardCheck(rows(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), booleanArrayOf(false, false, false, false, false, true))

        assertEquals(6, check.rank(east).hazardRank)
        assertFalse(check.rank(east).warns)
    }

    @Test
    fun `a safe species tied with the hazard does not push it down`() {
        val check = HazardCheck(rows(10.0, 10.0), booleanArrayOf(false, true))

        assertEquals(1, check.rank(east).hazardRank)
    }

    @Test
    fun `hazard check validates its table and the embedding`() {
        assertThrows<IllegalArgumentException> { HazardCheck(rows(1.0), booleanArrayOf(false)) }
        assertThrows<IllegalArgumentException> { HazardCheck(rows(1.0), booleanArrayOf(true, false)) }
        assertThrows<IllegalArgumentException> {
            HazardCheck(rows(1.0), booleanArrayOf(true)).rank(floatArrayOf(1f))
        }
    }

    @Test
    fun `the target passes only as top-1 above the floor and past the margin`() {
        val labels = rows(10.0, 30.0, 50.0)
        val candidates = intArrayOf(0, 1, 2)
        val top = cos(Math.toRadians(10.0))
        val gap = top - cos(Math.toRadians(30.0))

        assertEquals(GoalScore(true, labels.dot(0, east), 1), TargetGoal(labels, 0, candidates, null, null).score(east))
        assertEquals(2, TargetGoal(labels, 1, candidates, null, null).score(east).rank)
        assertFalse(TargetGoal(labels, 1, candidates, null, null).score(east).met)
        assertTrue(TargetGoal(labels, 0, candidates, top - 1e-6, null).score(east).met)
        assertFalse(TargetGoal(labels, 0, candidates, top + 1e-6, null).score(east).met)
        assertTrue(TargetGoal(labels, 0, candidates, null, gap - 1e-6).score(east).met)
        assertFalse(TargetGoal(labels, 0, candidates, null, gap + 1e-6).score(east).met)
    }

    @Test
    fun `a tie with the runner-up is not top-1`() {
        val goal = TargetGoal(rows(10.0, 10.0), 0, intArrayOf(0, 1), null, null)

        assertEquals(GoalScore(false, goal.score(east).score, 1), goal.score(east))
    }

    @Test
    fun `only candidates compete with the target`() {
        val labels = rows(20.0, 30.0, 0.0)

        assertTrue(TargetGoal(labels, 0, intArrayOf(0, 1), null, null).score(east).met)
        assertFalse(TargetGoal(labels, 0, intArrayOf(0, 1, 2), null, null).score(east).met)
    }

    @Test
    fun `target goal validates its rows`() {
        val labels = rows(1.0, 2.0)
        assertThrows<IllegalArgumentException> { TargetGoal(labels, 0, intArrayOf(1), null, null) }
        assertThrows<IllegalArgumentException> { TargetGoal(labels, 0, intArrayOf(0), null, null) }
        assertThrows<IllegalArgumentException> { TargetGoal(labels, 0, intArrayOf(0, 5), null, null) }
        // A repeated target leaves no runner-up, so any plant would pass.
        assertThrows<IllegalArgumentException> { TargetGoal(labels, 0, intArrayOf(0, 0), null, null) }
        assertThrows<IllegalArgumentException> { TargetGoal(labels, 0, intArrayOf(0, 1, 1), null, null) }
    }

    @Test
    fun `grass passes the tutorial anywhere in the top three and skips the hazard row`() {
        val labels = rows(30.0, 10.0, 20.0, 40.0, 25.0)
        val all = intArrayOf(0, 1, 2, 3, 4)

        assertEquals(4, TutorialGoal(labels, 0, all).score(east).rank)
        assertFalse(TutorialGoal(labels, 0, all).score(east).met)
        assertTrue(TutorialGoal(labels, 4, all).score(east).met)
        assertFalse(TutorialGoal(labels, 4, all).checksHazards)
        assertTrue(TargetGoal(labels, 0, all, null, null).checksHazards)
    }

    @Test
    fun `tutorial goal validates its rows`() {
        val labels = rows(1.0, 2.0)
        assertThrows<IllegalArgumentException> { TutorialGoal(labels, 0, intArrayOf(1)) }
        assertThrows<IllegalArgumentException> { TutorialGoal(labels, 0, intArrayOf(0, 7)) }
    }
}

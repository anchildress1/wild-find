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

    /** A unit embedding at [degrees]; it scores cos(angle - degrees) against each row. */
    private fun toward(degrees: Double) =
        floatArrayOf(cos(Math.toRadians(degrees)).toFloat(), sin(Math.toRadians(degrees)).toFloat())

    private fun everywhere(rows: Int) = BooleanArray(rows) { true }

    /** A North American check whose every hazard is on the floor, so locality never changes which hazard warns. */
    private fun fixed(table: FloatMatrix, hazard: BooleanArray, local: BooleanArray) =
        HazardCheck(table, hazard, hazard, local, floorAlways = true)

    @Test
    fun `hazard rank counts every species scoring above the best hazard`() {
        val table = rows(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0)
        val check = fixed(table, booleanArrayOf(false, false, true, false, false, true, false), everywhere(7))

        assertEquals(3, check.rank(east).hazardRank)
        assertTrue(check.rank(east).warns)
    }

    @Test
    fun `ranking names the top species and the best hazard rows`() {
        val check = fixed(rows(30.0, 10.0, 20.0, 40.0), booleanArrayOf(false, false, false, true), everywhere(4))

        assertEquals(HazardCheck.Ranking(topRow = 1, hazardRow = 3, hazardRank = 4), check.rank(east))
    }

    @Test
    fun `the top species is local, but the hazard rank still counts every row`() {
        val check = fixed(
            rows(5.0, 10.0, 20.0, 30.0),
            booleanArrayOf(false, false, false, true),
            booleanArrayOf(false, true, false, false),
        )

        assertEquals(HazardCheck.Ranking(topRow = 1, hazardRow = 3, hazardRank = 4), check.rank(east))
    }

    @Test
    fun `with no local row the top species falls back to the whole table`() {
        val check = fixed(rows(5.0, 10.0, 30.0), booleanArrayOf(false, false, true), BooleanArray(3))

        assertEquals(HazardCheck.Ranking(topRow = 0, hazardRow = 2, hazardRank = 3), check.rank(east))
    }

    @Test
    fun `a hazard below the top five does not warn`() {
        val check =
            fixed(
                rows(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
                booleanArrayOf(false, false, false, false, false, true),
                everywhere(6),
            )

        assertEquals(6, check.rank(east).hazardRank)
        assertFalse(check.rank(east).warns)
    }

    @Test
    fun `a safe species tied with the hazard does not push it down`() {
        val check = fixed(rows(10.0, 10.0), booleanArrayOf(false, true), everywhere(2))

        assertEquals(1, check.rank(east).hazardRank)
    }

    @Test
    fun `hazard check validates its table and the embedding`() {
        assertThrows<IllegalArgumentException> { fixed(rows(1.0), booleanArrayOf(true, false), everywhere(2)) }
        assertThrows<IllegalArgumentException> { fixed(rows(1.0), booleanArrayOf(true), everywhere(2)) }
        assertThrows<IllegalArgumentException> {
            HazardCheck(rows(1.0), booleanArrayOf(true), booleanArrayOf(true, false), everywhere(1), floorAlways = true)
        }
        assertThrows<IllegalArgumentException> {
            fixed(rows(1.0), booleanArrayOf(true), everywhere(1)).rank(floatArrayOf(1f))
        }
    }

    @Test
    fun `inside North America a floor hazard warns where it was never seen`() {
        val hazard = booleanArrayOf(false, false, true)
        val check = HazardCheck(rows(10.0, 20.0, 30.0), hazard, hazard, BooleanArray(3), floorAlways = true)

        assertEquals(HazardCheck.Ranking(topRow = 0, hazardRow = 2, hazardRank = 3), check.rank(east))
        assertTrue(check.rank(east).warns)
    }

    @Test
    fun `outside North America a floor hazard warns only where the pull named it`() {
        val table = rows(10.0, 20.0, 30.0)
        val hazard = booleanArrayOf(false, false, true)

        val unseen = HazardCheck(table, hazard, hazard, booleanArrayOf(true, true, false), floorAlways = false)
        assertEquals(HazardCheck.Ranking(topRow = 0, hazardRow = null, hazardRank = null), unseen.rank(east))
        assertFalse(unseen.rank(east).warns)
        val seen = HazardCheck(table, hazard, hazard, everywhere(3), floorAlways = false)
        assertEquals(HazardCheck.Ranking(topRow = 0, hazardRow = 2, hazardRank = 3), seen.rank(east))
        assertTrue(seen.rank(east).warns)
    }

    @Test
    fun `a contact hazard warns only where it was seen, and elsewhere ranks as an ordinary species`() {
        val table = rows(10.0, 20.0, 30.0, 40.0)
        val hazard = booleanArrayOf(true, false, false, true)
        val floor = booleanArrayOf(false, false, false, true)

        val seen = HazardCheck(table, hazard, floor, booleanArrayOf(true, true, false, false), floorAlways = true)
        assertEquals(HazardCheck.Ranking(topRow = 0, hazardRow = 0, hazardRank = 1), seen.rank(east))
        // Unseen, row 0 still outscores the floor hazard, so it counts toward the floor hazard's rank.
        val unseen = HazardCheck(table, hazard, floor, booleanArrayOf(false, true, false, false), floorAlways = true)
        assertEquals(HazardCheck.Ranking(topRow = 1, hazardRow = 3, hazardRank = 4), unseen.rank(east))
        assertTrue(unseen.rank(east).warns)
    }

    @Test
    fun `an unseen contact hazard in the top five does not warn`() {
        val check = HazardCheck(
            rows(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
            booleanArrayOf(true, false, false, false, false, true),
            booleanArrayOf(false, false, false, false, false, true),
            BooleanArray(6),
            floorAlways = true,
        )

        assertEquals(6, check.rank(east).hazardRank)
        assertFalse(check.rank(east).warns)
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
    fun `with a full-frame plant each row scores the mean of its reticle and full-frame cosines`() {
        val table = rows(10.0, 40.0, 50.0, 70.0, 80.0)
        val full = toward(30.0)
        val score = TargetGoal(table, genera, 0, intArrayOf(0, 1, 2), intArrayOf()).score(east, full)

        assertEquals((table.dot(0, east) + table.dot(0, full)) / 2, score.score, 1e-12)
        assertEquals(1, score.rank)
        assertTrue(score.met)
    }

    @Test
    fun `the mean needs the full-frame margin over the best blocker, the reticle alone the reticle margin`() {
        val blockers = intArrayOf(3, 4)
        // cos 10 - cos 20 is 0.045: past the full-frame margin, under the reticle one.
        val between = target(10.0, 40.0, 50.0, 20.0, 80.0, blockers = blockers)
        assertTrue(between.score(east, east).met)
        assertFalse(between.score(east).met)
        // cos 10 - cos 17 is 0.029, under both.
        assertFalse(target(10.0, 40.0, 50.0, 17.0, 80.0, blockers = blockers).score(east, east).met)
        assertEquals(0.034, TargetGoal.FULL_MARGIN)
        assertEquals(0.048, TargetGoal.MARGIN)
    }

    @Test
    fun `a target the reticle alone ranks under another genus passes on the mean`() {
        // On the reticle the maple at 5 degrees leads the oak at 10; the full frame at 30 degrees lifts the oak.
        val goal = target(10.0, 40.0, 5.0, 70.0, 80.0)

        assertFalse(goal.score(east).met)
        assertTrue(goal.score(east, toward(30.0)).met)
    }

    @Test
    fun `a toxic blocker within the full-frame margin of the top species blocks it`() {
        val blockers = intArrayOf(3, 4)
        val full = toward(20.0)
        // Mean scores: the oak cos 10, the blocker at 15 degrees (cos 15 + cos 5) / 2, 0.004 behind.
        assertFalse(target(10.0, 40.0, 50.0, 15.0, 80.0, blockers = blockers).score(east, full).met)
        // At 30 degrees the blocker's mean falls 0.059 behind, past the margin.
        assertTrue(target(10.0, 40.0, 50.0, 30.0, 80.0, blockers = blockers).score(east, full).met)
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

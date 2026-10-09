package dev.anchildress1.wildfind.core.hunt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HintTest {
    private val summer = Hint("Look for blue flowers in summer.", Season.SUMMER)
    private val fall = Hint("Look for red fruit in fall.", Season.FALL)
    private val allYear = Hint("It likes shade.")

    @Test
    fun `months fold into northern seasons, December joining winter`() {
        val seasons = (1..12).map { Season.of(it) }

        assertEquals(
            listOf(
                Season.WINTER, Season.WINTER, Season.SPRING, Season.SPRING, Season.SPRING, Season.SUMMER,
                Season.SUMMER, Season.SUMMER, Season.FALL, Season.FALL, Season.FALL, Season.WINTER,
            ),
            seasons,
        )
        assertThrows(IllegalArgumentException::class.java) { Season.of(13) }
    }

    @Test
    fun `a season name parses, and no name means no season`() {
        assertEquals(Season.FALL, Season.named("fall"))
        assertNull(Season.named(null))
    }

    @Test
    fun `in-season hints lead, then year-round, then off-season`() {
        val hints = listOf(summer, allYear, fall)

        assertEquals(listOf(fall, allYear, summer), hints.forMonth(10))
        assertEquals(listOf(summer, allYear, fall), hints.forMonth(7))
    }

    @Test
    fun `the limit applies after the month ordering`() {
        assertEquals(listOf(fall, allYear), listOf(summer, allYear, fall).forMonth(10, limit = 2))
    }

    @Test
    fun `a species with no hints yields none`() {
        assertEquals(emptyList<Hint>(), emptyList<Hint>().forMonth(10))
    }
}

package dev.anchildress1.wildfind.core.inat

import dev.anchildress1.wildfind.core.hunt.Sighting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RetryWindowTest {
    private var now = 1_000L
    private val window = RetryWindow { now }

    @Test
    fun `closed until a 429, then open for exactly its Retry-After`() {
        assertFalse(window.open)
        window.rateLimited(120)
        now += 119_999
        assertTrue(window.open)
        now += 1
        assertFalse(window.open)
    }

    @Test
    fun `a 429 without Retry-After waits the default, and a shorter ask never shortens a longer one`() {
        window.rateLimited(null)
        now += RetryWindow.DEFAULT_SECONDS * 1_000 - 1
        assertTrue(window.open)
        window.rateLimited(0)
        assertTrue(window.open)
        now += 1
        assertFalse(window.open)
    }

    @Test
    fun `a huge Retry-After is capped instead of overflowing into the past`() {
        window.rateLimited(Long.MAX_VALUE / 1_000)
        assertTrue(window.open)
        now += RetryWindow.MAX_SECONDS * 1_000
        assertFalse(window.open)
    }

    @Test
    fun `a pull returns its sightings, and a failure is null without opening the window`() {
        val sightings = listOf(Sighting("Quercus nigra", "water oak", 72))

        assertEquals(sightings, window.pull { Pull.Pulled(sightings) })
        assertNull(window.pull { Pull.Failed })
        assertFalse(window.open)
    }

    @Test
    fun `a 429 opens the window, and nothing goes out until it passes`() {
        var calls = 0
        val limited = {
            calls++
            Pull.RateLimited(30)
        }

        assertNull(window.pull(limited))
        assertTrue(window.open)
        assertNull(
            window.pull {
                calls++
                Pull.Pulled(emptyList())
            },
        )
        assertEquals(1, calls)

        now += 30_000
        assertEquals(
            emptyList<Sighting>(),
            window.pull {
                calls++
                Pull.Pulled(emptyList())
            },
        )
        assertEquals(2, calls)
    }
}

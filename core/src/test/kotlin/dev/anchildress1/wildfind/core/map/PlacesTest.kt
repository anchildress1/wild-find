package dev.anchildress1.wildfind.core.map

import dev.anchildress1.wildfind.core.region.RegionKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PlacesTest {
    // The real bundled file; CI runs make assets before these tests, as every build needs it.
    private val bundled by lazy {
        val file = File("../app/generated/assets/places.bin")
        check(file.isFile) { "$file missing; run make assets" }
        Places.parse(file.readBytes())
    }

    // Two names; 34,-85 holds the first and -90,179 the second, in places.bin's layout.
    private fun file(
        cells: Map<Pair<Int, Int>, Int> = mapOf((34 to -85) to 1, (-90 to 179) to 2),
        grid: Int = 181 * 360,
    ) = ByteBuffer.allocate(7 + 2 + 5 + 2 + 5 + grid * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("WFPL".toByteArray()).put(2).putShort(2)
        listOf("Inner", "Outer").forEach { putShort(it.length.toShort()).put(it.toByteArray()) }
        val start = position()
        cells.forEach { (point, value) ->
            putShort(start + ((90 - point.first) * 360 + point.second + 180) * 2, value.toShort())
        }
    }.array()

    @Test
    fun `each whole-degree point reads its own cell, and an empty cell names nothing`() {
        val places = Places.parse(file())

        assertEquals("Inner", places.nameAt(RegionKey(34, -85)))
        assertEquals("Outer", places.nameAt(RegionKey(-90, 179)))
        assertNull(places.nameAt(RegionKey(34, -84)))
    }

    @Test
    fun `the label adds the name to the whole degrees, or shows the degrees alone`() {
        assertEquals("34°N, 85°W · Georgia", Places.label(RegionKey(34, -85), "Georgia"))
        assertEquals("0°N, 30°W", Places.label(RegionKey(0, -30), null))
    }

    @Test
    fun `the bundled index names real places offline`() {
        assertEquals("Georgia", bundled.nameAt(RegionKey(34, -85)))
        assertEquals("Georgia", bundled.nameAt(RegionKey(34, -84)))
        assertEquals("California", bundled.nameAt(RegionKey(38, -121)))
        // In the country of Georgia, the state level names one of its regions.
        assertEquals("Mtskheta-Mtianeti", bundled.nameAt(RegionKey(42, 45)))
        assertNull(bundled.nameAt(RegionKey(0, -30)))
    }

    @Test
    fun `refuses another format, a short grid, or a cell past the name table`() {
        assertThrows<IllegalArgumentException> { Places.parse("NOPE".toByteArray() + file().drop(4)) }
        assertThrows<IllegalArgumentException> { Places.parse(file(cells = emptyMap(), grid = 100)) }
        assertThrows<IllegalArgumentException> { Places.parse(file(mapOf((0 to 0) to 3))) }
    }
}

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

    // One state (a 10° square with a 2° hole) inside one country (a 20° square), in places.bin's layout.
    private fun file(trailing: Int = 0): ByteArray {
        val names = listOf("Inner", "Outer").map { it.toByteArray() }
        fun square(west: Int, south: Int, side: Int) = listOf(
            west to south,
            west + side to south,
            west + side to south + side,
            west to south + side,
            west to south,
        )
        val places = listOf(
            0 to listOf(square(0, 0, 10), square(4, 4, 2)),
            1 to listOf(square(-5, -5, 20)),
        )
        val size =
            7 + names.sumOf { 2 + it.size } + 4 + places.sumOf { (_, rings) -> 5 + rings.sumOf { 4 + it.size * 4 } }
        val buffer = ByteBuffer.allocate(size + trailing).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("WFPL".toByteArray()).put(1).putShort(names.size.toShort())
        names.forEach { buffer.putShort(it.size.toShort()).put(it) }
        buffer.putInt(places.size)
        places.forEachIndexed { kind, (name, rings) ->
            buffer.put(kind.toByte()).putShort(name.toShort()).putShort(rings.size.toShort())
            rings.forEach { ring ->
                buffer.putInt(ring.size)
                ring.forEach { (x, y) -> buffer.putShort((x * 100).toShort()).putShort((y * 100).toShort()) }
            }
        }
        return buffer.array()
    }

    @Test
    fun `a point names its state first, its country outside the state, and nothing over water`() {
        val places = Places.parse(file())

        assertEquals("Inner", places.nameAt(RegionKey(2, 2)))
        assertEquals("Outer", places.nameAt(RegionKey(5, 5)))
        assertEquals("Outer", places.nameAt(RegionKey(12, -3)))
        assertNull(places.nameAt(RegionKey(30, 30)))
    }

    @Test
    fun `the label adds the name to the whole degrees, or shows the degrees alone`() {
        assertEquals("34°N, 85°W · Georgia", Places.label(RegionKey(34, -85), "Georgia"))
        assertEquals("0°N, 30°W", Places.label(RegionKey(0, -30), null))
    }

    @Test
    fun `the bundled map names real places offline`() {
        assertEquals("Georgia", bundled.nameAt(RegionKey(34, -85)))
        assertEquals("Georgia", bundled.nameAt(RegionKey(34, -84)))
        assertEquals("California", bundled.nameAt(RegionKey(38, -121)))
        assertNull(bundled.nameAt(RegionKey(0, -30)))
        // In the country of Georgia, the state level names one of its regions.
        assertEquals("Mtskheta-Mtianeti", bundled.nameAt(RegionKey(42, 45)))
    }

    @Test
    fun `refuses another format or a padded file`() {
        assertThrows<IllegalArgumentException> { Places.parse("NOPE".toByteArray() + file().drop(4)) }
        assertThrows<IllegalArgumentException> { Places.parse(file(trailing = 2)) }
    }
}

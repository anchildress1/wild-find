package dev.anchildress1.wildfind.core.map

import dev.anchildress1.wildfind.core.region.RegionKey
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline place names from `places.bin`: one cell per whole-degree point naming its Natural Earth state or province,
 * else its country, else nothing over water. Naming the crosshairs' region reads one cell, with no network call.
 */
class Places private constructor(private val names: List<String>, private val cells: ShortArray) {
    /** The state or province at [region]'s center, else its country, else null over open water. */
    fun nameAt(region: RegionKey): String? {
        val row = MAX_LAT - region.lat.coerceIn(-MAX_LAT, MAX_LAT)
        val column = Math.floorMod(region.lng + HALF_TURN, COLUMNS)
        val cell = cells[row * COLUMNS + column].toInt() and UNSIGNED_SHORT
        return if (cell == 0) null else names[cell - 1]
    }

    /** Reads `places.bin`. */
    companion object {
        private val MAGIC = "WFPL".toByteArray()
        private const val VERSION = 2
        private const val MAX_LAT = 90
        private const val HALF_TURN = 180
        private const val ROWS = 181
        private const val COLUMNS = 360
        private const val UNSIGNED_SHORT = 0xFFFF

        /** Parses [bytes]; throws [IllegalArgumentException] on another format, so a bad build never ships. */
        fun parse(bytes: ByteArray): Places {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(MAGIC.size).also(buffer::get)
            require(magic.contentEquals(MAGIC)) { "not a places file" }
            require(buffer.get().toInt() == VERSION) { "places version" }
            val names = List(buffer.short.toInt() and UNSIGNED_SHORT) {
                String(ByteArray(buffer.short.toInt() and UNSIGNED_SHORT).also(buffer::get))
            }
            require(buffer.remaining() == ROWS * COLUMNS * Short.SIZE_BYTES) { "${buffer.remaining()} grid bytes" }
            val cells = ShortArray(ROWS * COLUMNS).also { buffer.asShortBuffer().get(it) }
            require(cells.all { (it.toInt() and UNSIGNED_SHORT) <= names.size }) { "a cell names no entry" }
            return Places(names, cells)
        }

        /** The chip and Hunting-area label: `34°N, 85°W · Georgia`, or the degrees alone over open water. */
        fun label(region: RegionKey, name: String?): String = MapCamera.degrees(region) + (name?.let { " · $it" } ?: "")
    }
}

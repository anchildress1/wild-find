package dev.anchildress1.wildfind.core.map

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The built-in world map from `land.bin`: Natural Earth land rings (public domain) at two levels, so the phone draws
 * the map itself and no tile server is ever asked.
 *
 * @property levels per level, each ring as interleaved (longitude, latitude) degrees
 */
class LandMap(val levels: List<List<FloatArray>>) {
    /** Reads `land.bin`. */
    companion object {
        private val MAGIC = "WFLD".toByteArray()
        private const val VERSION = 1
        private const val SCALE = 100f

        /** Parses [bytes]; throws [IllegalArgumentException] on another format, so a bad build never ships. */
        fun parse(bytes: ByteArray): LandMap {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(MAGIC.size).also(buffer::get)
            require(magic.contentEquals(MAGIC)) { "not a land map" }
            require(buffer.get().toInt() == VERSION) { "land map version" }
            val levels = List(buffer.get().toInt()) {
                List(buffer.int) {
                    FloatArray(buffer.int * 2) { buffer.short / SCALE }
                }
            }
            require(!buffer.hasRemaining()) { "${buffer.remaining()} trailing bytes" }
            return LandMap(levels)
        }
    }
}

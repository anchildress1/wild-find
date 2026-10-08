package dev.anchildress1.wildfind.core.map

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What a `map.bin` layer draws; never a place name. */
enum class MapLayer {
    /** Closed land rings, filled. */
    LAND,

    /** Country borders, drawn heaviest. */
    BORDERS,

    /** State and province lines; area detail only. */
    STATES,
}

/**
 * The built-in world map from `map.bin`: Natural Earth land, country borders, and state lines (public domain) at two
 * detail levels ([MapCamera.level]), so the phone draws the map itself and no tile server is ever asked.
 *
 * @property layers per (layer, level), each shape as interleaved (longitude, latitude) degrees
 */
class WorldMap(val layers: Map<Pair<MapLayer, Int>, List<FloatArray>>) {
    /** The shapes of [layer] at detail [level], or none. */
    fun shapes(layer: MapLayer, level: Int): List<FloatArray> = layers[layer to level].orEmpty()

    /** Reads `map.bin`. */
    companion object {
        private val MAGIC = "WFMP".toByteArray()
        private const val VERSION = 1
        private const val SCALE = 100f
        private const val UNSIGNED = 0xFF

        /** Parses [bytes]; throws [IllegalArgumentException] on another format, so a bad build never ships. */
        fun parse(bytes: ByteArray): WorldMap {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(MAGIC.size).also(buffer::get)
            require(magic.contentEquals(MAGIC)) { "not a world map" }
            require(buffer.get().toInt() == VERSION) { "world map version" }
            val layers = List(buffer.get().toInt() and UNSIGNED) {
                val kind = MapLayer.entries.getOrNull(buffer.get().toInt() and UNSIGNED)
                requireNotNull(kind) { "unknown map layer" }
                val level = buffer.get().toInt() and UNSIGNED
                (kind to level) to List(buffer.int) { FloatArray(buffer.int * 2) { buffer.short / SCALE } }
            }
            require(!buffer.hasRemaining()) { "${buffer.remaining()} trailing bytes" }
            return WorldMap(layers.toMap())
        }
    }
}

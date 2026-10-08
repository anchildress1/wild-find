package dev.anchildress1.wildfind.core.map

import dev.anchildress1.wildfind.core.region.RegionKey
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline place names from `places.bin`: Natural Earth state/province polygons, then country polygons for the rest.
 * A lookup is point-in-polygon on the phone, so naming a place never makes a network call.
 */
class Places private constructor(private val names: List<String>, private val places: List<Place>) {
    private class Place(val name: Int, val rings: List<FloatArray>) {
        val west = rings.minOf { ring -> ring.filterIndexed { i, _ -> i % 2 == 0 }.min() }
        val east = rings.maxOf { ring -> ring.filterIndexed { i, _ -> i % 2 == 0 }.max() }
        val south = rings.minOf { ring -> ring.filterIndexed { i, _ -> i % 2 == 1 }.min() }
        val north = rings.maxOf { ring -> ring.filterIndexed { i, _ -> i % 2 == 1 }.max() }

        // Even-odd over every ring, so holes such as enclaves count as outside.
        fun contains(lng: Float, lat: Float): Boolean {
            if (lng !in west..east || lat !in south..north) return false
            var inside = false
            for (ring in rings) {
                var j = ring.size - 2
                for (i in 0 until ring.size step 2) {
                    if (crosses(ring[i], ring[i + 1], ring[j], ring[j + 1], lng, lat)) inside = !inside
                    j = i
                }
            }
            return inside
        }
    }

    /** The state or province at [region]'s center, else its country, else null over open water. */
    fun nameAt(region: RegionKey): String? {
        val lng = region.lng.toFloat()
        val lat = region.lat.toFloat()
        return places.firstOrNull { it.contains(lng, lat) }?.let { names[it.name] }
    }

    /** Reads `places.bin`. */
    companion object {
        private val MAGIC = "WFPL".toByteArray()
        private const val VERSION = 1
        private const val SCALE = 100f
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
            val places = List(buffer.int) {
                buffer.get()
                val name = buffer.short.toInt() and UNSIGNED_SHORT
                require(name < names.size) { "name $name of ${names.size}" }
                Place(
                    name,
                    List(buffer.short.toInt() and UNSIGNED_SHORT) {
                        FloatArray(buffer.int * 2) { buffer.short / SCALE }
                    },
                )
            }
            require(!buffer.hasRemaining()) { "${buffer.remaining()} trailing bytes" }
            return Places(names, places)
        }

        /** The chip and Hunting-area label: `34°N, 85°W · Georgia`, or the degrees alone over open water. */
        fun label(region: RegionKey, name: String?): String = MapCamera.degrees(region) + (name?.let { " · $it" } ?: "")
    }
}

// Whether a ray east from (lng, lat) crosses the edge from (xi, yi) to (xj, yj).
@Suppress("LongParameterList")
private fun crosses(xi: Float, yi: Float, xj: Float, yj: Float, lng: Float, lat: Float): Boolean =
    (yi > lat) != (yj > lat) && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi

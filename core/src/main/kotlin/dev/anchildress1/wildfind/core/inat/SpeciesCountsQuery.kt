package dev.anchildress1.wildfind.core.inat

import dev.anchildress1.wildfind.core.cache.CacheKey
import dev.anchildress1.wildfind.core.region.RegionKey
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.ceil

/**
 * The one iNat query a hunt sends (PRD: The iNaturalist query): plants seen near a region's center in a calendar
 * month across all years. It carries the region center, never device coordinates.
 *
 * @property region the whole-degree region; its center is the query point
 * @property month the calendar month, 1 to 12
 * @property locale the device language for common names
 * @property radiusKm [CacheKey.RADIUS_KM], or [CacheKey.WIDE_RADIUS_KM] on the widen
 */
data class SpeciesCountsQuery(val region: RegionKey, val month: Int, val locale: String, val radiusKm: Int) {
    /** The URL of [page], 1-based, up to [MAX_PAGES]. */
    fun url(page: Int): String {
        require(page in 1..MAX_PAGES) { "page $page" }
        val params = listOf(
            "lat" to region.lat,
            "lng" to region.lng,
            "radius" to radiusKm,
            "month" to month,
            "iconic_taxa" to "Plantae",
            "quality_grade" to "research",
            "locale" to locale,
            "per_page" to PER_PAGE,
            "page" to page,
        )
        // The Charset overload is API 33; minSdk is 30.
        return BASE +
            params.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode("$value", "UTF-8")}" }
    }

    /** iNat etiquette and page limits from PRD R2. */
    companion object {
        /** species_counts endpoint. */
        const val BASE = "https://api.inaturalist.org/v1/observations/species_counts?"

        /** iNat's largest page. */
        const val PER_PAGE = 500

        /** At most three requests per query. */
        const val MAX_PAGES = 3

        /** Names the app to iNat, as its API recommendations ask. */
        const val USER_AGENT = "wild-find/0.1 (+https://github.com/anchildress1/wild-find)"

        /** Pages a query with [totalResults] needs, capped at [MAX_PAGES]. */
        fun pages(totalResults: Int): Int = ceil(totalResults / PER_PAGE.toDouble()).toInt().coerceIn(1, MAX_PAGES)

        /** Seconds a 429's `Retry-After` asks for, as delay seconds or an HTTP date read against [now], or null. */
        fun retryAfterSeconds(header: String?, now: Instant): Long? {
            val value = header?.trim() ?: return null
            return value.toLongOrNull()?.takeIf { it >= 0 } ?: httpDate(value)?.let {
                Duration.between(now, it).seconds.coerceAtLeast(0)
            }
        }

        private fun httpDate(value: String): Instant? = try {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

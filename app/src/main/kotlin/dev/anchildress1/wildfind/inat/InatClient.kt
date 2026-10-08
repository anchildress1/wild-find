package dev.anchildress1.wildfind.inat

import dev.anchildress1.wildfind.core.hunt.Sighting
import dev.anchildress1.wildfind.core.inat.SpeciesCountsQuery
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * The app's only network call: one iNat species_counts query, up to three pages (R2, R7).
 *
 * Blocking; call it off the main thread.
 *
 * @param fetch one GET; the default opens an HTTPS connection with the app's User-Agent
 */
class InatClient(private val fetch: (String) -> Response = ::get) {
    /**
     * One HTTP response.
     *
     * @property code the status code
     * @property retryAfter the raw `Retry-After` header, if any
     * @property body the response body; empty unless the code is 200
     */
    data class Response(val code: Int, val retryAfter: String?, val body: String)

    /** What one query returned. */
    sealed interface Pull {
        /**
         * Every species the query returned.
         *
         * @property sightings one per taxon, any rank; only species ever match the table
         */
        data class Pulled(val sightings: List<Sighting>) : Pull

        /**
         * iNat answered 429.
         *
         * @property retryAfterSeconds the wait it asked for, or null when it gave none in seconds
         */
        data class RateLimited(val retryAfterSeconds: Long?) : Pull

        /**
         * No usable answer: no signal, a server error, or a malformed body.
         *
         * @property reason what went wrong, for the debug log
         * @property cause the exception behind it, if any
         */
        data class Failed(val reason: String, val cause: Throwable? = null) : Pull
    }

    /** Runs [query], fetching only the pages its first page's total needs. */
    fun pull(query: SpeciesCountsQuery): Pull {
        val sightings = mutableListOf<Sighting>()
        var pages = 1
        var page = 1
        var stop: Pull? = null
        while (stop == null && page <= pages) {
            when (val step = page(query, page)) {
                is Page -> {
                    if (page == 1) pages = SpeciesCountsQuery.pages(step.total)
                    sightings += step.sightings
                }

                is Stop -> stop = step.pull
            }
            page++
        }
        return stop ?: Pull.Pulled(sightings)
    }

    private fun page(query: SpeciesCountsQuery, page: Int): Step {
        val response = try {
            fetch(query.url(page))
        } catch (e: IOException) {
            return Stop(Pull.Failed("page $page: no response", e))
        }
        return when (response.code) {
            HttpURLConnection.HTTP_OK -> parse(response.body, page)
            HTTP_TOO_MANY_REQUESTS -> Stop(Pull.RateLimited(SpeciesCountsQuery.retryAfterSeconds(response.retryAfter)))
            else -> Stop(Pull.Failed("page $page: HTTP ${response.code}"))
        }
    }

    private fun parse(body: String, page: Int): Step = try {
        val json = JSONObject(body)
        val results = json.getJSONArray("results")
        Page(
            json.optInt("total_results"),
            List(results.length()) { i ->
                val result = results.getJSONObject(i)
                val taxon = result.getJSONObject("taxon")
                val common = taxon.optString("preferred_common_name").takeIf { it.isNotBlank() }
                Sighting(taxon.getString("name"), common, result.getInt("count"))
            },
        )
    } catch (e: JSONException) {
        Stop(Pull.Failed("page $page: malformed body", e))
    }

    private sealed interface Step

    private class Page(val total: Int, val sightings: List<Sighting>) : Step

    private class Stop(val pull: Pull) : Step

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val TIMEOUT_MS = 15_000

        fun get(url: String): Response {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            return try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("User-Agent", SpeciesCountsQuery.USER_AGENT)
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                val body = if (code ==
                    HttpURLConnection.HTTP_OK
                ) {
                    connection.inputStream.bufferedReader().readText()
                } else {
                    ""
                }
                Response(code, connection.getHeaderField("Retry-After"), body)
            } finally {
                connection.disconnect()
            }
        }
    }
}

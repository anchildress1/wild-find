package dev.anchildress1.wildfind.inat

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anchildress1.wildfind.core.cache.CacheKey
import dev.anchildress1.wildfind.core.hunt.Sighting
import dev.anchildress1.wildfind.core.inat.SpeciesCountsQuery
import dev.anchildress1.wildfind.core.region.RegionKey
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** S33: the client parses iNat's species_counts shape and stops at the pages it needs; org.json needs the phone. */
@RunWith(AndroidJUnit4::class)
class InatClientTest {
    private val query = SpeciesCountsQuery(RegionKey(34, -85), 10, "en", CacheKey.RADIUS_KM)
    private val asked = mutableListOf<String>()

    private fun page(total: Int, vararg results: String) = InatClient.Response(
        200,
        null,
        """{"total_results": $total, "page": 1, "per_page": 500, "results": [${results.joinToString(",")}]}""",
    )

    private fun result(name: String, common: String?, count: Int) =
        """{"count": $count, "taxon": {"id": 1, "name": "$name", "rank": "species"""" +
            (common?.let { ""","preferred_common_name": "$it"""" } ?: "") + "}}"

    private fun client(vararg responses: InatClient.Response): InatClient {
        asked.clear()
        return InatClient { url ->
            asked += url
            responses[asked.size - 1]
        }
    }

    @Test
    fun oneShortPageIsOneRequest() {
        val pull = client(page(2, result("Quercus nigra", "water oak", 72), result("Carex", null, 9))).pull(query)

        assertEquals(
            InatClient.Pull.Pulled(listOf(Sighting("Quercus nigra", "water oak", 72), Sighting("Carex", null, 9))),
            pull,
        )
        assertEquals(listOf(query.url(1)), asked)
    }

    @Test
    fun aLongListPullsThreePagesAndNoMore() {
        val pull = client(
            page(1038, result("A a", "a", 3)),
            page(1038, result("B b", "b", 2)),
            page(1038, result("C c", "c", 1)),
        ).pull(query)

        assertEquals(3, (pull as InatClient.Pull.Pulled).sightings.size)
        assertEquals(listOf(query.url(1), query.url(2), query.url(3)), asked)
    }

    @Test
    fun rateLimitsErrorsAndBadBodiesStopThePull() {
        assertEquals(InatClient.Pull.RateLimited(30), client(InatClient.Response(429, "30", "")).pull(query))
        assertEquals(
            InatClient.Pull.Failed,
            client(page(900, result("A a", "a", 3)), InatClient.Response(503, null, "")).pull(query),
        )
        assertEquals(InatClient.Pull.Failed, client(InatClient.Response(200, null, "<html>")).pull(query))
        assertEquals(InatClient.Pull.Failed, client(InatClient.Response(200, null, "{}")).pull(query))
        assertEquals(InatClient.Pull.Failed, InatClient { throw IOException("offline") }.pull(query))
    }
}

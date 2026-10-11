package dev.anchildress1.wildfind.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.wildfind.core.cache.CacheKey
import dev.anchildress1.wildfind.core.hunt.ActiveHunt
import dev.anchildress1.wildfind.core.hunt.AppFlags
import dev.anchildress1.wildfind.core.hunt.AreaCacher
import dev.anchildress1.wildfind.core.hunt.Eligible
import dev.anchildress1.wildfind.core.hunt.HuntPick
import dev.anchildress1.wildfind.core.hunt.HuntProgress
import dev.anchildress1.wildfind.core.hunt.LocalListResult
import dev.anchildress1.wildfind.core.hunt.LocalListSource
import dev.anchildress1.wildfind.core.hunt.LocalSpecies
import dev.anchildress1.wildfind.core.hunt.Sighting
import dev.anchildress1.wildfind.core.hunt.SpeciesRow
import dev.anchildress1.wildfind.core.region.RegionKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** S37, S38: flags, the hunt, and cached pulls survive a new store over the same files; org.json needs the phone. */
@RunWith(AndroidJUnit4::class)
class GameStoreTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "game-store-test")
    private val store get() = GameStore(dir)
    private val key = CacheKey("abc123def456", RegionKey(34, -85), "en", 10, CacheKey.RADIUS_KM)
    private val hunt = ActiveHunt(
        HuntProgress(
            tutorialPending = false,
            targets = listOf(Eligible(4, "water oak", 72), Eligible(9, "sweetgum", 183)),
            found = setOf(9),
            queue = listOf(Eligible(12, "redbud", 76)),
        ),
        RegionKey(34, -85),
        eligible = listOf(4, 9, 12),
        blockers = listOf(2, 30),
    )

    // A run killed before @After leaves its files behind for the next run to trip on.
    @Before
    @After
    fun clean() {
        dir.deleteRecursively()
    }

    @Test
    fun flagsDefaultUnsetAndRoundTrip() {
        assertEquals(AppFlags(), store.flags())
        store.save(AppFlags(openerSeen = true, tutorialDone = false, region = RegionKey(-34, 151)))
        assertEquals(AppFlags(openerSeen = true, tutorialDone = false, region = RegionKey(-34, 151)), store.flags())
        store.save(AppFlags(openerSeen = true, tutorialDone = true, locationDenied = true))
        assertEquals(AppFlags(openerSeen = true, tutorialDone = true, locationDenied = true), store.flags())
        store.save(AppFlags(foundSpecies = setOf("Quercus nigra", "Acer rubrum")))
        assertEquals(AppFlags(foundSpecies = setOf("Quercus nigra", "Acer rubrum")), store.flags())
    }

    @Test
    fun flagsSavedBeforeFoundSpeciesWereKeptStillLoad() {
        dir.mkdirs()
        File(dir, "flags.json").writeText(
            """{"opener_seen":true,"tutorial_done":true,"region":"34_-85","location_denied":false}""",
        )

        assertEquals(AppFlags(openerSeen = true, tutorialDone = true, region = RegionKey(34, -85)), store.flags())
    }

    @Test
    fun theHuntRoundTripsOnlyForItsOwnTable() {
        store.save(hunt, "abc123def456")

        assertEquals(hunt, store.hunt("abc123def456"))
        assertNull(store.hunt("000000000000"))
        store.clearHunt()
        assertNull(store.hunt("abc123def456"))
    }

    @Test
    fun aCachedPullServesOnlyItsKey() {
        val sightings = listOf(Sighting("Quercus nigra", "water oak", 72), Sighting("Carex", null, 9))
        assertTrue(store.cache(key, sightings))

        assertEquals(sightings, store.cached(key))
        assertNull(store.cached(key.copy(month = 11)))
        assertNull(store.cached(key.copy(tableVersion = "000000000000")))
    }

    @Test
    fun concurrentRefreshesKeepEverySavedCacheReadableAndWhole() {
        val shared = store
        val answers = List(8) { writer ->
            List(64) { row -> Sighting("Species $writer $row", "$writer".repeat(200), row + 1) }
        }
        assertTrue(shared.cache(key, answers.first()))
        val ready = CountDownLatch(answers.size)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(answers.size)
        try {
            val results = answers.map { sightings ->
                workers.submit<Boolean> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    (1..20).all {
                        shared.cache(key, sightings) && shared.cached(key) in answers
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            results.forEach {
                assertTrue("a concurrent refresh lost or corrupted its cache", it.get(20, TimeUnit.SECONDS))
            }
            assertTrue(shared.cached(key) in answers)
        } finally {
            start.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun aCorruptFileReadsAsMissingAndIsDropped() {
        dir.mkdirs()
        File(dir, "flags.json").writeText("{not json")
        File(dir, "hunt.json").writeText("""{"table_version": "abc123def456"}""")

        assertEquals(AppFlags(), store.flags())
        assertNull(store.hunt("abc123def456"))
        assertEquals(false, File(dir, "hunt.json").exists())
    }

    @Test
    fun aFailedSaveDoesNotThrow() {
        // A plain file where the cache folder belongs makes every cache write fail, like a full disk.
        dir.mkdirs()
        File(dir, "inat").writeText("not a folder")

        assertFalse(store.cache(key, listOf(Sighting("Quercus nigra", "water oak", 72))))
        store.save(AppFlags(openerSeen = true))
        assertNull(store.cached(key))
        assertEquals(AppFlags(openerSeen = true), store.flags())
    }

    @Test
    fun aFailedRefreshKeepsThePreviousCachedAnswer() {
        val old = listOf(Sighting("Quercus nigra", "water oak", 72))
        assertTrue(store.cache(key, old))
        val temp = File(dir, "inat/${key.region}_${key.locale}_${key.month}_${key.radiusKm}.json.tmp")
        assertTrue(temp.mkdirs())
        File(temp, "blocks-write").writeText("keep")

        assertFalse(store.cache(key, listOf(Sighting("Acer rubrum", "red maple", 10))))
        assertEquals(old, store.cached(key))
    }

    @Test
    fun failedDiskWritesStopOfflineCachingButLeaveAnOnlineHuntPlayable() {
        dir.mkdirs()
        File(dir, "inat").writeText("not a folder")
        val rows = listOf("Quercus", "Acer", "Cercis").map {
            SpeciesRow("$it species", it, hazard = false, toxic = false)
        }
        val sightings = rows.map { Sighting(it.scientific, it.genus.lowercase(), 10) }
        val species = LocalSpecies(rows) { scientific -> rows.indexOfFirst { it.scientific == scientific } }
        val source = LocalListSource(species, pull = { sightings }, cached = store::cached, save = store::cache)
        val progress = mutableListOf<Int>()

        val saved = AreaCacher(source).cache(
            key.tableVersion,
            key.region,
            key.locale,
            pause = {},
            progress = { progress += it },
        )
        val playable = source.load(key.tableVersion, key.region, key.locale, key.month)

        assertEquals(0, saved)
        assertTrue(progress.isEmpty())
        assertNull(store.cached(key))
        assertEquals(LocalListResult.Ready(species.of(sightings)), playable)
    }

    @Test
    fun restoredHuntsKeepFindsAndVerificationOnlySpecies() {
        val rows = listOf("Quercus", "Acer", "Cercis", "Carex").mapIndexed { row, genus ->
            SpeciesRow("$genus species", genus, hazard = false, toxic = false, target = row != 3)
        }
        val local = LocalSpecies(rows) { scientific -> rows.indexOfFirst { it.scientific == scientific } }
            .of(rows.map { Sighting(it.scientific, it.genus.lowercase(), 10) })
        val started = ActiveHunt.start(HuntPick(rows).next(local, tutorialDone = true), local, key.region)
        val saved = started.copy(progress = started.progress.targetFound(started.progress.targets[0].row))
        assertTrue(store.save(saved, key.tableVersion))

        val restored = store.hunt(key.tableVersion)!!

        assertEquals(saved, restored)
        assertTrue(restored.validFor(key.region, rows))
        assertEquals(setOf(0, 1, 2, 3), restored.local)
        assertEquals(1, restored.progress.stars)
        assertTrue(restored.progress.targets.none { it.row == 3 })
    }
}

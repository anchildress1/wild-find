package dev.anchildress1.wildfind.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.wildfind.core.cache.CacheKey
import dev.anchildress1.wildfind.core.hunt.ActiveHunt
import dev.anchildress1.wildfind.core.hunt.AppFlags
import dev.anchildress1.wildfind.core.hunt.Eligible
import dev.anchildress1.wildfind.core.hunt.HuntProgress
import dev.anchildress1.wildfind.core.hunt.Sighting
import dev.anchildress1.wildfind.core.region.RegionKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
        store.cache(key, sightings)

        assertEquals(sightings, store.cached(key))
        assertNull(store.cached(key.copy(month = 11)))
        assertNull(store.cached(key.copy(tableVersion = "000000000000")))
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

        store.cache(key, listOf(Sighting("Quercus nigra", "water oak", 72)))
        store.save(AppFlags(openerSeen = true))
        assertNull(store.cached(key))
        assertEquals(AppFlags(openerSeen = true), store.flags())
    }
}

package dev.anchildress1.wildfind.store

import android.util.Log
import dev.anchildress1.wildfind.core.cache.CacheEntry
import dev.anchildress1.wildfind.core.cache.CacheKey
import dev.anchildress1.wildfind.core.hunt.ActiveHunt
import dev.anchildress1.wildfind.core.hunt.AppFlags
import dev.anchildress1.wildfind.core.hunt.Eligible
import dev.anchildress1.wildfind.core.hunt.HuntProgress
import dev.anchildress1.wildfind.core.hunt.Sighting
import dev.anchildress1.wildfind.core.region.RegionKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * The only state wild-find keeps on the phone: the fixed app flags, the current hunt, and cached iNat pulls
 * (H2, R8). No photo is ever stored. Every file is written whole through a temp file, so a kill mid-write keeps
 * the last good copy.
 *
 * @param dir the app's no-backup files directory, so nothing reaches a cloud backup
 */
class GameStore(private val dir: File) {
    /** The saved flags, or defaults. */
    fun flags(): AppFlags = parse("flags.json") {
        AppFlags(
            it.getBoolean("opener_seen"),
            it.getBoolean("tutorial_done"),
            if (it.isNull("region")) null else region(it.getString("region")),
        )
    } ?: AppFlags()

    /** Saves [flags]. */
    fun save(flags: AppFlags) = write(
        "flags.json",
        JSONObject()
            .put("opener_seen", flags.openerSeen)
            .put("tutorial_done", flags.tutorialDone)
            .put("region", flags.region?.toString() ?: JSONObject.NULL),
    )

    /** The hunt saved under [tableVersion], or null: another table build moved its rows. */
    fun hunt(tableVersion: String): ActiveHunt? = parse(HUNT) {
        if (it.getString("table_version") != tableVersion) return@parse null
        val targets = it.getJSONArray("targets").species()
        ActiveHunt(
            HuntProgress(
                it.getBoolean("tutorial_pending"),
                targets,
                it.getJSONArray("found").ints().toSet(),
                it.getJSONArray("queue").species(),
            ),
            region(it.getString("region")),
            it.getJSONArray("eligible").ints(),
            it.getJSONArray("blockers").ints(),
        )
    }

    /** Saves [hunt] against [tableVersion]. */
    fun save(hunt: ActiveHunt, tableVersion: String) = write(
        HUNT,
        JSONObject()
            .put("table_version", tableVersion)
            .put("region", hunt.region.toString())
            .put("tutorial_pending", hunt.progress.tutorialPending)
            .put("targets", species(hunt.progress.targets))
            .put("queue", species(hunt.progress.queue))
            .put("found", JSONArray(hunt.progress.found.sorted()))
            .put("eligible", JSONArray(hunt.eligible))
            .put("blockers", JSONArray(hunt.blockers)),
    )

    /** Forgets the current hunt, after Hunt Again or Home. */
    fun clearHunt() {
        val file = File(dir, HUNT)
        if (file.exists() && !file.delete()) Log.w(TAG, "can't delete $file")
    }

    /** The pull cached under exactly [key], or null. */
    fun cached(key: CacheKey): List<Sighting>? = parse(cacheFile(key), ::entry)?.sightingsFor(key)

    /** Caches [sightings] under [key]. */
    fun cache(key: CacheKey, sightings: List<Sighting>) = write(
        cacheFile(key),
        JSONObject()
            .put("schema_version", key.schemaVersion)
            .put("table_version", key.tableVersion)
            .put("region", key.region.toString())
            .put("locale", key.locale)
            .put("month", key.month)
            .put("radius_km", key.radiusKm)
            .put(
                "sightings",
                JSONArray(
                    sightings.map {
                        JSONObject().put("scientific", it.scientific).put("common", it.common ?: JSONObject.NULL)
                            .put("count", it.count)
                    },
                ),
            ),
    )

    // A corrupt or old-format file reads as missing and is deleted, so the app refetches or starts fresh.
    private fun <T> parse(name: String, decode: (JSONObject) -> T?): T? {
        val file = File(dir, name).takeIf { it.isFile } ?: return null
        return runCatching {
            decode(JSONObject(file.readText()))
        }.onFailure { if (!file.delete()) Log.w(TAG, "can't delete unreadable $file") }.getOrNull()
    }

    // A failed save (a full disk) costs only the saved copy, never the hunt in play, so it logs instead of throwing.
    private fun write(name: String, json: JSONObject) {
        val file = File(dir, name)
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) throw IOException("can't replace $file")
        } catch (e: IOException) {
            Log.w(TAG, "can't save $file", e)
            if (temp.exists() && !temp.delete()) Log.w(TAG, "can't remove $temp")
        }
    }

    private companion object {
        const val HUNT = "hunt.json"
        const val TAG = "GameStore"
    }
}

// One file per region, locale, month, and radius; the key check inside still discards a stale schema or table.
private fun cacheFile(key: CacheKey) = "inat/${key.region}_${key.locale}_${key.month}_${key.radiusKm}.json"

private fun region(key: String): RegionKey = key.split('_').map(String::toInt).let { (lat, lng) -> RegionKey(lat, lng) }

private fun entry(json: JSONObject): CacheEntry {
    val key = CacheKey(
        json.getString("table_version"),
        region(json.getString("region")),
        json.getString("locale"),
        json.getInt("month"),
        json.getInt("radius_km"),
        json.getInt("schema_version"),
    )
    val sightings = json.getJSONArray("sightings").objects().map {
        Sighting(
            it.getString("scientific"),
            if (it.isNull("common")) null else it.getString("common"),
            it.getInt("count"),
        )
    }
    return CacheEntry(key, sightings)
}

private fun JSONArray.objects() = List(length(), ::getJSONObject)

private fun JSONArray.ints() = List(length(), ::getInt)

private fun JSONArray.species() = objects().map {
    Eligible(it.getInt("row"), it.getString("common"), it.getInt("count"))
}

private fun species(list: List<Eligible>) =
    JSONArray(list.map { JSONObject().put("row", it.row).put("common", it.common).put("count", it.count) })

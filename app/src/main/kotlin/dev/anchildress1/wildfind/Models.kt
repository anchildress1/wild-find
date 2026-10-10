package dev.anchildress1.wildfind

import dev.anchildress1.wildfind.camera.CaptureVerifier
import dev.anchildress1.wildfind.core.hunt.NameIndex
import dev.anchildress1.wildfind.core.region.NorthAmerica
import dev.anchildress1.wildfind.core.region.RegionKey
import dev.anchildress1.wildfind.core.verify.FrameVerifier
import dev.anchildress1.wildfind.core.verify.HazardCheck
import dev.anchildress1.wildfind.inference.BundledAssets
import dev.anchildress1.wildfind.inference.ImageEncoder

/**
 * Everything the APK carries, loaded once per process off the main thread; the encoders idle between captures.
 *
 * @param assets the bundled files
 */
class Models(assets: BundledAssets) {
    /** BioCLIP species table. */
    val table = assets.speciesTable()

    /** One row per [table] row. */
    val rows = assets.speciesLabels(table)

    /** Each row's genus, for verify row 4. */
    val genus = rows.map { it.genus }

    /** The cache and saved-hunt table version. */
    val tableVersion = assets.tableVersion()

    /** iNat name to table row. */
    val names = NameIndex(rows)

    /** The grass tutorial's goal (R3). */
    val tutorial = assets.labels().tutorialGoal()

    private val gate = assets.plantGate()
    private val gateEncoder = ImageEncoder(assets.plantGateModel())
    private val bioclip = ImageEncoder(assets.bioclipModel())
    private val hazard = rows.map { it.hazard }.toBooleanArray()
    private val floor = rows.map { it.hazardFloor }.toBooleanArray()

    /**
     * A capture loop for one hunt in [region]: only its [local] rows are named, and a hazard warns when it is local, or
     * on the fixed floor inside North America.
     */
    fun verifier(local: Set<Int>, region: RegionKey): CaptureVerifier = CaptureVerifier(
        FrameVerifier(
            gate,
            gateEncoder,
            bioclip,
            HazardCheck(table, hazard, floor, BooleanArray(rows.size) { it in local }, NorthAmerica.contains(region)),
        ),
    )
}

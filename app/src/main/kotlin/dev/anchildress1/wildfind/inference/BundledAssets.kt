package dev.anchildress1.wildfind.inference

import android.content.res.AssetManager
import dev.anchildress1.wildfind.core.hunt.SpeciesRow
import dev.anchildress1.wildfind.core.tensor.FloatMatrix
import dev.anchildress1.wildfind.core.tensor.Npy
import dev.anchildress1.wildfind.core.verify.HazardCheck
import dev.anchildress1.wildfind.core.verify.LabelSet
import dev.anchildress1.wildfind.core.verify.PlantGate
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Models and tables the APK carries: `make assets` builds most of them; `make labels` commits the label vectors.
 *
 * @property assets the app's asset manager
 */
class BundledAssets(private val assets: AssetManager) {
    /** BioCLIP 2.5 Mobile image encoder, memory-mapped from the APK. */
    fun bioclipModel(): ByteBuffer = mapped(BIOCLIP)

    /** TinyCLIP plant-gate image encoder, memory-mapped from the APK. */
    fun plantGateModel(): ByteBuffer = mapped(PLANT_GATE)

    /** Plant-gate labels and scale from `plant_gate.json`. */
    fun plantGate(): PlantGate {
        val json = JSONObject(String(bytes(PLANT_GATE_LABELS)))
        val labels = json.getJSONArray("labels")
        return PlantGate(
            List(labels.length()) { i ->
                labels.getJSONObject(i).let {
                    PlantGate.Label(it.getBoolean("plant"), floats(it.getJSONArray("vector")))
                }
            },
            json.getDouble("logit_scale").toFloat(),
        )
    }

    /** BioCLIP species table: one unit text vector per row of [speciesLabels]. */
    fun speciesTable(): FloatMatrix = Npy.floatMatrix(bytes(SPECIES_TABLE))

    /** Name, genus, and flags per species-table row. */
    fun speciesLabels(): List<SpeciesRow> {
        val rows = JSONArray(String(bytes(SPECIES_LABELS)))
        return List(rows.length()) { i ->
            rows.getJSONObject(i).let {
                SpeciesRow(
                    it.getString("scientific"),
                    it.getString("genus"),
                    it.getBoolean("hazard"),
                    it.getBoolean("toxic"),
                )
            }
        }
    }

    /** Verify row 1's hazard rule over [table], naming only [local] species as the top species. */
    fun hazardCheck(table: FloatMatrix, labels: List<SpeciesRow>, local: Set<String>): HazardCheck = HazardCheck(
        table,
        labels.map { it.hazard }.toBooleanArray(),
        labels.map { it.scientific in local }.toBooleanArray(),
    )

    /** Menu-word and tutorial text vectors from `labels.npy`, with their `labels.json` entries. */
    fun labels(): LabelSet {
        val json = JSONObject(String(bytes(LABELS_JSON)))
        val version = json.getInt("schema_version")
        require(version == LABELS_SCHEMA) { "labels.json schema_version $version, expected $LABELS_SCHEMA" }
        val rows = json.getJSONArray("labels")
        val entries = List(rows.length()) { i ->
            rows.getJSONObject(i).let {
                LabelSet.Entry(it.getString("id"), LabelSet.Kind.of(it.getString("kind")), it.getString("scientific"))
            }
        }
        return LabelSet(entries, Npy.floatMatrix(bytes(LABELS_NPY)))
    }

    private fun bytes(name: String) = assets.open(name).use { it.readBytes() }

    // openFd only works on stored entries; app/build.gradle.kts keeps .onnx uncompressed. The mapping outlives the fd.
    private fun mapped(name: String): ByteBuffer = assets.openFd(name).use { fd ->
        FileInputStream(fd.fileDescriptor).channel.use {
            it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    private fun floats(array: JSONArray) = FloatArray(array.length()) { array.getDouble(it).toFloat() }

    private companion object {
        const val BIOCLIP = "flora_student_fp32.onnx"
        const val PLANT_GATE = "plant_gate.onnx"
        const val PLANT_GATE_LABELS = "plant_gate.json"
        const val SPECIES_TABLE = "species_table.npy"
        const val SPECIES_LABELS = "species_labels.json"
        const val LABELS_NPY = "labels.npy"
        const val LABELS_JSON = "labels.json"
        const val LABELS_SCHEMA = 1
    }
}

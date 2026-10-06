package dev.anchildress1.wildfind.core.tensor

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Row-major float matrix.
 *
 * @property rows row count
 * @property cols column count
 * @property data `rows * cols` values, row after row
 */
class FloatMatrix(val rows: Int, val cols: Int, val data: FloatArray) {
    init {
        require(rows >= 0 && cols >= 0 && data.size.toLong() == rows.toLong() * cols) {
            "expected $rows x $cols values, got ${data.size}"
        }
    }

    /** Dot product of row [index] with [vector]. */
    fun dot(index: Int, vector: FloatArray): Double {
        require(vector.size == cols) { "vector size ${vector.size}, expected $cols" }
        return data.dotAt(index * cols, vector)
    }
}

/** Dot product of [other] with this array's values starting at [start]; allocation-free for the per-frame path. */
fun FloatArray.dotAt(start: Int, other: FloatArray): Double {
    var sum = 0.0
    for (i in other.indices) sum += this[start + i] * other[i]
    return sum
}

/** Reads the 2-D little-endian float32 `.npy` files the build pipeline writes. */
object Npy {
    private val MAGIC =
        byteArrayOf(
            0x93.toByte(),
            'N'.code.toByte(),
            'U'.code.toByte(),
            'M'.code.toByte(),
            'P'.code.toByte(),
            'Y'.code.toByte(),
        )
    private val SHAPE = Regex("""'shape':\s*\((\d+),\s*(\d+)\)""")

    // Magic, then one byte each for the major and minor version.
    private const val LENGTH_OFFSET = 8
    private const val PREAMBLE_V1 = 10
    private const val PREAMBLE_V2 = 12
    private const val MAX_VERSION = 3

    /** Parses [bytes] as a C-order `<f4` matrix; throws [IllegalArgumentException] on any other layout. */
    fun floatMatrix(bytes: ByteArray): FloatMatrix {
        require(bytes.size >= PREAMBLE_V1 && bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) { "not an .npy file" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val major = bytes[MAGIC.size].toInt()
        require(major in 1..MAX_VERSION) { "unsupported .npy version $major" }
        // Version 1 stores the header length in 2 bytes; versions 2 and 3 use 4.
        val dataStart = if (major == 1) PREAMBLE_V1 else PREAMBLE_V2
        require(bytes.size >= dataStart) { "truncated .npy preamble" }
        val headerLength = if (major ==
            1
        ) {
            buffer.getShort(LENGTH_OFFSET).toUShort().toInt()
        } else {
            buffer.getInt(LENGTH_OFFSET)
        }
        require(headerLength in 0..bytes.size - dataStart) { "header length $headerLength overruns the file" }
        val header = String(bytes, dataStart, headerLength, Charsets.US_ASCII)
        require("'descr': '<f4'" in header) { "expected little-endian float32: $header" }
        require("'fortran_order': False" in header) { "expected C order: $header" }
        val (rows, cols) = requireNotNull(SHAPE.find(header)) { "expected a 2-D shape: $header" }
            .destructured.let { (r, c) -> r.toLongOrNull() to c.toLongOrNull() }
        // Each side fits an Int, so their product fits a Long without wrapping; then the count must fit an Int.
        val max = Int.MAX_VALUE.toLong()
        require(rows != null && cols != null && rows <= max && cols <= max && rows * cols <= max) {
            "shape too large: $header"
        }
        val count = rows * cols
        val floats = buffer.position(dataStart + headerLength).slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        require(floats.remaining().toLong() == count) { "expected $count values, got ${floats.remaining()}" }
        return FloatMatrix(rows.toInt(), cols.toInt(), FloatArray(count.toInt()).also { floats.get(it) })
    }
}

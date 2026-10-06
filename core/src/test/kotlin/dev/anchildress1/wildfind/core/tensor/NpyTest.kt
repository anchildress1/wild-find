package dev.anchildress1.wildfind.core.tensor

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NpyTest {
    private fun npy(header: String, values: FloatArray, version: Int = 1): ByteArray {
        val headerBytes = header.toByteArray(Charsets.US_ASCII)
        val lengthBytes = if (version == 1) 2 else 4
        val buffer = ByteBuffer.allocate(
            8 + lengthBytes + headerBytes.size + 4 * values.size,
        ).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(
            byteArrayOf(
                0x93.toByte(),
                'N'.code.toByte(),
                'U'.code.toByte(),
                'M'.code.toByte(),
                'P'.code.toByte(),
                'Y'.code.toByte(),
            ),
        )
        buffer.put(version.toByte()).put(0)
        if (version == 1) buffer.putShort(headerBytes.size.toShort()) else buffer.putInt(headerBytes.size)
        buffer.put(headerBytes)
        values.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private val values = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
    private val header = "{'descr': '<f4', 'fortran_order': False, 'shape': (2, 3), }\n"

    @Test
    fun `reads a version 1 float32 matrix row by row`() {
        val matrix = Npy.floatMatrix(npy(header, values))

        assertEquals(2, matrix.rows)
        assertEquals(3, matrix.cols)
        assertArrayEquals(values, matrix.data)
    }

    @Test
    fun `reads a version 2 header with a 4-byte length`() {
        assertArrayEquals(values, Npy.floatMatrix(npy(header, values, version = 2)).data)
    }

    @Test
    fun `dot multiplies one row`() {
        assertEquals(4.0 + 10.0 + 18.0, Npy.floatMatrix(npy(header, values)).dot(1, floatArrayOf(1f, 2f, 3f)))
    }

    @Test
    fun `dot rejects a vector of the wrong size`() {
        assertThrows<IllegalArgumentException> { Npy.floatMatrix(npy(header, values)).dot(0, floatArrayOf(1f)) }
    }

    @Test
    fun `rejects bytes without the npy magic`() {
        assertThrows<IllegalArgumentException> { Npy.floatMatrix(ByteArray(16)) }
    }

    @Test
    fun `rejects float64`() {
        assertThrows<IllegalArgumentException> { Npy.floatMatrix(npy(header.replace("<f4", "<f8"), values)) }
    }

    @Test
    fun `rejects fortran order`() {
        assertThrows<IllegalArgumentException> { Npy.floatMatrix(npy(header.replace("False", "True"), values)) }
    }

    @Test
    fun `rejects a 1-D shape`() {
        assertThrows<IllegalArgumentException> { Npy.floatMatrix(npy(header.replace("(2, 3)", "(6,)"), values)) }
    }

    @Test
    fun `rejects a value count that does not match the shape`() {
        assertThrows<IllegalArgumentException> { Npy.floatMatrix(npy(header, values.copyOf(5))) }
    }

    @Test
    fun `matrix rejects data that does not match its shape`() {
        assertThrows<IllegalArgumentException> { FloatMatrix(2, 2, FloatArray(3)) }
    }

    @Test
    fun `dotAt multiplies from an offset`() {
        assertEquals(4.0 * 1 + 5.0 * 2, floatArrayOf(9f, 4f, 5f).dotAt(1, floatArrayOf(1f, 2f)))
    }

    @Test
    fun `rejects an unknown major version`() {
        val bytes = npy(header, values).also { it[6] = 4 }

        assertThrows<IllegalArgumentException> { Npy.floatMatrix(bytes) }
    }

    @Test
    fun `rejects a version 2 preamble cut short`() {
        assertThrows<IllegalArgumentException> { Npy.floatMatrix(npy(header, values, version = 2).copyOf(11)) }
    }

    @Test
    fun `rejects a header length past the end of the file`() {
        val bytes = npy(header, values).also {
            it[8] = 0xFF.toByte()
            it[9] = 0x7F
        }

        assertThrows<IllegalArgumentException> { Npy.floatMatrix(bytes) }
    }

    @Test
    fun `rejects a shape whose count wraps an Int to zero`() {
        assertThrows<IllegalArgumentException> {
            Npy.floatMatrix(npy(header.replace("(2, 3)", "(65536, 65536)"), values))
        }
    }

    @Test
    fun `rejects a shape too large for a Long`() {
        val huge = header.replace("(2, 3)", "(99999999999999999999, 1)")

        assertThrows<IllegalArgumentException> { Npy.floatMatrix(npy(huge, values)) }
    }
}

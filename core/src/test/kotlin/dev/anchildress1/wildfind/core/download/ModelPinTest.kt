package dev.anchildress1.wildfind.core.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ModelPinTest {
    @Test
    fun `bioclip pins load from the bundled manifest`() {
        val pin = ModelPin.load("bioclip")

        assertEquals("crazedcodernate/bioclip-2.5-mobile-fastvit", pin.repo)
        assertEquals("flora_student_fp32.onnx", pin.file)
        assertEquals(46_986_589L, pin.bytes)
        assertTrue(pin.sha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `a model with missing keys is rejected`() {
        // teacher has a repo and revision but no file, size, or hash.
        assertThrows<IllegalArgumentException> { ModelPin.load("teacher") }
    }
}

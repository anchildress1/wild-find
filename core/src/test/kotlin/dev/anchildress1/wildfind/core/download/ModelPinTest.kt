package dev.anchildress1.wildfind.core.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ModelPinTest {
    @Test
    fun `gemma pins load from the bundled manifest`() {
        val pin = ModelPin.load("gemma")

        assertEquals("litert-community/gemma-4-E2B-it-litert-lm", pin.repo)
        assertEquals("gemma-4-E2B-it.litertlm", pin.file)
        assertEquals(2_588_147_712L, pin.bytes)
        // Preflight and marker checks compare the pin as lowercase hex.
        assertTrue(pin.sha256.matches(Regex("[0-9a-f]{64}")))
        assertEquals(
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/${pin.revision}/" +
                "gemma-4-E2B-it.litertlm",
            pin.url,
        )
    }

    @Test
    fun `the start URL is the pinned Hugging Face resolve URL`() {
        assertEquals(
            "https://huggingface.co/org/model/resolve/abc123/model.bin",
            ModelPin("org/model", "abc123", "model.bin", 1, "0").url,
        )
    }

    @Test
    fun `a model with missing keys is rejected`() {
        // teacher has a repo and revision but no file, size, or hash.
        assertThrows<IllegalArgumentException> { ModelPin.load("teacher") }
    }
}

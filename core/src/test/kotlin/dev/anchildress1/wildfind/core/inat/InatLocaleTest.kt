package dev.anchildress1.wildfind.core.inat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InatLocaleTest {
    @Test
    fun `a regional locale iNat has wins, else the language`() {
        assertEquals("pt-BR", InatLocale.of("pt-BR"))
        assertEquals("es-MX", InatLocale.of("es-MX"))
        assertEquals("es", InatLocale.of("es-ES"))
        assertEquals("en-US", InatLocale.of("en-US"))
        assertEquals("de", InatLocale.of("de-AT"))
        assertEquals("ka", InatLocale.of("ka-GE"))
    }

    @Test
    fun `retired codes Android still reports map to today's`() {
        assertEquals("he", InatLocale.of("iw-IL"))
        assertEquals("id", InatLocale.of("in-ID"))
        assertEquals("he", InatLocale.of("he"))
    }

    @Test
    fun `Chinese follows its script and region`() {
        assertEquals("zh-TW", InatLocale.of("zh-Hant-TW"))
        assertEquals("zh-TW", InatLocale.of("zh-Hant"))
        assertEquals("zh-HK", InatLocale.of("zh-Hant-HK"))
        assertEquals("zh-CN", InatLocale.of("zh-Hans-CN"))
        assertEquals("zh-CN", InatLocale.of("zh"))
    }

    @Test
    fun `an unsupported language falls back to English, and underscores read as tags`() {
        assertEquals(InatLocale.FALLBACK, InatLocale.of("yi"))
        assertEquals(InatLocale.FALLBACK, InatLocale.of("und"))
        assertEquals("fr-CA", InatLocale.of("fr_CA"))
    }
}

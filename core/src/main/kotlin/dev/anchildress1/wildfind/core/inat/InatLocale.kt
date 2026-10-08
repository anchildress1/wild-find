package dev.anchildress1.wildfind.core.inat

/** The `locale` iNat gets for common names, and the cache key keeps, from the device's BCP-47 language tag. */
object InatLocale {
    // iNaturalist's config/locales, minus its translator (qqq) and phonetic Japanese entries.
    private val SUPPORTED = setOf(
        "af", "ar", "be", "bg", "br", "ca", "cs", "da", "de", "el", "en", "en-CA", "en-GB", "en-US", "eo", "es",
        "es-AR", "es-CO", "es-CR", "es-MX", "et", "eu", "fa", "fi", "fil", "fo", "fr", "fr-CA", "gd", "gl", "gu", "he",
        "hi", "hr", "hu", "hy", "id", "is", "it", "ja", "ka", "kk", "kl", "kn", "ko", "lb", "lt", "lv", "mi", "mk",
        "ml", "mr", "ms", "nb", "nl", "nn", "oc", "pl", "pt", "pt-BR", "ro", "ru", "sat", "si", "sk", "sl", "sq",
        "sr", "sv", "sw", "ta", "te", "th", "tr", "uk", "vi", "zh-CN", "zh-HK", "zh-TW",
    )

    // Android still reports these retired ISO 639 codes.
    private val LEGACY = mapOf("iw" to "he", "in" to "id", "ji" to "yi")

    /** Names come back in English for any language iNat has no locale for. */
    const val FALLBACK = "en"

    private const val SCRIPT = 4
    private const val REGION = 2

    /** iNat's locale for [tag] (e.g. `pt-BR`, `zh-Hant-TW`, `iw-IL`): its regional locale, else its language. */
    fun of(tag: String): String {
        val parts = tag.replace('_', '-').split('-')
        val language = parts.first().lowercase().let { LEGACY[it] ?: it }
        val script = parts.drop(1).firstOrNull { it.length == SCRIPT }?.lowercase()
        val region = parts.drop(1).firstOrNull { it.length == REGION }?.uppercase()
        if (language == "zh") {
            return when {
                region == "HK" || region == "MO" -> "zh-HK"
                script == "hant" || region == "TW" -> "zh-TW"
                else -> "zh-CN"
            }
        }
        return listOfNotNull(region?.let { "$language-$it" }, language).firstOrNull { it in SUPPORTED } ?: FALLBACK
    }
}

package io.github.flavyu22.movietorrentsearchtv.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbLocaleTest {
    @Test fun mapsEverySupportedLanguage() {
        val expected = mapOf(
            "EN" to "en-US", "RO" to "ro-RO", "IT" to "it-IT", "ES" to "es-ES",
            "FR" to "fr-FR", "DE" to "de-DE", "PT" to "pt-PT", "RU" to "ru-RU",
            "EL" to "el-GR",
        )
        expected.forEach { (code, locale) -> assertEquals(locale, TmdbLocale.fromAppLanguage(code)) }
        assertEquals("en-US", TmdbLocale.fromAppLanguage("unknown"))
    }

    @Test
    fun mapsEverySupportedLanguageToAWikipediaEdition() {
        val expected = mapOf(
            "RO" to "ro",
            "ES" to "es",
            "FR" to "fr",
            "IT" to "it",
            "DE" to "de",
            "PT" to "pt",
            "RU" to "ru",
            "EL" to "el",
        )
        expected.forEach { (code, edition) ->
            assertEquals(edition, TmdbLocale.wikipediaLanguageCode(code))
        }
        assertEquals("en", TmdbLocale.wikipediaLanguageCode("unknown"))
        assertEquals("en", TmdbLocale.wikipediaLanguageCode(null))
    }
}

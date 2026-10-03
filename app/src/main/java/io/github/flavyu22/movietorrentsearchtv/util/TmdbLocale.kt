package io.github.flavyu22.movietorrentsearchtv.util

import java.util.Locale

object TmdbLocale {
    fun fromAppLanguage(languageCode: String?): String = when (
        languageCode.orEmpty().uppercase(Locale.ROOT)
    ) {
        "RO" -> "ro-RO"
        "ES" -> "es-ES"
        "FR" -> "fr-FR"
        "IT" -> "it-IT"
        "DE" -> "de-DE"
        "PT" -> "pt-PT"
        "RU" -> "ru-RU"
        "EL" -> "el-GR"
        else -> "en-US"
    }

    /**
     * Lower-case Wikipedia/Wikidata edition code for the app language. Used by the
     * secondary metadata provider ([io.github.flavyu22.movietorrentsearchtv.data.
     * metadata.WikipediaMetadataProvider]) which queries per-language editions.
     */
    fun wikipediaLanguageCode(languageCode: String?): String = when (
        languageCode.orEmpty().uppercase(Locale.ROOT)
    ) {
        "RO" -> "ro"
        "ES" -> "es"
        "FR" -> "fr"
        "IT" -> "it"
        "DE" -> "de"
        "PT" -> "pt"
        "RU" -> "ru"
        "EL" -> "el"
        else -> "en"
    }
}

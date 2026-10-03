package io.github.flavyu22.movietorrentsearchtv.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.OkHttpClient

/**
 * JVM tests over the pure parsing/wire-format logic of the keyless Wikidata
 * metadata provider. Network behaviour is intentionally not exercised here.
 */
class WikipediaMetadataProviderTest {

    // Pure parsing/wire-format tests only: the client is never used for network I/O here,
    // but the constructor requires an explicit one (no implicit default client allowed).
    private val provider = WikipediaMetadataProvider(OkHttpClient())

    @Test
    fun entityLookupUrlEncodesParametersForTheWire() {
        val url = provider.entityLookupUrl(
            referenceSite = "enwiki",
            title = "The Lord of the Rings",
            languages = listOf("ro", "es", "fr"),
        )
        assertTrue(url.startsWith("https://www.wikidata.org/w/api.php?"))
        assertTrue(url.contains("action=wbgetentities"))
        assertTrue(url.contains("sites=enwiki"))
        assertTrue(url.contains("titles=The+Lord+of+the+Rings"))
        assertTrue(url.contains("props=labels%7Csitelinks"))
        assertTrue(url.contains("languages=ro%7Ces%7Cfr"))
        assertTrue(url.contains("format=json&formatversion=2"))
    }

    @Test
    fun prefersHumanLabelInRequestedLanguage() {
        val json = """
            {"entities":{"Q189222":{"id":"Q189222","type":"item",
              "labels":{"en":{"language":"en","value":"The Lord of the Rings"},
                        "es":{"language":"es","value":"El Señor de los Anillos"}},
              "sitelinks":{"enwiki":{"site":"enwiki","title":"The Lord of the Rings"},
                           "eswiki":{"site":"eswiki","title":"El Señor de los Anillos"}}}}}
        """.trimIndent()
        val info = provider.parseEntityResponse(json, "es")
        assertEquals("es", info?.language)
        assertEquals("El Señor de los Anillos", info?.localizedTitle)
        assertEquals("https://es.wikipedia.org/wiki/El+Se%C3%B1or+de+los+Anillos", info?.pageUrl)
    }

    @Test
    fun fallsBackToLocalizedSitelinkWhenLabelMissing() {
        val json = """
            {"entities":{"Q123":{"id":"Q123","type":"item",
              "labels":{},
              "sitelinks":{"rowiki":{"site":"rowiki","title":"Stăpânul Inelelor"}}}}}
        """.trimIndent()
        val info = provider.parseEntityResponse(json, "ro")
        assertEquals("ro", info?.language)
        assertEquals("Stăpânul Inelelor", info?.localizedTitle)
    }

    @Test
    fun returnsNullWhenRequestedLanguageHasNoLocalizedName() {
        val json = """
            {"entities":{"Q123":{"id":"Q123","type":"item",
              "labels":{"es":{"language":"es","value":"Duna"}},
              "sitelinks":{"eswiki":{"site":"eswiki","title":"Duna"}}}}}
        """.trimIndent()
        assertNull(provider.parseEntityResponse(json, "el"))
    }

    @Test
    fun returnsNullForEmptyOrMalformedPayloads() {
        assertNull(provider.parseEntityResponse("""{"entities":{}}""", "es"))
        assertNull(provider.parseEntityResponse("not json at all", "es"))
        assertNull(provider.parseEntityResponse("", "es"))
    }
}

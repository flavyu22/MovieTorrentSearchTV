package io.github.flavyu22.movietorrentsearchtv.data.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpanishMagnetScraperTest {

    @Test
    fun readsTitleFromAnchorTextAndFromMagnetDisplayName() {
        val hashA = "0123456789abcdef0123456789abcdef01234567"
        val hashB = "89abcdef0123456789abcdef0123456789abcdef"
        val html = """
            <div class="result">
              <a href="magnet:?xt=urn:btih:$hashA&dn=ignored">Pelicula Ejemplo 2026 1080p Castellano</a>
            </div>
            <div class="result">
              <a href="magnet:?xt=urn:btih:$hashB&dn=Serie%20Ejemplo%20S01E02%20720p%20Latino"></a>
            </div>
        """.trimIndent()

        val results = parseMagnetSearchHtml(html, "MejorTorrent")

        assertEquals(2, results.size)
        val first = results.first { it.infoHash == hashA }
        assertEquals("Pelicula Ejemplo 2026 1080p Castellano", first.title)
        assertEquals("1080p", first.quality)
        assertEquals("ES", first.language)
        assertTrue(first.magnetUrl.startsWith("magnet:?xt=urn:btih:$hashA"))

        val second = results.first { it.infoHash == hashB }
        assertEquals("Serie Ejemplo S01E02 720p Latino", second.title)
        assertEquals("S01E02", second.seasonEpisode)
        assertEquals("ES", second.language)
        assertTrue(second.isSeries)
    }

    @Test
    fun deduplicatesByInfoHashAndSkipsUntitledMagnets() {
        val hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val untitled = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val html = """
            <a href="magnet:?xt=urn:btih:$hash&dn=Titulo%20Uno">Titulo Uno</a>
            <a href="magnet:?xt=urn:btih:$hash&dn=Titulo%20Uno%20Duplicado">Titulo Uno Duplicado</a>
            magnet:?xt=urn:btih:$untitled
        """.trimIndent()

        val results = parseMagnetSearchHtml(html, "DivxTotal")

        assertEquals(1, results.size)
        assertEquals(hash, results.single().infoHash)
        assertEquals("Titulo Uno", results.single().title)
    }

    @Test
    fun magnetDisplayNameDecodesWithoutSmugglingParameters() {
        assertEquals(
            "Movie Name",
            magnetDisplayName("magnet:?xt=urn:btih:${"a".repeat(40)}&dn=Movie%20Name"),
        )
        assertEquals(
            "Movie Name",
            magnetDisplayName("magnet:?xt=urn:btih:${"a".repeat(40)}&dn=Movie+Name"),
        )
        assertEquals("", magnetDisplayName("magnet:?xt=urn:btih:${"a".repeat(40)}"))
    }
}

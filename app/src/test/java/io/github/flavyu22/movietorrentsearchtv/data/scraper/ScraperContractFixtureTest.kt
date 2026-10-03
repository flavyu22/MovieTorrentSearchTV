package io.github.flavyu22.movietorrentsearchtv.data.scraper

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScraperContractFixtureTest {
    private val client = OkHttpClient.Builder().build()

    @Test
    fun parsesVersionedEztvFixture() {
        val body = fixture("contracts/eztv-response-v1.json")
        val result = EztvScraper(client).parseEztvResponse(body)

        assertEquals(1, result.size)
        assertEquals("EZTV", result.single().source)
        assertEquals("1080p x265", result.single().quality)
        assertEquals("S01E02", result.single().seasonEpisode)
        assertEquals(120, result.single().seeds)
        assertEquals("https://eztvx.to/ep/1/example-show-s01e02/", result.single().originalLink)
        assertTrue(result.single().magnetUrl.startsWith("magnet:?xt=urn:btih:0123456789abcdef"))
    }

    @Test
    fun parsesVersionedPirateBayFixture() {
        val body = fixture("contracts/piratebay-response-v1.json")
        val result = PirateBayScraper(client).parsePirateBayResponse(body)

        assertEquals(1, result.size)
        assertEquals("TPB", result.single().source)
        assertEquals("2160p", result.single().quality)
        assertEquals(340, result.single().seeds)
        assertEquals(12, result.single().peers)
        assertEquals("89abcdef0123456789abcdef0123456789abcdef", result.single().infoHash)
    }

    private fun fixture(path: String): String = requireNotNull(
        javaClass.classLoader?.getResource(path),
    ) { "Missing fixture: $path" }.readText()
}

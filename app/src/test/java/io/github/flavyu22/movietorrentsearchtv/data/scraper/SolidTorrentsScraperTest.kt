
package io.github.flavyu22.movietorrentsearchtv.data.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SolidTorrentsScraperTest {
    private val scraper = SolidTorrentsScraper(okhttp3.OkHttpClient())

    @Test
    fun parsesInfohashResultsIntoMagnetTorrents() {
        val body = """
            {
              "success": true,
              "results": [
                {
                  "id": "66108c767a868426cec15eed",
                  "infohash": "2770FE270845674966E184BE60ED1BE0FE494F3A",
                  "title": "Dune Part Two (2024) [1080p] [WEBRip] [YTS.MX]",
                  "size": 2968337547,
                  "seeders": 1495,
                  "leechers": 679,
                  "updatedAt": "2026-08-22T09:16:23.904Z"
                },
                { "id": "x", "title": "Fara infohash valid", "size": 1 }
              ]
        """.trimIndent() + "\n}"

        val results = scraper.parseSolidResponse(body)

        assertEquals(1, results.size)
        val torrent = results.single()
        assertEquals("2770fe270845674966e184be60ed1be0fe494f3a", torrent.infoHash)
        assertEquals("Dune Part Two (2024) [1080p] [WEBRip] [YTS.MX]", torrent.title)
        assertTrue(torrent.magnetUrl.startsWith("magnet:?xt=urn:btih:2770fe270845674966e184be60ed1be0fe494f3a"))
        assertTrue(torrent.magnetUrl.contains("dn=Dune%20Part%20Two"))
        assertEquals(1495, torrent.seeds)
        assertEquals(679, torrent.peers)
        assertEquals("2.76 GB", torrent.size)
        assertEquals("1080p", torrent.quality)
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsUnsuccessfulResponses() {
        scraper.parseSolidResponse("""{"success": false, "results": []}""")
        assertTrue(scraper.parseSolidResponse("""{"results": null}""").isEmpty())
    }

    @Test
    fun retriesTransient5xxBeforeSucceeding() = runBlocking {
        val scraper = SolidTorrentsScraper(okhttp3.OkHttpClient())
        var calls = 0
        val body = scraper.fetchWithRetry(maxAttempts = 4, delayMs = 1) {
            calls++
            if (calls <= 2) {
                throw HttpStatusException(500, "https://solidtorrents.eu/api/v1/search?q=test")
            }
            """{"success": true, "results": []}"""
        }
        assertEquals(3, calls)
        assertTrue(body.contains("success"))
    }

    @Test
    fun doesNotRetryClientErrors() {
        val scraper = SolidTorrentsScraper(okhttp3.OkHttpClient())
        var calls = 0
        try {
            runBlocking {
                scraper.fetchWithRetry(maxAttempts = 4, delayMs = 1) {
                    calls++
                    throw HttpStatusException(404, "https://solidtorrents.eu/api/v1/search?q=test")
                }
            }
            fail("expected HttpStatusException")
        } catch (expected: HttpStatusException) {
            assertEquals(404, expected.statusCode)
            assertEquals(1, calls)
        }
    }

    @Test
    fun retriesTransportIoFailuresThenSucceeds() = runBlocking {
        val scraper = SolidTorrentsScraper(okhttp3.OkHttpClient())
        var calls = 0
        val body = scraper.fetchWithRetry(maxAttempts = 3, delayMs = 1) {
            calls++
            if (calls == 1) throw java.io.IOException("boom")
            """{"success": true, "results": []}"""
        }
        assertEquals(2, calls)
        assertTrue(body.contains("results"))
    }
}
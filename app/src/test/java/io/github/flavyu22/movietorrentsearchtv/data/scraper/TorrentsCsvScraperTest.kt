package io.github.flavyu22.movietorrentsearchtv.data.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentsCsvScraperTest {
    private val scraper = TorrentsCsvScraper(okhttp3.OkHttpClient())

    @Test
    fun responseProducesUnifiedTorrents() {
        val body = """
            {"torrents":[
              {"infohash":"224bf45881252643dfc2e71abc7b2660a21c68c4",
               "name":"Inception (2010) 1080p BrRip x264 - YIFY",
               "size_bytes":1991613584,"created_unix":1339547627,
               "seeders":642,"leechers":206,"completed":14062},
              {"infohash":"short","name":"Invalid hash row must be skipped"}
            ]}
        """.trimIndent()
        val results = scraper.parseTorrentsCsvResponse(body)

        assertEquals(1, results.size)
        val torrent = results.single()
        assertEquals("TorrentsCSV", torrent.source)
        assertEquals("224bf45881252643dfc2e71abc7b2660a21c68c4", torrent.infoHash)
        assertEquals(642, torrent.seeds)
        assertEquals(206, torrent.peers)
        assertTrue(torrent.magnetUrl.startsWith("magnet:?xt=urn:btih:224bf458"))
        assertTrue(torrent.size.isNotBlank())
    }

    @Test
    fun emptyPayloadYieldsNoResults() {
        val results = scraper.parseTorrentsCsvResponse("""{"torrents":[]}""")
        assertTrue(results.isEmpty())
    }
}
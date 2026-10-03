package io.github.flavyu22.movietorrentsearchtv.data.scraper

import io.github.flavyu22.movietorrentsearchtv.model.TorrentSource.Category
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Contract tests for the two Cyrillic/Russian magnet sources added on 2026-09-19.
 * Network transport is stubbed with an interceptor; only request construction and
 * response parsing are exercised.
 */
class MultilingualProvidersTest {
    private val hash = "a".repeat(40)

    private val rutorBody get() = """
        {"status":"ok","total":1,"results":[
          {"id":1,"name":"Ночной дозор 2004 720p","torrent_id":"$hash",
           "size":1500000000,"seeders":12,"leechers":3,"type":"movie",
           "date":"2026-09-18 10:00:00","url":"https://rutor.info/torrent/1"}
        ]}
    """.trimIndent()

    private val rutrackerBody get() = """
        {"total_size_lines":1,"total_size_bytes":1500000000,"response":[
          {"topic_id":1234567,"topic_title":"Ночной дозор 2004 1080p",
           "size":1500000000,"seeders":7,"lechers":1,"topic_date_start":1758000000,
           "dl":"$hash","imdb_id":"0371744"}
        ]}
    """.trimIndent()

    // ── Rutor ────────────────────────────────────────────────────────────────

    @Test fun rutorBuildsSearchRequestAndParsesCyrillicMagnet() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("rutor.info", request.url.host)
            assertTrue(request.url.encodedPath.startsWith("/search/"))
            assertTrue(request.url.encodedPath.endsWith("/0/0/0"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(rutorBody.toResponseBody()).build()
        }.build()

        val row = RutorScraper(client).search("Ночной дозор", Category.MOVIES).single()
        assertEquals(hash, extractHashFromMagnet(row.magnetUrl))
        assertEquals(hash, row.infoHash)
        assertEquals(12, row.seeds)
        assertEquals(3, row.peers)
        assertEquals("Rutor", row.source)
        // The Cyrillic title must survive URL-encoding into the magnet display name.
        assertTrue(row.magnetUrl.contains("dn="))
        assertEquals("https://rutor.info/torrent/1", row.originalLink)
    }

    @Test fun rutorRejectsMissingResultsArray() {
        val parser = RutorScraper(OkHttpClient())
        assertThrows(IOException::class.java) { parser.parseRutorResponse("{}") }
    }

    @Test fun rutorSkipsInvalidHashBlankTitleAndForeignLinks() {
        val parser = RutorScraper(OkHttpClient())
        assertTrue(parser.parseRutorResponse(rutorBody.replace(hash, "not-a-hash")).isEmpty())
        assertTrue(
            parser.parseRutorResponse(rutorBody.replace("Ночной дозор 2004 720p", "  ")).isEmpty()
        )
        assertTrue(parser.parseRutorResponse("""{"status":"ok","results":[]}""").isEmpty())

        val foreign = parser.parseRutorResponse(
            rutorBody.replace("https://rutor.info/torrent/1", "https://evil.example/torrent/1")
        ).single()
        assertNull(foreign.originalLink)

        val cleartext = parser.parseRutorResponse(
            rutorBody.replace("https://rutor.info/torrent/1", "http://rutor.info/torrent/1")
        ).single()
        assertNull(cleartext.originalLink)
    }

    @Test fun rutorUnsupportedCategoryMakesNoRequest() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { throw AssertionError("Unexpected request") }
            .build()
        assertTrue(RutorScraper(client).search("Sintel", Category.GAMES).isEmpty())
// ── Rutracker ────────────────────────────────────────────────────────────

    @Test fun rutrackerBuildsApiRequestAndParsesRussianMagnet() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("rutracker.org", request.url.host)
            assertEquals("search", request.url.queryParameter("method"))
            assertEquals("test-key", request.url.queryParameter("apikey"))
            assertEquals("Ночной дозор", request.url.queryParameter("query"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(rutrackerBody.toResponseBody()).build()
        }.build()

        val row = RutrackerScraper(client, "test-key")
            .search("Ночной дозор", Category.MOVIES).single()
        assertEquals(hash, extractHashFromMagnet(row.magnetUrl))
        assertEquals(7, row.seeds)
        assertEquals("Rutracker", row.source)
        assertEquals(
            "https://rutracker.org/forum/viewtopic.php?t=1234567",
            row.originalLink,
        )
    }

    @Test fun rutrackerReportsMissingApiKeyInsteadOfSilentEmptyResults() {
        val client = OkHttpClient.Builder()
            .addInterceptor { throw AssertionError("No request without a key") }
            .build()
        assertThrows(IOException::class.java) {
            runBlocking { RutrackerScraper(client, "").search("Sintel", Category.MOVIES) }
        }
    }

    @Test fun rutrackerRejectsApiErrorEnvelopes() {
        val parser = RutrackerScraper(OkHttpClient(), "key")
        assertThrows(IOException::class.java) {
            parser.parseRutrackerResponse("""{"error_code":2,"error_text":"bad key"}""")
        }
        assertThrows(IOException::class.java) { parser.parseRutrackerResponse("{}") }
    }

    @Test fun rutrackerSkipsRowsWithoutAValidHash() {
        val parser = RutrackerScraper(OkHttpClient(), "key")
        assertTrue(parser.parseRutrackerResponse(rutrackerBody.replace(hash, "zzzz")).isEmpty())
        assertTrue(parser.parseRutrackerResponse("""{"response":[]}""").isEmpty())
    }

    @Test fun rutrackerIgnoresNonNumericTopicIdInOriginalLink() {
        val parser = RutrackerScraper(OkHttpClient(), "key")
        val row = parser.parseRutrackerResponse(
            rutrackerBody.replace("\"topic_id\":1234567", "\"topic_id\":\"1 OR 1=1\"")
        ).single()
        assertNull(row.originalLink)
    }
}
    }
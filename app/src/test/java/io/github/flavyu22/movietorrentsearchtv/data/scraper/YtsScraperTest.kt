package io.github.flavyu22.movietorrentsearchtv.data.scraper

import io.github.flavyu22.movietorrentsearchtv.model.TorrentSource.Category
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class YtsScraperTest {
    private val hash = "b".repeat(40)

    /**
     * YTS answers HTTP 200 / status="ok" with a non-zero movie_count but omits the
     * "movies" array whenever query_term ends in a 4-digit year. Verified live on every
     * configured mirror on 2026-10-03. It is an empty page, not a broken response.
     */
    private val degradedPage =
        """{"status":"ok","data":{"movie_count":3,"limit":50,"page_number":1}}"""

    private val moviePage = """
        {"status":"ok","data":{"movie_count":1,"limit":50,"page_number":1,"movies":[
          {"title":"Dune Part Two","year":2024,
           "url":"https://yts.gg/movies/dune-part-two-2024",
           "torrents":[{"hash":"$hash","quality":"1080p","type":"web","video_codec":"x264",
                        "seeds":42,"peers":3,"size":"2.9 GB"}]}]}}
    """.trimIndent()

    /**
     * Stands in for the live endpoint. Keyed by query_term because `firstSuccessfulMirror`
     * races every mirror concurrently, so one logical attempt fans out into several
     * requests; [seen] therefore records distinct terms, in the order they were first tried.
     */
    private fun clientReturning(
        responses: Map<String, String>,
        seen: MutableSet<String>
    ) = OkHttpClient.Builder().addInterceptor { chain ->
        // Every configured mirror is queried concurrently by firstSuccessfulMirror, so the
        // recorder must be synchronized; a plain HashSet would race and lose entries.
        val term = chain.request().url.queryParameter("query_term").orEmpty()
        synchronized(seen) { seen += term }
        val body = responses[term]
            ?: error("Unexpected query_term '$term'; known: ${responses.keys}")
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody())
            .build()
    }.build()

    @Test fun degradedPageIsAnEmptyResultNotAFailure() {
        val parser = YtsScraper(OkHttpClient())
        assertTrue(parser.parseYtsResponse(degradedPage).isEmpty())
        assertTrue(parser.parseYtsResponse("""{"status":"ok","data":{"movie_count":0}}""").isEmpty())
    }

    @Test fun transportAndEnvelopeFailuresStillSurface() {
        val parser = YtsScraper(OkHttpClient())
        for (body in listOf(
            "{}",
            """{"status":"error","status_message":"nope"}""",
            """{"status":"ok"}""",
        )) {
            assertThrows(IOException::class.java) { parser.parseYtsResponse(body) }
        }
    }

    @Test fun yearSuffixedQueryFallsBackToTheBareTitle() = runBlocking {
        val seen = LinkedHashSet<String>()
        val rows = YtsScraper(
            clientReturning(
                mapOf("Dune Part Two 2024" to degradedPage, "Dune Part Two" to moviePage),
                seen,
            )
        ).search("Dune Part Two 2024", Category.MOVIES)
        assertEquals(1, rows.size)
        assertEquals(listOf("Dune Part Two 2024", "Dune Part Two"), seen.toList())
        assertEquals("YTS", rows.single().source)
    }

    @Test fun noFallbackRequestWhenTheFirstQueryReturnsRows() = runBlocking {
        val seen = LinkedHashSet<String>()
        val rows = YtsScraper(clientReturning(mapOf("Dune Part Two 2024" to moviePage), seen))
            .search("Dune Part Two 2024", Category.MOVIES)
        assertEquals(1, rows.size)
        assertEquals(listOf("Dune Part Two 2024"), seen.toList())
    }

    @Test fun imdbLookupsAreNotRewrittenAsTitles() = runBlocking {
        val seen = LinkedHashSet<String>()
        val rows = YtsScraper(clientReturning(mapOf("tt15239678" to moviePage), seen))
            .searchByImdb("tt15239678")
        assertEquals(1, rows.size)
        assertEquals(listOf("tt15239678"), seen.toList())
    }

    @Test fun aGenuinelyEmptyIndexDoesNotRetryForever() = runBlocking {
        val seen = LinkedHashSet<String>()
        val rows = YtsScraper(
            clientReturning(
                mapOf(
                    "Dune Part Two 2024" to degradedPage,
                    "Dune Part Two" to degradedPage,
                ),
                seen,
            )
        ).search("Dune Part Two 2024", Category.MOVIES)
        assertTrue(rows.isEmpty())
        assertEquals(listOf("Dune Part Two 2024", "Dune Part Two"), seen.toList())
    }
}
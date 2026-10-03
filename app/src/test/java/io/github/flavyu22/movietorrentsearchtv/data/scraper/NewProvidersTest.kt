package io.github.flavyu22.movietorrentsearchtv.data.scraper

import io.github.flavyu22.movietorrentsearchtv.model.TorrentSource.Category
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NewProvidersTest {
    private val hash = "a".repeat(40)
    private val bitBody get() = """{"success":true,"results":[{"title":"Sintel 1080p","infohash":"$hash","seeders":-1}]}"""
    private val rss get() = """<rss xmlns:nyaa="https://nyaa.si/xmlns/nyaa"><channel><item><title>Sintel 1080p</title><nyaa:infoHash>$hash</nyaa:infoHash><nyaa:seeders>2</nyaa:seeders><nyaa:size>523.5 MiB</nyaa:size><guid>https://nyaa.si/view/567811</guid></item></channel></rss>"""

    @Test fun bitSearchBuildsMovieRequestAndParsesMagnet() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("bitsearch.eu", request.url.host)
            assertEquals("Sintel & test", request.url.queryParameter("q"))
            assertEquals("2", request.url.queryParameter("category"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(bitBody.toResponseBody()).build()
        }.build()
        val row = BitSearchScraper(client).search("Sintel & test", Category.MOVIES).single()
        assertEquals(hash, extractHashFromMagnet(row.magnetUrl))
        assertEquals(0, row.seeds)
        assertEquals("BitSearch", row.source)
    }
    @Test fun bitSearchRejectsMissingResultsAndExplicitFailure() {
        for (body in listOf("{}", """{"success":false,"results":[]}""")) {
            assertThrows(IOException::class.java) { BitSearchScraper(OkHttpClient()).parseResponse(body) }
        }
    }
    @Test fun bitSearchSkipsInvalidHashAndBlankTitle() {
        val parser = BitSearchScraper(OkHttpClient())
        assertTrue(parser.parseResponse(bitBody.replace(hash, "invalid")).isEmpty())
        assertTrue(parser.parseResponse(bitBody.replace("Sintel 1080p", " ")).isEmpty())
        assertTrue(parser.parseResponse("""{"success":true,"results":[]}""").isEmpty())
    }
    @Test fun nyaaBuildsAnimeRssRequestAndParsesMagnet() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("nyaa.si", request.url.host)
            assertEquals("rss", request.url.queryParameter("page"))
            assertEquals("1_0", request.url.queryParameter("c"))
            assertEquals("Sintel", request.url.queryParameter("q"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(rss.toResponseBody()).build()
        }.build()
        val row = NyaaScraper(client).search("Sintel", Category.MOVIES).single()
        assertEquals(hash, extractHashFromMagnet(row.magnetUrl))
        assertEquals(2, row.seeds)
        assertEquals("https://nyaa.si/view/567811", row.originalLink)
    }
    @Test fun nyaaRejectsEntityDeclarationsAndWrongDocument() {
        val parser = NyaaScraper(OkHttpClient())
        for (body in listOf("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>$rss", "<html/>", "<rss/>")) {
            assertThrows(IOException::class.java) { parser.parseResponse(body) }
        }
    }
    @Test fun nyaaSkipsInvalidRowsAndUnsafeLinks() {
        val parser = NyaaScraper(OkHttpClient())
        assertTrue(parser.parseResponse(rss.replace(hash, "bad")).isEmpty())
        assertTrue(parser.parseResponse("<rss><channel/></rss>").isEmpty())
        assertNull(parser.parseResponse(rss.replace("https://nyaa.si/view/567811", "https://example.com/")).single().originalLink)
    }
    @Test fun unsupportedQueriesDoNotMakeNetworkRequests() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { throw AssertionError("Unexpected request") }.build()
        for (scraper in listOf(BitSearchScraper(client), NyaaScraper(client))) {
            assertTrue(scraper.search("Sintel", Category.GAMES).isEmpty())
            assertTrue(scraper.search(" ", Category.ALL).isEmpty())
            assertTrue(scraper.searchByImdb("tt1727587").isEmpty())
        }
    }
}

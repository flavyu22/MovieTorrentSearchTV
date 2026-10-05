package io.github.flavyu22.movietorrentsearchtv.data.scraper

import io.github.flavyu22.movietorrentsearchtv.BuildConfig
import io.github.flavyu22.movietorrentsearchtv.model.TorrentSource
import io.github.flavyu22.movietorrentsearchtv.model.UnifiedTorrent
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiSourceScraperTest {
    /**
     * Locks the trimmed provider set.
     *
     * The active list is deliberately short because every provider is queried once per
     * title variant on each detail screen. BitSearch and Nyaa were removed for latency,
     * and Rutracker only registers when an API key is present, so the expected list is
     * asserted conditionally rather than always containing all nine historical names.
     */
    @Test
    fun activeProvidersMatchTheRequestedSelection() {
        val scraper = MultiSourceScraper(OkHttpClient())
        val names = scraper.scrapers.map { it.source.name }

        val expected = buildList {
            addAll(listOf("YTS", "EZTV", "TPB", "Solid", "TorrentsCSV"))
            if (RutrackerScraper(OkHttpClient(), BuildConfig.RUTRACKER_API_KEY).isConfigured) {
                add("Rutracker")
            }
        }
        assertEquals(expected, names)

        // The two dropped indexes must not sneak back in.
        assertTrue("names=$names", names.none { it == "BitSearch" || it == "Nyaa" || it == "Rutor" })
    }

    /**
     * A provider that cannot possibly succeed must not be registered: unconfigured,
     * Rutracker throws on every call, which costs a request per search and pushes a
     * permanent "API key not configured" error into the source-error list.
     */
    @Test
    fun unconfiguredRutrackerIsNotRegistered() {
        val names = MultiSourceScraper(OkHttpClient())
            .scrapers
            .map { it.source.name }
        if (BuildConfig.RUTRACKER_API_KEY.isBlank()) {
            assertFalse("names=$names", names.contains("Rutracker"))
        }
    }

    @Test
    fun trimmedSetStaysWithinTheLatencyBudget() {
        val scraper = MultiSourceScraper(OkHttpClient())
        // Four per category plus the optional keyed tracker. This is the guardrail that
        // catches an accidental re-add of a dropped provider.
        val movies = scraper.eligibleScrapersCount(TorrentSource.Category.MOVIES)
        val series = scraper.eligibleScrapersCount(TorrentSource.Category.TV_SHOWS)
        assertTrue("movie providers=$movies", movies <= 5)
        assertTrue("series providers=$series", series <= 5)
    }

    @Test
    fun titleSearchInvokesOnlyCategoryEligibleSources() = runBlocking {
        val movies = FakeScraper("Movies", listOf(TorrentSource.Category.MOVIES))
        val television = FakeScraper("TV", listOf(TorrentSource.Category.TV_SHOWS))
        val all = FakeScraper("All", listOf(TorrentSource.Category.ALL))
        val scraper = MultiSourceScraper(
            OkHttpClient.Builder().build(),
            listOf(movies, television, all)
        )

        val outcomes = scraper.searchAllFlow("Example", TorrentSource.Category.TV_SHOWS).toList()

        assertEquals(setOf("TV", "All"), outcomes.map { it.source }.toSet())
        assertEquals(0, movies.searchCalls.get())
        assertEquals(1, television.searchCalls.get())
        assertEquals(1, all.searchCalls.get())
        assertEquals(2, scraper.eligibleScrapersCount(TorrentSource.Category.TV_SHOWS))
    }

    @Test
    fun imdbSearchAlsoHonoursTheRequestedCategory() = runBlocking {
        val movies = FakeScraper("Movies", listOf(TorrentSource.Category.MOVIES))
        val television = FakeScraper("TV", listOf(TorrentSource.Category.TV_SHOWS))
        val scraper = MultiSourceScraper(
            OkHttpClient.Builder().build(),
            listOf(movies, television)
        )

        val outcomes = scraper.searchByImdbFlow(
            "tt1234567",
            TorrentSource.Category.MOVIES
        ).toList()

        assertEquals(listOf("Movies"), outcomes.map { it.source })
        assertEquals(1, movies.imdbCalls.get())
        assertEquals(0, television.imdbCalls.get())
    }

    @Test
    fun imdbSearchCanBeRestrictedToListedSources() = runBlocking {
        val alpha = FakeScraper("Alpha", listOf(TorrentSource.Category.ALL))
        val beta = FakeScraper("Beta", listOf(TorrentSource.Category.ALL))
        val gamma = FakeScraper("Gamma", listOf(TorrentSource.Category.ALL))
        val scraper = MultiSourceScraper(
            OkHttpClient.Builder().build(),
            listOf(alpha, beta, gamma)
        )

        val outcomes = scraper.searchByImdbFlow(
            "tt1234567",
            TorrentSource.Category.ALL,
            sourceNames = setOf("Alpha", "Gamma"),
        ).toList()

        assertEquals(setOf("Alpha", "Gamma"), outcomes.map { it.source }.toSet())
        assertEquals(1, alpha.imdbCalls.get())
        assertEquals(0, beta.imdbCalls.get())
        assertEquals(1, gamma.imdbCalls.get())
    }

    @Test
    fun imdbSearchWithoutRestrictionReachesEverySource() = runBlocking {
        val alpha = FakeScraper("Alpha", listOf(TorrentSource.Category.ALL))
        val beta = FakeScraper("Beta", listOf(TorrentSource.Category.ALL))
        val scraper = MultiSourceScraper(
            OkHttpClient.Builder().build(),
            listOf(alpha, beta)
        )

        scraper.searchByImdbFlow("tt1234567").toList()

        assertEquals(1, alpha.imdbCalls.get())
        assertEquals(1, beta.imdbCalls.get())
    }

    private class FakeScraper(
        name: String,
        categories: List<TorrentSource.Category>
    ) : TorrentScraper {
        override val source = TorrentSource(name, "https://example.com", 1f, categories)
        val searchCalls = AtomicInteger()
        val imdbCalls = AtomicInteger()

        override suspend fun search(
            query: String,
            category: TorrentSource.Category
        ): List<UnifiedTorrent> {
            searchCalls.incrementAndGet()
            return emptyList()
        }

        override suspend fun searchByImdb(imdbId: String): List<UnifiedTorrent> {
            imdbCalls.incrementAndGet()
            return emptyList()
        }
    }
}

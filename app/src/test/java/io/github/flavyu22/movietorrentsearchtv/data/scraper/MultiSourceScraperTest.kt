package io.github.flavyu22.movietorrentsearchtv.data.scraper

import io.github.flavyu22.movietorrentsearchtv.model.TorrentSource
import io.github.flavyu22.movietorrentsearchtv.model.UnifiedTorrent
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class MultiSourceScraperTest {
    @Test
    fun activeProvidersMatchTheRequestedSelection() {
        val names = MultiSourceScraper(okhttp3.OkHttpClient()).scrapers.map { it.source.name }
        assertEquals(
            listOf(
                "YTS", "EZTV", "TPB", "Solid", "TorrentsCSV",
                "BitSearch", "Nyaa", "Rutor", "Rutracker",
            ),
            names,
        )
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

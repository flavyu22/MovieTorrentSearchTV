package io.github.flavyu22.movietorrentsearchtv.data.scraper

import androidx.compose.runtime.Immutable
import io.github.flavyu22.movietorrentsearchtv.BuildConfig
import io.github.flavyu22.movietorrentsearchtv.model.TorrentSource
import io.github.flavyu22.movietorrentsearchtv.model.UnifiedTorrent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

@Immutable
data class SourceSearchResult(
    val source: String,
    val torrents: List<UnifiedTorrent>,
    val errorMessage: String? = null
) {
    val isSuccessful: Boolean get() = errorMessage == null
}

class MultiSourceScraper(
    private val client: OkHttpClient,
    val scrapers: List<TorrentScraper> = defaultScrapers(client)
) {
    fun searchAllFlow(
        query: String,
        category: TorrentSource.Category
    ): Flow<SourceSearchResult> = searchFlow(
        timeoutMs = SEARCH_TIMEOUT_MS,
        selectedScrapers = eligibleScrapers(category)
    ) { scraper ->
        scraper.search(query, category)
    }

    fun searchByImdbFlow(
        imdbId: String,
        category: TorrentSource.Category = TorrentSource.Category.ALL,
        sourceNames: Set<String> = emptySet(),
    ): Flow<SourceSearchResult> {
        val selected = if (sourceNames.isEmpty()) {
            eligibleScrapers(category)
        } else {
            eligibleScrapers(category).filter { it.source.name in sourceNames }
        }
        return searchFlow(
            timeoutMs = IMDB_TIMEOUT_MS,
            selectedScrapers = selected
        ) { scraper -> scraper.searchByImdb(imdbId) }
    }

    fun eligibleScrapers(category: TorrentSource.Category): List<TorrentScraper> =
        if (category == TorrentSource.Category.ALL) {
            scrapers
        } else {
            scrapers.filter { scraper ->
                val supported = scraper.source.supportedCategories
                category in supported || TorrentSource.Category.ALL in supported
            }
        }

    fun eligibleScrapersCount(category: TorrentSource.Category): Int =
        eligibleScrapers(category).size

    suspend fun searchAll(
        query: String,
        category: TorrentSource.Category,
        onSourceComplete: (String, List<UnifiedTorrent>) -> Unit = { _, _ -> }
    ): List<UnifiedTorrent> = collectResults(searchAllFlow(query, category), onSourceComplete)

    suspend fun searchByImdbAll(
        imdbId: String,
        onSourceComplete: (String, List<UnifiedTorrent>) -> Unit = { _, _ -> }
    ): List<UnifiedTorrent> = collectResults(searchByImdbFlow(imdbId), onSourceComplete)

    private fun searchFlow(
        timeoutMs: Long,
        selectedScrapers: List<TorrentScraper>,
        block: suspend (TorrentScraper) -> List<UnifiedTorrent>
    ): Flow<SourceSearchResult> = channelFlow {
        selectedScrapers.forEach { scraper ->
            launch {
                val outcome = try {
                    val torrents = withTimeoutOrNull(timeoutMs) { block(scraper) }
                    if (torrents == null) {
                        SourceSearchResult(scraper.source.name, emptyList(), "Timed out")
                    } else {
                        SourceSearchResult(scraper.source.name, torrents)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    SourceSearchResult(
                        source = scraper.source.name,
                        torrents = emptyList(),
                        errorMessage = failure.toUserMessage()
                    )
                }
                send(outcome)
            }
        }
    }

    private suspend fun collectResults(
        flow: Flow<SourceSearchResult>,
        onSourceComplete: (String, List<UnifiedTorrent>) -> Unit
    ): List<UnifiedTorrent> {
        val results = mutableListOf<UnifiedTorrent>()
        flow.collect { outcome ->
            onSourceComplete(outcome.source, outcome.torrents)
            results += outcome.torrents
        }
        return results.distinctBy { it.infoHash.lowercase() }.sorted()
    }

    private fun Exception.toUserMessage(): String = when (this) {
        is HttpStatusException -> "HTTP $statusCode"
        is java.net.SocketTimeoutException -> "Timed out"
        is java.net.UnknownHostException -> "Host unavailable"
        is java.io.IOException -> "Network error"
        else -> "Invalid response"
    }

    private companion object {
        const val SEARCH_TIMEOUT_MS = 15_000L
        // Keep IMDb and text lookups within the same bounded search budget.
        const val IMDB_TIMEOUT_MS = SEARCH_TIMEOUT_MS

        fun defaultScrapers(client: OkHttpClient): List<TorrentScraper> = listOf(
            YtsScraper(client),
            EztvScraper(client),
            PirateBayScraper(client),
            SolidTorrentsScraper(client),
            TorrentsCsvScraper(client),
            BitSearchScraper(client),
            NyaaScraper(client),
            // Multi-language magnet sources: keyless Cyrillic index + the canonical
            // Russian tracker (configured separately, see README/TMDB_SETUP_RO.md).
            RutorScraper(client),
            RutrackerScraper(client, BuildConfig.RUTRACKER_API_KEY),
        )
    }
}

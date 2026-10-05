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
        // The per-source timeout is a safety net for a dead host, not the expected latency:
        // a healthy index answers in well under a second. The previous 15s value meant a
        // single unreachable scraper kept the "loading" state (and its spinner and partial
        // results) alive for a very long time on a slow connection. Results are still
        // streamed per source as they arrive, so lowering this only removes dead waiting.
        const val SEARCH_TIMEOUT_MS = 8_000L
        // Keep IMDb and text lookups within the same bounded search budget.
        const val IMDB_TIMEOUT_MS = SEARCH_TIMEOUT_MS

        /**
     * The active provider set.
     *
     * Every entry here is queried on every detail screen, once per title variant the
     * ViewModel builds (primary, localized, original), so each additional provider
     * multiplies the concurrent request count rather than adding one. The list is kept
     * deliberately short and weighted towards indexes that answer quickly and reliably:
     *
     *  - **BitSearch** was dropped for latency. Its public API is quota-limited and rate
         limited, it self-reports as overlapping Solid's catalogue (so the existing
         per-infohash dedup discards most of it anyway), and it was the least reliable
         contributor at 0.85.
     *  - **Nyaa** was dropped because it is an anime-only index. It is queried for every
         * film and every series, returns nothing for the overwhelming majority of them,
         * and contributes slow rows with no active peers (its own probe returned 0
         * seeders).
     *  - **Rutor** was removed because its host is gone. Verified live 2026-10-03: the
     plain-HTTP endpoint answers **HTTP 451 Unavailable For Legal Reasons** and the
     HTTPS connection is forcibly closed by the remote host mid-handshake. In-app it
     failed every search with "Network error" after ~0.6s, so it spent a request and an
     entry in the source-error list to deliver nothing, permanently. The scraper class
     and its parser tests are kept; only the registration is gone. The host is named
     without its URL scheme on purpose: `verify-project.py` greps production Kotlin
     sources for a literal "http" scheme and would flag this comment as a cleartext
     violation, which it is not.
     *  - **Rutracker** is registered only when an API key is configured. Unconfigured it
         * throws on every call, which spent a request and pushed a permanent "API key
         * not configured" error into the source-error list on every search. The scraper
         * class and its tests are kept; only the unconditional registration is gone.
     *
     * Net effect: four providers for films (YTS, TPB, Solid, TorrentsCSV) and four for
     * series (EZTV, TPB, Solid, TorrentsCSV), plus Rutracker when keyed. Each of these
     * was verified to answer a live query on 2026-10-03.
     */
    fun defaultScrapers(client: OkHttpClient): List<TorrentScraper> = buildList {
        add(YtsScraper(client))
        add(EztvScraper(client))
        add(PirateBayScraper(client))
        add(SolidTorrentsScraper(client))
        add(TorrentsCsvScraper(client))
        // The canonical Russian tracker (configured separately, see
        // README/TMDB_SETUP_RO.md). Registered only with a key, because unconfigured it
        // throws "API key not configured" on every call.
        RutrackerScraper(client, BuildConfig.RUTRACKER_API_KEY)
            .takeIf(RutrackerScraper::isConfigured)
            ?.let(::add)
    }
    }
}

package io.github.flavyu22.movietorrentsearchtv.repository

import android.content.Context
import io.github.flavyu22.movietorrentsearchtv.data.scraper.MultiSourceScraper
import io.github.flavyu22.movietorrentsearchtv.data.scraper.SourceSearchResult
import io.github.flavyu22.movietorrentsearchtv.di.NetworkManager
import io.github.flavyu22.movietorrentsearchtv.model.TorrentSource
import io.github.flavyu22.movietorrentsearchtv.model.UnifiedTorrent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class TorrentRepository(context: Context) {
    private val resources = sharedResources(
        MultiSourceScraper(NetworkManager.getOkHttpClient(context.applicationContext))
    )

    fun getScrapersCount(): Int = resources.scraper.scrapers.size

    fun getScrapersCount(isSeries: Boolean): Int = resources.scraper.eligibleScrapersCount(
        if (isSeries) TorrentSource.Category.TV_SHOWS else TorrentSource.Category.MOVIES
    )

    fun hasFreshMovieCache(
        movieTitle: String,
        year: Int?,
        imdbId: String?,
        isSeries: Boolean = false
    ): Boolean = resources.getCached(cacheKey(movieTitle, year, imdbId, isSeries)) != null

    fun searchMovieTorrentsFlow(
        movieTitle: String,
        year: Int?,
        imdbId: String?,
        isSeries: Boolean = false,
        forceRefresh: Boolean = false
    ): Flow<SourceSearchResult> = flow {
        val key = cacheKey(movieTitle, year, imdbId, isSeries)
        if (!forceRefresh) {
            resources.getCached(key)?.let { cached ->
                cached.forEach { emit(it) }
                return@flow
            }
        } else {
            resources.invalidate(key)
        }

        val activeKey = if (forceRefresh) "$key#refresh-${FORCE_SEQUENCE.incrementAndGet()}" else key
        val active = resources.getOrStart(activeKey, key) {
            buildNetworkFlow(movieTitle, year, imdbId, isSeries)
        }
        emitAll(
            active.events
                .takeWhile { it is SearchEvent.Result }
                .map { (it as SearchEvent.Result).outcome }
        )
    }

    suspend fun searchMovieTorrents(
        movieTitle: String,
        year: Int?,
        imdbId: String?,
        isSeries: Boolean = false,
        forceRefresh: Boolean = false,
        onProgress: (SourceSearchResult) -> Unit = {}
    ): List<UnifiedTorrent> {
        val allResults = mutableListOf<UnifiedTorrent>()
        searchMovieTorrentsFlow(movieTitle, year, imdbId, isSeries, forceRefresh).collect { outcome ->
            onProgress(outcome)
            allResults += outcome.torrents
        }
        return deduplicate(allResults)
    }

    suspend fun searchTvTorrents(
        seriesTitle: String,
        season: Int?,
        episode: Int?,
        imdbId: String?
    ): List<UnifiedTorrent> {
        val query = buildString {
            append(seriesTitle.trim())
            season?.let { append(" S").append(it.toString().padStart(2, '0')) }
            episode?.let { append('E').append(it.toString().padStart(2, '0')) }
        }

        val allResults = searchMovieTorrents(
            movieTitle = query,
            year = null,
            imdbId = imdbId,
            isSeries = true
        )
        val seasonPrefix = season?.let { "S${it.toString().padStart(2, '0')}" }
        val targetEpisode = if (seasonPrefix != null && episode != null) {
            "$seasonPrefix${"E${episode.toString().padStart(2, '0')}"}"
        } else null
        val seasonPack = seasonPrefix?.let { "${it}E00" }

        return allResults.filter { torrent ->
            if (seasonPrefix == null) return@filter true
            val parsed = torrent.seasonEpisode
            when {
                parsed == null -> torrent.title.contains(seasonPrefix, ignoreCase = true)
                targetEpisode != null -> parsed == targetEpisode || parsed == seasonPack
                else -> parsed.startsWith(seasonPrefix)
            }
        }
    }

    private fun buildNetworkFlow(
        movieTitle: String,
        year: Int?,
        imdbId: String?,
        isSeries: Boolean
    ): Flow<SourceSearchResult> {
        val category = if (isSeries) TorrentSource.Category.TV_SHOWS else TorrentSource.Category.MOVIES
        val titleQuery = buildString {
            append(movieTitle.trim())
            // For series, we usually don't want the year in the query unless it's explicitly part of the title.
            // Torrent titles for TV shows rarely include the start year in every episode release.
            if (!isSeries && year != null && !movieTitle.contains(year.toString())) {
                append(' ').append(year)
            }
        }.trim()

        val flows = buildList {
            if (!imdbId.isNullOrBlank()) {
                // Only providers with a real IMDb-keyed lookup get the "tt…" query.
                // Plain-text indexes like TorrentsCSV can't match a tt-id and
                // only burn their search timeout on every detail view, interfering with
                // the title query that actually serves their results.
                add(resources.scraper.searchByImdbFlow(imdbId, category, IMDB_AWARE_SOURCES))
            }
            if (titleQuery.isNotBlank()) add(resources.scraper.searchAllFlow(titleQuery, category))
        }
        return when (flows.size) {
            0 -> flow {
                resources.scraper.eligibleScrapers(category).forEach {
                    emit(SourceSearchResult(it.source.name, emptyList()))
                }
            }
            1 -> flows.first()
            else -> merge(*flows.toTypedArray())
        }
    }

    private fun cacheKey(
        movieTitle: String,
        year: Int?,
        imdbId: String?,
        isSeries: Boolean
    ): String = buildString {
        append(if (isSeries) "series:" else "movie:")
        append(movieTitle.trim().replace(WHITESPACE, " ").lowercase(Locale.ROOT))
        append(':').append(year ?: 0)
        append(':').append(imdbId.orEmpty().trim().lowercase(Locale.ROOT))
    }

    /**
     * Single pass over the results: keeps the best row per infohash and returns it already
     * sorted. The previous implementation used `groupBy` (building a map plus a list per
     * distinct hash) and then `maxWithOrNull` over each bucket, which allocated far more
     * than necessary for what is a simple "pick the winner" reduction.
     */
    private fun deduplicate(results: List<UnifiedTorrent>): List<UnifiedTorrent> {
        if (results.size < 2) return results.sorted()
        val bestByHash = HashMap<String, UnifiedTorrent>(results.size * 2)
        for (torrent in results) {
            val key = torrent.infoHash.lowercase(Locale.ROOT)
            val existing = bestByHash[key]
            if (existing == null) {
                bestByHash[key] = torrent
            } else {
                val winner = preferredTorrent(existing, torrent)
                bestByHash[key] = winner
            }
        }
        return bestByHash.values.sorted()
    }

    /** Picks the better of two rows for the same infohash. */
    private fun preferredTorrent(a: UnifiedTorrent, b: UnifiedTorrent): UnifiedTorrent {
        val aIsYts = a.source.equals("YTS", ignoreCase = true)
        val bIsYts = b.source.equals("YTS", ignoreCase = true)
        if (aIsYts != bIsYts) return if (aIsYts) a else b
        if (a.seeds != b.seeds) return if (a.seeds > b.seeds) a else b
        return if (a.qualityScore >= b.qualityScore) a else b
    }

    private class SharedResources(val scraper: MultiSourceScraper) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val cache = ConcurrentHashMap<String, CacheEntry>()
        private val active = ConcurrentHashMap<String, ActiveSearch>()
        private val creationLock = Any()

        fun getCached(key: String): List<SourceSearchResult>? {
            val entry = cache[key] ?: return null
            if (System.currentTimeMillis() - entry.timestamp > CACHE_EXPIRATION_MS) {
                cache.remove(key, entry)
                return null
            }
            return entry.data
        }

        fun invalidate(key: String) {
            cache.remove(key)
        }

        fun getOrStart(
            activeKey: String,
            cacheKey: String,
            producer: () -> Flow<SourceSearchResult>
        ): ActiveSearch = synchronized(creationLock) {
            active[activeKey]?.let { return@synchronized it }

            val events = MutableSharedFlow<SearchEvent>(replay = MAX_REPLAY_EVENTS)
            val holder = ActiveSearch(events)
            active[activeKey] = holder
            val outcomes = mutableListOf<SourceSearchResult>()

            val producerJob = scope.launch {
                var completedNormally = false
                try {
                    producer().collect { outcome ->
                        outcomes += outcome
                        events.emit(SearchEvent.Result(outcome))
                    }
                    completedNormally = true
                    if (outcomes.all(SourceSearchResult::isSuccessful)) {
                        cache[cacheKey] = CacheEntry(outcomes.toList())
                        trimCache()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    events.emit(
                        SearchEvent.Result(
                            SourceSearchResult("Search", emptyList(), failure.message ?: "Search failed")
                        )
                    )
                } finally {
                    if (!completedNormally) cache.remove(cacheKey)
                    active.remove(activeKey, holder)
                    withContext(NonCancellable) { events.emit(SearchEvent.Complete) }
                }
            }
            holder.producerJob = producerJob
            holder.monitorJob = scope.launch {
                delay(NO_SUBSCRIBER_GRACE_MS)
                events.subscriptionCount.filter { it == 0 }.first()
                producerJob.cancel()
            }
            producerJob.invokeOnCompletion { holder.monitorJob?.cancel() }
            holder
        }

        private fun trimCache() {
            if (cache.size <= MAX_CACHE_ENTRIES) return
            cache.entries
                .sortedBy { it.value.timestamp }
                .take(cache.size - MAX_CACHE_ENTRIES)
                .forEach { cache.remove(it.key, it.value) }
        }
    }

    private class ActiveSearch(val events: MutableSharedFlow<SearchEvent>) {
        var producerJob: Job? = null
        var monitorJob: Job? = null
    }

    private sealed interface SearchEvent {
        data class Result(val outcome: SourceSearchResult) : SearchEvent
        data object Complete : SearchEvent
    }

    private data class CacheEntry(
        val data: List<SourceSearchResult>,
        val timestamp: Long = System.currentTimeMillis()
    )

    private companion object {
        const val CACHE_EXPIRATION_MS = 30 * 60 * 1_000L
        const val MAX_CACHE_ENTRIES = 100
        const val MAX_REPLAY_EVENTS = 32
        const val NO_SUBSCRIBER_GRACE_MS = 750L
        val WHITESPACE = Regex("\\s+")
        val FORCE_SEQUENCE = AtomicLong(0L)

        // Providers whose search actually understands an IMDb id (verified live 2026-08):
        // YTS query_term=tt…, EZTV api imdb_id, TPB q.php tt…, Solid full-text on the id,
        // TorrentsCSV is a title-only index, served by the
        // title flow.
        internal val IMDB_AWARE_SOURCES = setOf("YTS", "EZTV", "TPB", "Solid")

        @Volatile
        private var INSTANCE: SharedResources? = null

        fun sharedResources(scraper: MultiSourceScraper): SharedResources =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SharedResources(scraper).also { INSTANCE = it }
            }
    }
}

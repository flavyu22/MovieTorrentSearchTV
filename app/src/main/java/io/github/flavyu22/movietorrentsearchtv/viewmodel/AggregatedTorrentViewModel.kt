package io.github.flavyu22.movietorrentsearchtv.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.flavyu22.movietorrentsearchtv.api.RetrofitClient
import io.github.flavyu22.movietorrentsearchtv.data.metadata.WikipediaMetadataProvider
import io.github.flavyu22.movietorrentsearchtv.di.NetworkManager
import io.github.flavyu22.movietorrentsearchtv.model.Movie
import io.github.flavyu22.movietorrentsearchtv.model.UnifiedTorrent
import io.github.flavyu22.movietorrentsearchtv.repository.TorrentRepository
import io.github.flavyu22.movietorrentsearchtv.BuildConfig
import io.github.flavyu22.movietorrentsearchtv.util.TorrentMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

class AggregatedTorrentViewModel(application: Application) : AndroidViewModel(application) {
    @Immutable
    data class AggregatedUiState(
        val torrents: List<UnifiedTorrent> = emptyList(),
        val isLoading: Boolean = false,
        val isEmptyResult: Boolean = false,
        val sourceErrors: Map<String, String> = emptyMap(),
        val totalResultsCount: Int = 0,
        val availableQualities: List<String> = listOf("All"),
        val currentQuality: String = "All",
        val currentSort: SortOption = SortOption.DATE_DESC,
        val searchStats: SearchStats? = null
    )

    @Immutable
    data class SearchStats(
        val totalResults: Int,
        val durationMs: Long
    )

    enum class SortOption {
        QUALITY_DESC, SEEDS_DESC, SIZE_ASC, DATE_DESC, SOURCE
    }

    private data class TorrentInputs(
        val results: List<UnifiedTorrent>,
        val quality: String,
        val sort: SortOption,
        val languageFilter: Boolean
    )

    private val repository = TorrentRepository(application)

    /** Second multilingual metadata provider (Wikipedia/Wikidata, no API key). */
    private val wikipediaProvider =
        WikipediaMetadataProvider(NetworkManager.getOkHttpClient(application))
    private val _uiState = MutableStateFlow(AggregatedUiState())
    val uiState: StateFlow<AggregatedUiState> = _uiState.asStateFlow()

    private val rawResults = MutableStateFlow<List<UnifiedTorrent>>(emptyList())
    private val currentQualityFilter = MutableStateFlow("All")
    private val currentSortOption = MutableStateFlow(SortOption.DATE_DESC)
    private val languageFilterEnabled = MutableStateFlow(readTorrentLanguageFilterPref())
    private val searchGeneration = AtomicLong(0L)

    /** Current state of the "only torrents in the selected app language" filter. */
    val torrentLanguageFilterEnabled: StateFlow<Boolean> = languageFilterEnabled.asStateFlow()

    private val tmdbApiKey get() = getApplication<Application>()
        .getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        .getString("tmdb_api_key", BuildConfig.TMDB_API_KEY).orEmpty()
    private val tmdbConfigured get() = tmdbApiKey.isNotBlank()

    private var searchJob: Job? = null
    private var lastSearchedMovieId: Long? = null

    init {
        viewModelScope.launch {
            combine(rawResults, currentQualityFilter, currentSortOption) { results, quality, sort ->
                Triple(results, quality, sort)
            }
                .combine(languageFilterEnabled) { (results, quality, sort), languageFilter ->
                    TorrentInputs(results, quality, sort, languageFilter)
                }
                .mapLatest { (results, quality, sort, languageFilter) ->
                    filterAndSort(results, quality, sort, languageFilter)
                }
                // DistinctUntilChanged keeps a re-emission that produces an identical
                // FilteredTorrentResults (for example a source that reported no new rows) from
                // pushing a new _uiState instance and re-composing the whole details
                // screen for nothing.
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .catch { e ->
                    Log.e(TAG, "Error filtering results", e)
                }
                .collect { filtered ->
                    _uiState.update {
                        it.copy(
                            torrents = filtered.visible,
                            totalResultsCount = filtered.totalCount,
                            availableQualities = filtered.qualities
                        )
                    }
                }
        }
    }

    fun searchMovies(movie: Movie, forceRefresh: Boolean = false) {
        if (!forceRefresh && movie.id == lastSearchedMovieId &&
            (_uiState.value.isLoading || _uiState.value.searchStats != null)
        ) return

        lastSearchedMovieId = movie.id
        val generation = searchGeneration.incrementAndGet()
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            currentQualityFilter.value = "All"
            resetRawResults()
            updateIfCurrent(generation) {
                copy(
                    isLoading = true,
                    isEmptyResult = false,
                    torrents = emptyList(),
                    sourceErrors = emptyMap(),
                    totalResultsCount = 0,
                    availableQualities = listOf("All"),
                    currentQuality = "All",
                    searchStats = null
                )
            }

            val startedAt = System.currentTimeMillis()
            val sourceCounts = linkedMapOf<String, Int>()
            val sourceErrors = linkedMapOf<String, String>()
            var completedNormally = false

            try {
                val resolvedMovie = resolveImdbId(movie)
                ensureCurrent(generation)
                // Second metadata pass: when TMDB gave no distinct localized title,
                // resolve one through the keyless Wikipedia/Wikidata provider so the
                // localized-language magnet indexers can be queried too.
                val searchableMovie = enrichWithLocalizedTitle(resolvedMovie)
                ensureCurrent(generation)
                val cleanTitle = cleanTitle(searchableMovie.title)
                    .ifBlank { cleanTitle(searchableMovie.originalTitle) }
                val originalTitle = cleanTitle(searchableMovie.originalTitle)
                val localizedTitle = cleanTitle(searchableMovie.localizedTitle)
                val language = appLanguage()

                val searchFlows = buildList {
                    add(
                        repository.searchMovieTorrentsFlow(
                            movieTitle = cleanTitle,
                            year = searchableMovie.year,
                            imdbId = searchableMovie.imdbCode,
                            isSeries = searchableMovie.isSeries,
                            forceRefresh = forceRefresh
                        )
                    )

                    localizedSearchSuffix(language)?.let { suffix ->
                        if (cleanTitle.isNotBlank()) {
                            add(
                                repository.searchMovieTorrentsFlow(
                                    movieTitle = "$cleanTitle $suffix multi",
                                    year = searchableMovie.year,
                                    imdbId = null,
                                    isSeries = searchableMovie.isSeries,
                                    forceRefresh = forceRefresh
                                )
                            )
                        }
                    }

                    if (originalTitle.isNotBlank() && !originalTitle.equals(cleanTitle, ignoreCase = true)) {
                        add(
                            repository.searchMovieTorrentsFlow(
                                movieTitle = originalTitle,
                                year = searchableMovie.year,
                                imdbId = null,
                                isSeries = searchableMovie.isSeries,
                                forceRefresh = forceRefresh
                            )
                        )
                    }

                    // TMDB or Wikipedia localized title (e.g. the Spanish name when the
                    // app language is Spanish). It differs from the display/original
                    // title for translated works, so it is sent as an extra query to
                    // the magnet-link providers (notably the Spanish-language indexers)
                    // to surface localized releases.
                    if (localizedTitle.isNotBlank() &&
                        !localizedTitle.equals(cleanTitle, ignoreCase = true) &&
                        !localizedTitle.equals(originalTitle, ignoreCase = true)
                    ) {
                        add(
                            repository.searchMovieTorrentsFlow(
                                movieTitle = localizedTitle,
                                year = searchableMovie.year,
                                imdbId = null,
                                isSeries = searchableMovie.isSeries,
                                forceRefresh = forceRefresh
                            )
                        )
                    }
                }

                merged(searchFlows).collect { outcome ->
                    ensureCurrent(generation)
                    sourceCounts[outcome.source] = maxOf(
                        sourceCounts[outcome.source] ?: 0,
                        outcome.torrents.size
                    )
                    if (outcome.errorMessage == null) {
                        sourceErrors.remove(outcome.source)
                    } else if (sourceCounts[outcome.source] == 0) {
                        sourceErrors[outcome.source] = outcome.errorMessage
                    }

                    updateIfCurrent(generation) {
                        copy(sourceErrors = sourceErrors.toMap())
                    }

                    if (outcome.torrents.isNotEmpty()) {
                        val series = searchableMovie.isSeries
                        val relevant = withContext(Dispatchers.Default) {
                            outcome.torrents.asSequence()
                                .filter { TorrentMatcher.isTorrentRelevant(it, searchableMovie) }
                                // Only copy when the flag actually differs. `copy` builds a
                                // brand-new instance and therefore discards the memoized
                                // size/quality/date/sort-title keys, forcing every sort
                                // comparator to re-parse them afterwards. The overwhelming
                                // majority of rows already carry the correct value.
                                .map { if (it.isSeries == series) it else it.copy(isSeries = series) }
                                .toList()
                        }
                        ensureCurrent(generation)
                        if (relevant.isNotEmpty()) mergeRawResults(relevant)
                    }
                }
                completedNormally = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "Torrent search failed", failure)
                sourceErrors["Search"] = failure.message ?: "Search failed"
            } finally {
                if (generation == searchGeneration.get()) {
                    val finalCount = rawResults.value.size
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isEmptyResult = completedNormally && finalCount == 0,
                            sourceErrors = sourceErrors.toMap(),
                            searchStats = SearchStats(
                                totalResults = finalCount,
                                durationMs = System.currentTimeMillis() - startedAt
                            )
                        )
                    }
                }
            }
        }
    }

    /** Toggles the "only torrents in the selected app language" filter. */
    fun setTorrentLanguageFilter(enabled: Boolean) {
        languageFilterEnabled.value = enabled
        getApplication<Application>().getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(TORRENT_LANGUAGE_FILTER, enabled) }
    }

    private fun readTorrentLanguageFilterPref(): Boolean =
        getApplication<Application>()
            .getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .getBoolean(TORRENT_LANGUAGE_FILTER, false)

    fun filterByQuality(quality: String) {
        if (quality !in _uiState.value.availableQualities) return
        currentQualityFilter.value = quality
        _uiState.update { it.copy(currentQuality = quality) }
    }

    fun sortBy(option: SortOption) {
        currentSortOption.value = option
        _uiState.update { it.copy(currentSort = option) }
    }

    private suspend fun resolveImdbId(movie: Movie): Movie {
        if (!movie.imdbCode.isNullOrBlank() || movie.tmdbId == null ||
            !tmdbConfigured
        ) return movie
        return try {
            val mediaType = if (movie.isSeries) "tv" else "movie"
            val externalIds = withContext(Dispatchers.IO) {
                RetrofitClient.getInstance(getApplication()).getTmdbExternalIds(
                    mediaType = mediaType,
                    tmdbId = movie.tmdbId,
                    apiKey = tmdbApiKey,
                )
            }
            movie.copy(imdbCode = externalIds.imdbId?.takeIf { it.matches(IMDB_ID) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            movie
        }
    }

    /**
     * Second multilingual metadata pass. When TMDB did not supply a localized title
     * that differs from the display/original titles, resolve one from the matching
     * language edition of Wikipedia through the keyless Wikidata provider, so the
     * localized-language magnet indexers can be queried too.
     *
     * Strictly best-effort: any failure leaves [movie] unchanged and never delays
     * or breaks the torrent search beyond a bounded 6s lookup.
     */
    private suspend fun enrichWithLocalizedTitle(movie: Movie): Movie {
        val language = appLanguage()
        if (language.isEmpty() || language == "EN") return movie

        val displayTitle = cleanTitle(movie.title)
        val originalTitle = cleanTitle(movie.originalTitle)
        val baseTitle = displayTitle.ifBlank { originalTitle }
        if (baseTitle.isBlank()) return movie

        // TMDB already provided a distinct localized name for this language — keep it.
        val existing = cleanTitle(movie.localizedTitle)
        if (existing.isNotBlank() &&
            !existing.equals(displayTitle, ignoreCase = true) &&
            !existing.equals(originalTitle, ignoreCase = true)
        ) return movie

        val info = fetchWikipediaTitle(baseTitle, language)
            ?: originalTitle.takeIf {
                it.isNotBlank() && !it.equals(baseTitle, ignoreCase = true)
            }?.let { fetchWikipediaTitle(it, language) }
            ?: return movie

        val wikiTitle = info.localizedTitle.trim()
        if (wikiTitle.isBlank() ||
            wikiTitle.equals(displayTitle, ignoreCase = true) ||
            wikiTitle.equals(originalTitle, ignoreCase = true)
        ) return movie

        Log.d(TAG, "Wikipedia localized \"$baseTitle\" -> \"$wikiTitle\" (${info.language})")
        return movie.copy(localizedTitle = wikiTitle)
    }

    private suspend fun fetchWikipediaTitle(title: String, language: String) =
        try {
            wikipediaProvider.fetchLocalizedTitle(title, language)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    /**
     * Accumulates deduplicated results by lower-cased infohash.
     *
     * The previous implementation rebuilt the map from scratch on every incoming source
     * (`(existing + incoming).groupBy { ... }`), which is O(total) per source and therefore
     * O(n²) across a full multi-source search. Keeping a live index makes each merge O(k)
     * in the number of *new* rows, so the whole search becomes linear.
     *
     * Only touched from the single search collector coroutine, so a plain LinkedHashMap
     * is sufficient and preserves a stable, deterministic ordering for the UI.
     */
    private val mergedResultsByHash = LinkedHashMap<String, UnifiedTorrent>()

    private fun mergeRawResults(incoming: List<UnifiedTorrent>) {
        var changed = false
        for (torrent in incoming) {
            val key = torrent.infoHash.lowercase(Locale.ROOT)
            val existing = mergedResultsByHash[key]
            if (existing == null) {
                mergedResultsByHash[key] = torrent
                changed = true
            } else {
                val winner = preferredTorrent(listOf(existing, torrent)) ?: existing
                if (winner !== existing) {
                    mergedResultsByHash[key] = winner
                    changed = true
                }
            }
        }
        if (!changed) return
        // A fresh immutable snapshot is required: StateFlow only emits on a new instance,
        // and downstream sorting must never observe the list while it is being mutated.
        rawResults.value = mergedResultsByHash.values.toList()
    }

    /** Drops every accumulated row; called when a new search starts. */
    private fun resetRawResults() {
        mergedResultsByHash.clear()
        rawResults.value = emptyList()
    }

    private fun preferredTorrent(duplicates: List<UnifiedTorrent>): UnifiedTorrent? =
        duplicates.maxWithOrNull(
            compareBy<UnifiedTorrent> { it.source.equals("YTS", ignoreCase = true) }
                .thenBy { it.seeds }
                .thenBy { it.qualityScore }
        )

    /**
     * Thin Android-aware wrapper around [TorrentResultFilter]: it resolves the app
     * language from preferences and hands the pure pass everything else it needs.
     * The filtering/sorting rules themselves are unit-tested in `TorrentResultFilterTest`.
     */
    private fun filterAndSort(
        baseResults: List<UnifiedTorrent>,
        targetQuality: String,
        sort: SortOption,
        languageFilter: Boolean
    ): FilteredTorrentResults = TorrentResultFilter.filterAndSort(
        baseResults = baseResults,
        targetQuality = targetQuality,
        sort = sort,
        languageFilter = languageFilter,
        appLanguage = if (languageFilter) appLanguage() else ""
    )

    private fun updateIfCurrent(
        generation: Long,
        block: AggregatedUiState.() -> AggregatedUiState
    ) {
        _uiState.update { current ->
            if (generation == searchGeneration.get()) current.block() else current
        }
    }

    private suspend fun ensureCurrent(generation: Long) {
        coroutineContext.ensureActive()
        if (generation != searchGeneration.get()) throw CancellationException("Superseded torrent search")
    }

    private fun appLanguage(): String = getApplication<Application>()
        .getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        .getString(APP_LANGUAGE, "EN")
        .orEmpty()
        .uppercase(Locale.ROOT)

    private fun localizedSearchSuffix(language: String): String? = when (language) {
        "RO" -> "romanian"
        "ES" -> "spanish"
        "FR" -> "french"
        "IT" -> "italian"
        "DE" -> "german"
        "RU" -> "russian"
        else -> null
    }

    private fun cleanTitle(title: String?): String = title.orEmpty()
        .replace(NON_TITLE_CHARS, " ")
        .replace(WHITESPACE, " ")
        .trim()

    private fun merged(flows: List<Flow<io.github.flavyu22.movietorrentsearchtv.data.scraper.SourceSearchResult>>) =
        if (flows.size == 1) flows.first() else merge(*flows.toTypedArray())

    private companion object {
        const val TAG = "AggregatedTorrentVM"
        const val APP_PREFS = "app_prefs"
        const val APP_LANGUAGE = "app_language"
        const val TORRENT_LANGUAGE_FILTER = "torrent_language_filter"
        val IMDB_ID = Regex("tt\\d{5,12}", RegexOption.IGNORE_CASE)
        val NON_TITLE_CHARS = Regex("[^\\p{L}\\p{N}\\s]")
        val WHITESPACE = Regex("\\s+")
    }
}

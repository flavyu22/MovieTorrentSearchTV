package io.github.flavyu22.movietorrentsearchtv.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.flavyu22.movietorrentsearchtv.api.RetrofitClient
import io.github.flavyu22.movietorrentsearchtv.config.RemoteHosts
import io.github.flavyu22.movietorrentsearchtv.model.Movie
import io.github.flavyu22.movietorrentsearchtv.model.TmdbSeries
import io.github.flavyu22.movietorrentsearchtv.model.Torrent
import io.github.flavyu22.movietorrentsearchtv.repository.TorrserverEndpoint
import io.github.flavyu22.movietorrentsearchtv.repository.TorrserverRepository
import io.github.flavyu22.movietorrentsearchtv.util.RemoteUrlPolicy
import io.github.flavyu22.movietorrentsearchtv.util.SeriesCatalogBuilder
import io.github.flavyu22.movietorrentsearchtv.util.TmdbLocale
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.annotations.SerializedName
import io.github.flavyu22.movietorrentsearchtv.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

class MovieViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    enum class CatalogueIssue {
        NETWORK,
        TMDB_NOT_CONFIGURED,
        PARTIAL_RESULTS,
    }

    data class MovieUiState(
        val movies: List<Movie> = emptyList(),
        val isLoading: Boolean = false,
        val isEmptyResult: Boolean = false,
        val currentPage: Int = 1,
        val isSeriesMode: Boolean = false,
        val isHistoryMode: Boolean = false,
        val isFavoritesMode: Boolean = false,
        val selectedGenre: String = "All",
        val selectedYear: String = "All",
        val selectedQuality: String = "All",
        val selectedRating: String = "All",
        val searchQuery: String = "",
        val lastClickedMovieId: Long? = null,
        val error: Boolean = false,
        val errorMessage: String? = null,
        val catalogueIssue: CatalogueIssue? = null,
    )

    private data class HistoryEnvelope(
        @SerializedName("version") val version: Int = HISTORY_FORMAT_VERSION,
        @SerializedName("movies") val movies: List<Movie> = emptyList(),
    )

    private sealed interface SourceFetch<out T> {
        data class Success<T>(val value: T) : SourceFetch<T>
        data class Failure(val cause: Exception) : SourceFetch<Nothing>
    }

    private data class CataloguePage(
        val movies: List<Movie>,
        val issue: CatalogueIssue? = null,
    )

    private val gson = Gson()
    private val settingsPrefs = application.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    private val appPrefs = application.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
    private val historyPrefs = application.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
    private val favoritesPrefs = application.getSharedPreferences(FAVORITES_PREFS, Context.MODE_PRIVATE)
    private val selectionPrefs = application.getSharedPreferences(SELECTION_PREFS, Context.MODE_PRIVATE)
    private val searchHistoryPrefs = application.getSharedPreferences(SEARCH_HISTORY_PREFS, Context.MODE_PRIVATE)
    private val apiService by lazy { RetrofitClient.getInstance(application) }
    private val apiCache = LruCache<String, List<Movie>>(API_CACHE_ENTRIES)
    private val searchGeneration = AtomicLong(0L)
    private val historyGeneration = AtomicLong(0L)
    private val favoritesGeneration = AtomicLong(0L)
    private val selectionGeneration = AtomicLong(0L)
    private val searchHistoryGeneration = AtomicLong(0L)
    private val historyWriteMutex = Mutex()
    private val favoritesWriteMutex = Mutex()
    private val selectionWriteMutex = Mutex()
    private val searchHistoryWriteMutex = Mutex()

    private val tmdbApiKey get() = appPrefs.getString("tmdb_api_key", BuildConfig.TMDB_API_KEY).orEmpty()
    private val tmdbConfigured get() = tmdbApiKey.isNotBlank()

    private val torrserverRepository = TorrserverRepository(application)
    private var searchJob: Job? = null

    private val _uiState = MutableStateFlow(
        MovieUiState(
            searchQuery = savedStateHandle[KEY_SEARCH_QUERY] ?: "",
            currentPage = savedStateHandle[KEY_CURRENT_PAGE] ?: 1,
            selectedGenre = savedStateHandle[KEY_SELECTED_GENRE] ?: "All",
            selectedYear = savedStateHandle[KEY_SELECTED_YEAR] ?: "All",
            selectedQuality = savedStateHandle[KEY_SELECTED_QUALITY] ?: "All",
            selectedRating = savedStateHandle[KEY_SELECTED_RATING] ?: "All",
            isSeriesMode = savedStateHandle[KEY_SERIES_MODE] ?: false,
            isHistoryMode = savedStateHandle[KEY_HISTORY_MODE] ?: false,
            isFavoritesMode = savedStateHandle[KEY_FAVORITES_MODE] ?: false,
        )
    )
    val uiState: StateFlow<MovieUiState> = _uiState.asStateFlow()

    val movies get() = _uiState.value.movies
    val isLoading get() = _uiState.value.isLoading
    val currentPage get() = _uiState.value.currentPage
    val isHistoryMode get() = _uiState.value.isHistoryMode
    val isFavoritesMode get() = _uiState.value.isFavoritesMode
    val isSeriesMode get() = _uiState.value.isSeriesMode
    val selectedGenre get() = _uiState.value.selectedGenre
    val selectedYear get() = _uiState.value.selectedYear
    val selectedQuality get() = _uiState.value.selectedQuality
    val selectedRating get() = _uiState.value.selectedRating
    val searchQuery get() = _uiState.value.searchQuery
    val lastClickedMovieId get() = _uiState.value.lastClickedMovieId

    private val _pendingUrl = MutableStateFlow(savedStateHandle.get<String>(KEY_PENDING_URL))
    fun setPendingUrl(url: String) {
        if (url.length !in 1..MAX_PENDING_URL_CHARS || url.any(Char::isISOControl)) return
        _pendingUrl.value = url
        savedStateHandle[KEY_PENDING_URL] = url
    }

    fun consumePendingUrl(): String? = _pendingUrl.value.also {
        _pendingUrl.value = null
        savedStateHandle.remove<String>(KEY_PENDING_URL)
    }

    private val _torrserverAddress = MutableStateFlow(
        TorrserverEndpoint.normalize(
            settingsPrefs.getString(TORRSERVER_ADDRESS, "").orEmpty(),
        ).orEmpty()
    )
    val torrserverAddress = _torrserverAddress.asStateFlow()

    private val _movieHistory = MutableStateFlow<List<Movie>>(emptyList())
    private val _favoriteMovies = MutableStateFlow<List<Movie>>(emptyList())
    private val _favoriteMovieIds = MutableStateFlow<Set<Long>>(emptySet())
    val favoriteMovieIds: StateFlow<Set<Long>> = _favoriteMovieIds.asStateFlow()
    private val _selectedMovie = MutableStateFlow(loadSelectedMovieFromPrefs())
    private val _searchHistory = MutableStateFlow(loadSearchHistoryFromPrefs())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == TORRSERVER_ADDRESS) {
            _torrserverAddress.value = TorrserverEndpoint.normalize(
                settingsPrefs.getString(TORRSERVER_ADDRESS, "").orEmpty(),
            ).orEmpty()
        }
    }

    init {
        settingsPrefs.registerOnSharedPreferenceChangeListener(settingsListener)
        // Older versions stored an entire remote Movie graph in the saved-state Bundle.
        // Drop that migration key to avoid TransactionTooLarge during process state save.
        savedStateHandle.remove<String>(KEY_SELECTED_MOVIE)
        // Local collections can be sizeable; parse them away from first composition.
        viewModelScope.launch(Dispatchers.IO) {
            val restoredHistory = loadHistoryFromPrefs()
            val restoredFavorites = loadFavoritesFromPrefs()
            withContext(Dispatchers.Main.immediate) {
                if (historyGeneration.get() == 0L) _movieHistory.value = restoredHistory
                if (favoritesGeneration.get() == 0L) {
                    _favoriteMovies.value = restoredFavorites
                    _favoriteMovieIds.value = restoredFavorites.mapTo(linkedSetOf(), Movie::id)
                }
                when {
                    _uiState.value.isFavoritesMode -> updateState {
                        copy(movies = _favoriteMovies.value, isEmptyResult = _favoriteMovies.value.isEmpty())
                    }
                    _uiState.value.isHistoryMode -> updateState {
                        copy(movies = _movieHistory.value, isEmptyResult = _movieHistory.value.isEmpty())
                    }
                }
            }
        }
        tryAutoDiscoverTorrserver()
    }

    private fun tryAutoDiscoverTorrserver() {
        if (_torrserverAddress.value.isNotBlank()) return
        val selectionGeneration = settingsPrefs.getLong(TORRSERVER_SELECTION_REVISION, 0L)
        // An explicitly cleared address remains disabled across cold starts.
        if (selectionGeneration > 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            val loopback = "http://127.0.0.1:8090"
            val localStatus = torrserverRepository.checkServerStatus(loopback)
            if (localStatus != null) {
                withContext(Dispatchers.Main.immediate) {
                    if (selectionGeneration == settingsPrefs.getLong(TORRSERVER_SELECTION_REVISION, 0L) &&
                        settingsPrefs.getString(TORRSERVER_ADDRESS, "").isNullOrBlank()
                    ) saveTorrserverAddress(loopback)
                }
                return@launch
            }

            if (BuildConfig.ALLOW_LAN_CLEARTEXT) {
                val discovered = torrserverRepository.scanNetwork()
                val firstValid = discovered.firstOrNull { it.isOnline }
                if (firstValid != null) {
                    val address = firstValid.address ?: "http://${firstValid.ip}:${firstValid.port}"
                    withContext(Dispatchers.Main.immediate) {
                        if (selectionGeneration == settingsPrefs.getLong(TORRSERVER_SELECTION_REVISION, 0L) &&
                            settingsPrefs.getString(TORRSERVER_ADDRESS, "").isNullOrBlank()
                        ) saveTorrserverAddress(address)
                    }
                }
            }
        }
    }

    fun updateSearchQuery(query: String) = updateState {
        copy(searchQuery = query.take(MAX_SEARCH_QUERY_CHARS))
    }

    fun searchMovies(
        query: String,
        genre: String? = null,
        year: String? = null,
        quality: String? = null,
        rating: String? = null,
        page: Int = 1,
        isSeries: Boolean = _uiState.value.isSeriesMode
    ) {
        updateState {
            copy(
                searchQuery = query.take(MAX_SEARCH_QUERY_CHARS),
                selectedGenre = genre?.take(MAX_FILTER_CHARS) ?: "All",
                selectedYear = year?.take(MAX_FILTER_CHARS) ?: "All",
                selectedQuality = quality?.take(MAX_FILTER_CHARS) ?: "All",
                selectedRating = rating?.take(MAX_FILTER_CHARS) ?: "All",
                isSeriesMode = isSeries,
                isHistoryMode = false,
                isFavoritesMode = false,
                currentPage = normalizePage(page)
            )
        }
        if (query.isNotBlank()) recordSearchQuery(query)
        executeSearch()
    }

    fun loadPopularMovies(page: Int = 1, isSeries: Boolean = _uiState.value.isSeriesMode) {
        updateState {
            copy(
                searchQuery = "",
                isSeriesMode = isSeries,
                isHistoryMode = false,
                isFavoritesMode = false,
                currentPage = normalizePage(page),
                selectedGenre = "All",
                selectedYear = "All",
                selectedQuality = "All",
                selectedRating = "All"
            )
        }
        executeSearch()
    }

    fun goToNextPage() {
        if (_uiState.value.currentPage >= MAX_CATALOGUE_PAGE) return
        updateState { copy(currentPage = normalizePage(currentPage + 1)) }
        executeSearch()
    }

    fun goToPreviousPage() {
        if (_uiState.value.currentPage <= 1) return
        updateState { copy(currentPage = currentPage - 1) }
        executeSearch()
    }

    fun retrySearch() = executeSearch(forceRefresh = true)

    private fun executeSearch(forceRefresh: Boolean = false) {
        val state = _uiState.value
        val generation = searchGeneration.incrementAndGet()
        searchJob?.cancel()

        if (state.isHistoryMode || state.isFavoritesMode) {
            val localMovies = if (state.isFavoritesMode) _favoriteMovies.value else _movieHistory.value
            updateState {
                copy(
                    movies = localMovies,
                    isLoading = false,
                    isEmptyResult = localMovies.isEmpty(),
                    error = false,
                    errorMessage = null,
                    catalogueIssue = null,
                )
            }
            return
        }

        val cacheKey = cacheKey(state)
        if (!forceRefresh) {
            apiCache.get(cacheKey)?.let { cached ->
                updateIfCurrent(generation) {
                    copy(
                        movies = cached,
                        isLoading = false,
                        isEmptyResult = cached.isEmpty(),
                        error = false,
                        errorMessage = null,
                        catalogueIssue = null,
                    )
                }
                return
            }
        } else {
            apiCache.remove(cacheKey)
        }

        updateIfCurrent(generation) {
            copy(
                isLoading = true,
                isEmptyResult = false,
                error = false,
                errorMessage = null,
                catalogueIssue = null,
            )
        }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val page = if (state.isSeriesMode) fetchSeries(state) else fetchMovies(state)
                ensureCurrent(generation)
                if (page.issue == null) apiCache.put(cacheKey, page.movies)
                updateIfCurrent(generation) {
                    copy(
                        movies = page.movies,
                        isLoading = false,
                        isEmptyResult = page.movies.isEmpty(),
                        error = false,
                        errorMessage = null,
                        catalogueIssue = page.issue,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (missingConfiguration: TmdbNotConfiguredException) {
                updateIfCurrent(generation) {
                    copy(
                        isLoading = false,
                        isEmptyResult = false,
                        error = true,
                        errorMessage = null,
                        catalogueIssue = CatalogueIssue.TMDB_NOT_CONFIGURED,
                    )
                }
            } catch (failure: Exception) {
                Log.e(TAG, "Movie search failed", failure)
                updateIfCurrent(generation) {
                    copy(
                        isLoading = false,
                        isEmptyResult = false,
                        error = true,
                        errorMessage = null,
                        catalogueIssue = CatalogueIssue.NETWORK,
                    )
                }
            }
        }
    }

    private suspend fun fetchSeries(state: MovieUiState): CataloguePage =
        withTimeoutOrNull(TMDB_TOTAL_TIMEOUT_MS) {
            fetchSeriesWithinTimeout(state)
        } ?: throw IOException("TMDB catalogue request timed out")

    /**
     * Fallback series catalogue used when TMDB is not configured: a text query is
     * searched across The Pirate Bay and restricted to the TV-show category, while
     * an empty query browses the TV category directly. Rows are grouped per show
     * (no posters or ratings are available from this source).
     */
    private suspend fun fetchTpbSeriesFallback(state: MovieUiState): List<Movie> =
        withTimeoutOrNull(YTS_TOTAL_TIMEOUT_MS) {
            val query = state.searchQuery.trim()
            val response = withRetry {
                apiService.searchPirateBay(query.ifBlank { "category:205" })
            }
            SeriesCatalogBuilder.buildCatalogue(response, query, PAGE_SIZE)
                .ifEmpty { throw SearchUnavailableException() }
        } ?: throw IOException("TPB series catalogue request timed out")

    private suspend fun fetchSeriesWithinTimeout(state: MovieUiState): CataloguePage = coroutineScope {
        if (!tmdbConfigured) {
            // Without a TMDB key the series catalogue would be a blank error screen.
            // The Pirate Bay TV category still yields a usable, poster-less fallback
            // listing the shows behind the most recent TV releases.
            return@coroutineScope CataloguePage(
                movies = fetchTpbSeriesFallback(state),
                issue = CatalogueIssue.TMDB_NOT_CONFIGURED,
            )
        }
        val startPage = (state.currentPage - 1) * TMDB_PAGES_PER_APP_PAGE + 1
        val outcomes = (0 until TMDB_PAGES_PER_APP_PAGE).map { offset ->
            async {
                captureSource {
                    val response = withRetry {
                        if (state.searchQuery.isNotBlank()) {
                            apiService.searchTmdbSeries(
                                apiKey = tmdbApiKey,
                                query = state.searchQuery.trim(),
                                page = startPage + offset,
                                year = selectedYear(state),
                                language = tmdbLanguage()
                            )
                        } else {
                            apiService.discoverTmdbSeries(
                                apiKey = tmdbApiKey,
                                page = startPage + offset,
                                year = selectedYear(state),
                                genre = Mapper.genreId(state.selectedGenre, true)?.toString(),
                                voteAverageGte = selectedRating(state),
                                voteCountGte = null, // Eliminat complet pragul de voturi pentru a garanta rezultate
                                dateLte = null,
                                sortBy = "popularity.desc",
                                language = tmdbLanguage()
                            )
                        }
                    }
                    response.results.orEmpty()
                        .asSequence()
                        .take(MAX_CATALOGUE_RESPONSE_ITEMS)
                        .filter { Mapper.matchesTmdbFilters(it, state, true) }
                        .mapNotNull { sanitizeCatalogueMovie(Mapper.mapTmdbToMovie(it, true)) }
                        .toList()
                }
            }
        }.awaitAll()
        val movies = successfulValuesOrThrow(outcomes)
            .flatten()
            .distinctBy(Movie::id)
            .take(PAGE_SIZE)
        CataloguePage(
            movies = movies,
            issue = CatalogueIssue.PARTIAL_RESULTS.takeIf {
                outcomes.any { outcome -> outcome is SourceFetch.Failure }
            },
        )
    }

    private suspend fun fetchMovies(state: MovieUiState): CataloguePage = coroutineScope {
        val ytsDeferred = async { captureSource { fetchYtsMovies(state) } }
        val tmdbDeferred = if (state.selectedQuality != "All") {
            null
        } else async {
            if (tmdbConfigured) captureSource {
                withTimeoutOrNull(TMDB_TOTAL_TIMEOUT_MS) { fetchTmdbMovies(state) }
                    ?: throw IOException("TMDB catalogue request timed out")
            }
            else SourceFetch.Failure(TmdbNotConfiguredException())
        }
        val ytsOutcome = ytsDeferred.await()
        val tmdbOutcome = tmdbDeferred?.await()
        if (ytsOutcome is SourceFetch.Failure &&
            (tmdbOutcome == null || tmdbOutcome is SourceFetch.Failure)
        ) {
            throw SearchUnavailableException()
        }

        val yts = (ytsOutcome as? SourceFetch.Success)?.value.orEmpty()
        val tmdb = (tmdbOutcome as? SourceFetch.Success)?.value.orEmpty()
        val movies = if (state.searchQuery.isBlank()) {
            mergeCatalogues(yts, tmdb)
        } else {
            mergeCatalogues(tmdb, yts)
        }
        val issue = when {
            tmdbOutcome is SourceFetch.Failure &&
                tmdbOutcome.cause is TmdbNotConfiguredException &&
                ytsOutcome is SourceFetch.Success -> CatalogueIssue.TMDB_NOT_CONFIGURED
            ytsOutcome is SourceFetch.Failure || tmdbOutcome is SourceFetch.Failure ->
                CatalogueIssue.PARTIAL_RESULTS
            else -> null
        }
        CataloguePage(movies = movies.take(PAGE_SIZE), issue = issue)
    }

    private suspend fun fetchYtsMovies(state: MovieUiState): List<Movie> =
        withTimeoutOrNull(YTS_TOTAL_TIMEOUT_MS) {
            raceYtsMirrors(state)
        } ?: throw IOException("YTS catalogue request timed out")

    /**
     * Queries every YTS mirror in parallel and returns the first successful
     * response. The previous sequential failover waited up to
     * YTS_MIRROR_TIMEOUT_MS on each dead mirror before ever reaching a healthy
     * one (up to three timeouts in a row); racing the mirrors bounds the wait
     * to the slowest live mirror instead of the sum of all timeouts.
     */
    private suspend fun raceYtsMirrors(state: MovieUiState): List<Movie> = coroutineScope {
        // Populated only after every deferred below has settled, so a plain
        // list is sufficient (no concurrent writes remain in flight).
        val failures = mutableListOf<Exception>()
        val deferreds = YTS_DOMAINS.map { domain ->
            async {
                try {
                    fetchYtsFromMirror(domain, state)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failures.add(failure)
                    null
                }
            }
        }
        try {
            val pending = deferreds.toMutableList()
            while (pending.isNotEmpty()) {
                val winner = select {
                    pending.forEach { deferred ->
                        deferred.onAwait { deferred to it }
                    }
                }
                pending.remove(winner.first)
                winner.second?.let { movies -> return@coroutineScope movies }
            }
            // Every mirror failed — surface the most recent real failure so the
            // error message stays as informative as the sequential implementation.
            throw failures.lastOrNull() ?: SearchUnavailableException()
        } finally {
            // Cancel the losing mirrors immediately; coroutineScope would
            // otherwise wait for every remaining request to finish.
            deferreds.forEach { it.cancel() }
        }
    }

    private suspend fun fetchYtsFromMirror(
        domain: String,
        state: MovieUiState,
    ): List<Movie> {
        val query = state.searchQuery.trim()
        val year = selectedYear(state)
        val finalQuery = when {
            query.isNotEmpty() && year != null -> "$query $year"
            query.isNotEmpty() -> query
            else -> null
        }
        val response = withTimeoutOrNull(YTS_MIRROR_TIMEOUT_MS) {
            apiService.searchMovies(
                url = "$domain/api/v2/list_movies.json",
                query = finalQuery,
                genre = state.selectedGenre.takeUnless { it == "All" }
                    ?.lowercase(Locale.ROOT),
                quality = state.selectedQuality.takeUnless { it == "All" },
                minimumRating = selectedRating(state)?.toInt(),
                page = state.currentPage,
                limit = PAGE_SIZE,
                sortBy = if (state.searchQuery.isBlank()) "date_added" else "year",
                orderBy = "desc",
            )
        } ?: throw IOException("YTS mirror request timed out")
        if (!response.status.equals("ok", ignoreCase = true)) {
            throw IOException("YTS mirror returned a non-success status")
        }
        return response.data?.movies.orEmpty()
            .asSequence()
            .take(MAX_CATALOGUE_RESPONSE_ITEMS)
            .mapNotNull { sanitizeCatalogueMovie(it, requirePositiveId = true) }
            .filter { Mapper.matchesYtsFilters(it, state) }
            .toList()
    }

    private suspend fun fetchTmdbMovies(state: MovieUiState): List<Movie> = coroutineScope {
        val startPage = (state.currentPage - 1) * TMDB_PAGES_PER_APP_PAGE + 1
        val outcomes = (0 until TMDB_PAGES_PER_APP_PAGE).map { offset ->
            async {
                captureSource {
                    val response = withRetry {
                        if (state.searchQuery.isNotBlank()) {
                            apiService.searchTmdbMovies(
                                apiKey = tmdbApiKey,
                                query = state.searchQuery.trim(),
                                page = startPage + offset,
                                year = selectedYear(state),
                                language = tmdbLanguage()
                            )
                        } else {
                            apiService.discoverTmdbMovies(
                                apiKey = tmdbApiKey,
                                page = startPage + offset,
                                year = selectedYear(state),
                                genre = Mapper.genreId(state.selectedGenre, false)?.toString(),
                                voteAverageGte = selectedRating(state),
                                voteCountGte = MIN_MOVIE_VOTE_COUNT,
                                releaseDateLte = todayUtc(),
                                sortBy = "popularity.desc",
                                language = tmdbLanguage()
                            )
                        }
                    }
                    response.results.orEmpty()
                        .asSequence()
                        .take(MAX_CATALOGUE_RESPONSE_ITEMS)
                        .filter { Mapper.matchesTmdbFilters(it, state, false) }
                        .mapNotNull { sanitizeCatalogueMovie(Mapper.mapTmdbToMovie(it, false)) }
                        .toList()
                }
            }
        }.awaitAll()
        successfulValuesOrThrow(outcomes).flatten()
    }

    fun loadHistoryMovies() {
        searchGeneration.incrementAndGet()
        searchJob?.cancel()
        updateState {
            copy(
                isHistoryMode = true,
                isFavoritesMode = false,
                movies = _movieHistory.value,
                isLoading = false,
                isEmptyResult = _movieHistory.value.isEmpty(),
                error = false,
                errorMessage = null,
                catalogueIssue = null,
            )
        }
    }

    fun loadFavoritesMovies() {
        searchGeneration.incrementAndGet()
        searchJob?.cancel()
        updateState {
            copy(
                isHistoryMode = false,
                isFavoritesMode = true,
                movies = _favoriteMovies.value,
                isLoading = false,
                isEmptyResult = _favoriteMovies.value.isEmpty(),
                error = false,
                errorMessage = null,
                catalogueIssue = null,
            )
        }
    }

    fun toggleFavorite(movie: Movie) {
        val favorite = sanitizeCatalogueMovie(movie)?.copy(torrents = null) ?: return
        val next = synchronized(_favoriteMovies) {
            val current = _favoriteMovies.value
            val updated = if (current.any { it.id == favorite.id }) {
                current.filterNot { it.id == favorite.id }
            } else {
                (listOf(favorite) + current.filterNot {
                    it.id == favorite.id ||
                        (it.title == favorite.title && it.year == favorite.year)
                }).take(MAX_FAVORITE_ITEMS)
            }
            _favoriteMovies.value = updated
            _favoriteMovieIds.value = updated.mapTo(linkedSetOf(), Movie::id)
            updated
        }
        if (_uiState.value.isFavoritesMode) {
            updateState { copy(movies = next, isEmptyResult = next.isEmpty()) }
        }
        persistFavorites(next)
    }

    fun clearFavorites() {
        _favoriteMovies.value = emptyList()
        _favoriteMovieIds.value = emptySet()
        if (_uiState.value.isFavoritesMode) {
            updateState { copy(movies = emptyList(), isEmptyResult = true) }
        }
        val generation = favoritesGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            favoritesWriteMutex.withLock {
                if (generation == favoritesGeneration.get()) {
                    favoritesPrefs.edit().remove(FAVORITES_JSON).commit()
                }
            }
        }
    }

    fun addToHistory(movie: Movie) {
        val historyMovie = sanitizeCatalogueMovie(movie)?.copy(torrents = null) ?: return
        val next = synchronized(_movieHistory) {
            (listOf(historyMovie) + _movieHistory.value.filterNot {
                it.id == historyMovie.id ||
                    (it.title == historyMovie.title && it.year == historyMovie.year)
            }).take(MAX_HISTORY_ITEMS).also { _movieHistory.value = it }
        }
        if (_uiState.value.isHistoryMode) updateState { copy(movies = next, isEmptyResult = false) }
        persistHistory(next)
    }

    fun clearHistory() {
        _movieHistory.value = emptyList()
        if (_uiState.value.isHistoryMode) updateState { copy(movies = emptyList(), isEmptyResult = true) }
        val generation = historyGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            historyWriteMutex.withLock {
                if (generation == historyGeneration.get()) historyPrefs.edit().remove(HISTORY_JSON).commit()
            }
        }
    }

    fun clearSearchHistory() {
        if (_searchHistory.value.isEmpty()) return
        _searchHistory.value = emptyList()
        val generation = searchHistoryGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            searchHistoryWriteMutex.withLock {
                if (generation == searchHistoryGeneration.get()) {
                    searchHistoryPrefs.edit().remove(SEARCH_HISTORY_JSON).commit()
                }
            }
        }
    }

    private fun recordSearchQuery(rawQuery: String) {
        val query = rawQuery.trim().replace(WHITESPACE_PATTERN, " ").take(MAX_SEARCH_QUERY_CHARS)
        if (query.length < 2) return
        val current = _searchHistory.value
        val updated = buildList(MAX_SEARCH_HISTORY_ITEMS) {
            add(query)
            current.asSequence()
                .filter { !it.equals(query, ignoreCase = true) }
                .take(MAX_SEARCH_HISTORY_ITEMS - 1)
                .forEach(::add)
        }
        if (updated == current) return
        _searchHistory.value = updated
        persistSearchHistory(updated)
    }

    private fun persistSearchHistory(queries: List<String>) {
        val generation = searchHistoryGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            val json = gson.toJson(queries)
            if (json.length > MAX_SEARCH_HISTORY_JSON_CHARS) return@launch
            searchHistoryWriteMutex.withLock {
                if (generation == searchHistoryGeneration.get()) {
                    searchHistoryPrefs.edit().putString(SEARCH_HISTORY_JSON, json).commit()
                }
            }
        }
    }

    private fun loadSearchHistoryFromPrefs(): List<String> {
        val json = searchHistoryPrefs.getString(SEARCH_HISTORY_JSON, null)
            ?.takeIf { it.isNotBlank() && it.length <= MAX_SEARCH_HISTORY_JSON_CHARS }
            ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<String?>>() {}.type
            gson.fromJson<List<String?>>(json, type).orEmpty()
        }.getOrDefault(emptyList())
            .asSequence()
            .filterNotNull()
            .map { it.trim().replace(WHITESPACE_PATTERN, " ").take(MAX_SEARCH_QUERY_CHARS) }
            .filter { it.length >= 2 }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(MAX_SEARCH_HISTORY_ITEMS)
            .toList()
    }

    fun selectMovie(movie: Movie) {
        val selected = sanitizeCatalogueMovie(movie) ?: return
        _selectedMovie.value = selected
        persistSelectedMovie(selected.copy(torrents = null))
    }

    fun findMovie(movieId: Long): Movie? = _selectedMovie.value?.takeIf { it.id == movieId }
        ?: _uiState.value.movies.firstOrNull { it.id == movieId }
        ?: _movieHistory.value.firstOrNull { it.id == movieId }
        ?: _favoriteMovies.value.firstOrNull { it.id == movieId }

    fun onMovieClicked(id: Long) {
        updateState { copy(lastClickedMovieId = id) }
    }

    fun consumeLastClickedMovieId(id: Long) {
        updateState {
            if (lastClickedMovieId == id) copy(lastClickedMovieId = null) else this
        }
    }

    fun saveTorrserverAddress(address: String) {
        if (address.isBlank()) {
            _torrserverAddress.value = ""
            settingsPrefs.edit().remove(TORRSERVER_ADDRESS)
                .putLong(TORRSERVER_SELECTION_REVISION,
                    settingsPrefs.getLong(TORRSERVER_SELECTION_REVISION, 0L) + 1L).apply()
            return
        }
        val normalized = TorrserverEndpoint.normalize(address) ?: return
        _torrserverAddress.value = normalized
        settingsPrefs.edit().putString(TORRSERVER_ADDRESS, normalized)
            .putLong(TORRSERVER_SELECTION_REVISION,
                settingsPrefs.getLong(TORRSERVER_SELECTION_REVISION, 0L) + 1L).apply()
    }

    override fun onCleared() {
        settingsPrefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
        torrserverRepository.destroy()
        super.onCleared()
    }

    private fun updateState(block: MovieUiState.() -> MovieUiState) {
        while (true) {
            val current = _uiState.value
            val updated = current.block()
            if (updated === current) return
            if (_uiState.compareAndSet(current, updated)) {
                persistUiStateChanges(current, updated)
                return
            }
        }
    }

    private fun updateIfCurrent(generation: Long, block: MovieUiState.() -> MovieUiState) {
        while (generation == searchGeneration.get()) {
            val current = _uiState.value
            val updated = current.block()
            if (updated === current) return
            if (generation == searchGeneration.get() && _uiState.compareAndSet(current, updated)) {
                persistUiStateChanges(current, updated)
                return
            }
        }
    }

    private fun persistUiStateChanges(previous: MovieUiState, updated: MovieUiState) {
        if (previous.searchQuery != updated.searchQuery) {
            savedStateHandle[KEY_SEARCH_QUERY] = updated.searchQuery
        }
        if (previous.currentPage != updated.currentPage) {
            savedStateHandle[KEY_CURRENT_PAGE] = updated.currentPage
        }
        if (previous.selectedGenre != updated.selectedGenre) {
            savedStateHandle[KEY_SELECTED_GENRE] = updated.selectedGenre
        }
        if (previous.selectedYear != updated.selectedYear) {
            savedStateHandle[KEY_SELECTED_YEAR] = updated.selectedYear
        }
        if (previous.selectedQuality != updated.selectedQuality) {
            savedStateHandle[KEY_SELECTED_QUALITY] = updated.selectedQuality
        }
        if (previous.selectedRating != updated.selectedRating) {
            savedStateHandle[KEY_SELECTED_RATING] = updated.selectedRating
        }
        if (previous.isSeriesMode != updated.isSeriesMode) {
            savedStateHandle[KEY_SERIES_MODE] = updated.isSeriesMode
        }
        if (previous.isHistoryMode != updated.isHistoryMode) {
            savedStateHandle[KEY_HISTORY_MODE] = updated.isHistoryMode
        }
        if (previous.isFavoritesMode != updated.isFavoritesMode) {
            savedStateHandle[KEY_FAVORITES_MODE] = updated.isFavoritesMode
        }
    }

    private fun persistHistory(movies: List<Movie>) {
        val generation = historyGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            val json = gson.toJson(HistoryEnvelope(movies = movies))
            historyWriteMutex.withLock {
                if (generation == historyGeneration.get()) {
                    historyPrefs.edit().putString(HISTORY_JSON, json).commit()
                }
            }
        }
    }

    private fun loadHistoryFromPrefs(): List<Movie> = loadMovieCollection(
        historyPrefs.getString(HISTORY_JSON, null),
        MAX_HISTORY_ITEMS,
    )

    private fun persistFavorites(movies: List<Movie>) {
        val generation = favoritesGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            val json = gson.toJson(HistoryEnvelope(version = FAVORITES_FORMAT_VERSION, movies = movies))
            favoritesWriteMutex.withLock {
                if (generation == favoritesGeneration.get()) {
                    favoritesPrefs.edit().putString(FAVORITES_JSON, json).commit()
                }
            }
        }
    }

    private fun loadFavoritesFromPrefs(): List<Movie> = loadMovieCollection(
        favoritesPrefs.getString(FAVORITES_JSON, null),
        MAX_FAVORITE_ITEMS,
    )

    private fun loadMovieCollection(json: String?, maxItems: Int): List<Movie> {
        val boundedJson = json?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (boundedJson.length > MAX_HISTORY_JSON_CHARS) return emptyList()
        return runCatching {
            gson.fromJson(boundedJson, HistoryEnvelope::class.java)?.movies.orEmpty()
        }.recoverCatching {
            val type = object : TypeToken<List<Movie>>() {}.type
            gson.fromJson<List<Movie>>(boundedJson, type).orEmpty()
        }.getOrDefault(emptyList())
            .mapNotNull { sanitizeCatalogueMovie(it)?.copy(torrents = null) }
            .distinctBy(Movie::id)
            .take(maxItems)
    }

    private fun persistSelectedMovie(movie: Movie) {
        val generation = selectionGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            val json = gson.toJson(movie)
            if (json.length > MAX_SELECTED_MOVIE_JSON_CHARS) return@launch
            selectionWriteMutex.withLock {
                if (generation == selectionGeneration.get()) {
                    selectionPrefs.edit().putString(SELECTED_MOVIE_JSON, json).commit()
                }
            }
        }
    }

    private fun loadSelectedMovieFromPrefs(): Movie? {
        val json = selectionPrefs.getString(SELECTED_MOVIE_JSON, null)
            ?.takeIf { it.length in 1..MAX_SELECTED_MOVIE_JSON_CHARS }
            ?: return null
        return runCatching { gson.fromJson(json, Movie::class.java) }
            .getOrNull()
            ?.let(::sanitizeCatalogueMovie)
    }

    private suspend fun ensureCurrent(generation: Long) {
        coroutineContext.ensureActive()
        if (generation != searchGeneration.get()) throw CancellationException("Superseded movie search")
    }

    private suspend fun <T> captureSource(block: suspend () -> T): SourceFetch<T> = try {
        SourceFetch.Success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        SourceFetch.Failure(failure)
    }

    private fun <T> successfulValuesOrThrow(outcomes: List<SourceFetch<T>>): List<T> {
        val successes = outcomes.mapNotNull { (it as? SourceFetch.Success)?.value }
        if (successes.isEmpty() && outcomes.any { it is SourceFetch.Failure }) {
            throw SearchUnavailableException()
        }
        return successes
    }

    private suspend fun <T> withRetry(attempts: Int = 2, block: suspend () -> T): T {
        require(attempts > 0)
        var lastFailure: Exception? = null
        repeat(attempts) { attempt ->
            try {
                return block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                lastFailure = failure
                if (attempt < attempts - 1) delay(RETRY_DELAY_MS)
            }
        }
        throw lastFailure ?: SearchUnavailableException()
    }

    private fun selectedYear(state: MovieUiState): String? = state.selectedYear
        .takeUnless { it == "All" }
        ?.takeIf { it.matches(FOUR_DIGIT_YEAR) }

    private fun selectedRating(state: MovieUiState): Float? = state.selectedRating
        .removeSuffix("+")
        .toFloatOrNull()
        ?.coerceIn(0f, 10f)

    private fun tmdbLanguage(): String =
        TmdbLocale.fromAppLanguage(appPrefs.getString(APP_LANGUAGE, "EN"))

    private fun todayUtc(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    private fun cacheKey(state: MovieUiState): String = listOf(
        CACHE_VERSION,
        state.isSeriesMode,
        state.searchQuery.trim().lowercase(Locale.ROOT),
        state.selectedGenre,
        state.selectedYear,
        state.selectedQuality,
        state.selectedRating,
        state.currentPage,
        tmdbLanguage(),
        tmdbConfigured,
    ).joinToString("|")

    private fun mergeCatalogues(primary: List<Movie>, secondary: List<Movie>): List<Movie> {
        val mergedByKey = (primary + secondary)
            .groupBy(::catalogueKey)
            .mapValues { (_, duplicates) -> mergeDuplicateMovies(duplicates) }
        val primaryKeys = primary.map(::catalogueKey).distinct()
        val secondaryKeys = secondary.map(::catalogueKey).distinct()
        val orderedKeys = LinkedHashSet<String>(mergedByKey.size)
        repeat(maxOf(primaryKeys.size, secondaryKeys.size)) { index ->
            primaryKeys.getOrNull(index)?.let(orderedKeys::add)
            secondaryKeys.getOrNull(index)?.let(orderedKeys::add)
        }
        return orderedKeys.mapNotNull(mergedByKey::get).distinctBy(Movie::id)
    }

    private fun catalogueKey(movie: Movie): String {
        val title = cleanTitle(movie.title ?: movie.originalTitle).lowercase(Locale.ROOT)
        return if (title.isBlank()) "id:${movie.id}" else "$title:${movie.year ?: 0}"
    }

    private fun mergeDuplicateMovies(duplicates: List<Movie>): Movie {
        val torrentEntry = duplicates.firstOrNull { !it.torrents.isNullOrEmpty() }
        val tmdbEntry = duplicates.firstOrNull { it.tmdbId != null }
        val base = torrentEntry ?: tmdbEntry ?: duplicates.first()
        
        // Prioritizăm TMDB pentru postere (calitate și stabilitate HTTPS garantată).
        // Folosim sursa torrent ca fallback pentru titlurile care nu sunt pe TMDB.
        val finalPoster = tmdbEntry?.largePosterPath ?: tmdbEntry?.mediumPosterPath ?: tmdbEntry?.posterPath
            ?: torrentEntry?.largePosterPath ?: torrentEntry?.mediumPosterPath ?: torrentEntry?.posterPath
            ?: base.mediumPosterPath

        return base.copy(
            id = torrentEntry?.id ?: base.id,
            title = base.title ?: tmdbEntry?.title,
            originalTitle = tmdbEntry?.originalTitle ?: base.originalTitle,
            localizedTitle = tmdbEntry?.localizedTitle ?: base.localizedTitle,
            imdbCode = torrentEntry?.imdbCode ?: base.imdbCode,
            posterPath = finalPoster,
            mediumPosterPath = finalPoster,
            largePosterPath = finalPoster,
            year = base.year ?: tmdbEntry?.year,
            ytTrailerCode = torrentEntry?.ytTrailerCode ?: base.ytTrailerCode,
            genres = tmdbEntry?.genres?.takeIf { it.isNotEmpty() } ?: base.genres,
            rating = tmdbEntry?.rating ?: base.rating,
            torrents = torrentEntry?.torrents ?: base.torrents,
            tmdbId = tmdbEntry?.tmdbId ?: base.tmdbId,
            url = torrentEntry?.url ?: base.url,
        )
    }

    private fun sanitizeCatalogueMovie(
        movie: Movie?,
        requirePositiveId: Boolean = false,
    ): Movie? {
        movie ?: return null
        if (movie.id == 0L || (requirePositiveId && movie.id < 1L)) return null
        val title = boundedText(movie.title, MAX_TITLE_CHARS) ?: return null
        val tmdbId = movie.tmdbId?.takeIf { it > 0L }
        val torrents = movie.torrents.orEmpty()
            .asSequence()
            .take(MAX_TORRENTS_PER_MOVIE)
            .mapNotNull(::sanitizeCatalogueTorrent)
            .toList()
            .takeIf { it.isNotEmpty() }

        return movie.copy(
            title = title,
            originalTitle = boundedText(movie.originalTitle, MAX_TITLE_CHARS),
            localizedTitle = boundedText(movie.localizedTitle, MAX_TITLE_CHARS),
            imdbCode = movie.imdbCode
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?.takeIf(IMDB_CODE::matches),
            posterPath = RemoteUrlPolicy.allowlistedHttps(
                movie.posterPath,
                CATALOGUE_IMAGE_HOST_SUFFIXES,
            ),
            mediumPosterPath = RemoteUrlPolicy.allowlistedHttps(
                movie.mediumPosterPath,
                CATALOGUE_IMAGE_HOST_SUFFIXES,
            ),
            largePosterPath = RemoteUrlPolicy.allowlistedHttps(
                movie.largePosterPath,
                CATALOGUE_IMAGE_HOST_SUFFIXES,
            ),
            year = movie.year?.takeIf { it in MIN_CATALOGUE_YEAR..currentYear() + 2 },
            ytTrailerCode = RemoteUrlPolicy.youtubeVideoId(movie.ytTrailerCode),
            genres = movie.genres.orEmpty()
                .asSequence()
                .mapNotNull { boundedText(it, MAX_GENRE_CHARS) }
                .distinct()
                .take(MAX_GENRES_PER_MOVIE)
                .toList()
                .takeIf { it.isNotEmpty() },
            rating = movie.rating?.takeIf { it.isFinite() }?.coerceIn(0.0, 10.0),
            torrents = torrents,
            tmdbId = tmdbId,
            url = RemoteUrlPolicy.allowlistedHttps(movie.url, YTS_ALLOWED_HOST_SUFFIXES),
        )
    }

    private fun sanitizeCatalogueTorrent(torrent: Torrent?): Torrent? {
        torrent ?: return null
        val hash = torrent.hash
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf(INFO_HASH::matches)
            ?: return null
        return torrent.copy(
            url = null,
            hash = hash,
            quality = boundedText(torrent.quality, MAX_TORRENT_LABEL_CHARS),
            type = boundedText(torrent.type, MAX_TORRENT_LABEL_CHARS),
            size = boundedText(torrent.size, MAX_TORRENT_LABEL_CHARS),
            seeds = torrent.seeds?.coerceIn(0, MAX_SWARM_COUNT),
            peers = torrent.peers?.coerceIn(0, MAX_SWARM_COUNT),
            seasonEpisode = boundedText(torrent.seasonEpisode, MAX_TORRENT_LABEL_CHARS),
        )
    }

    private fun boundedText(value: String?, maxLength: Int): String? = value
        ?.asSequence()
        ?.filterNot(Char::isISOControl)
        ?.joinToString("")
        ?.trim()
        ?.take(maxLength)
        ?.takeIf(String::isNotBlank)

    private fun normalizePage(page: Int): Int = page.coerceIn(1, MAX_CATALOGUE_PAGE)

    private fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

    private fun cleanTitle(title: String?): String = title.orEmpty()
        .replace(YEAR_PATTERN, "") // Eliminăm anul
        .replace(QUALITY_NOISE_PATTERN, "") // Eliminăm calitatea
        .replace(NON_TITLE_CHARS, " ")
        .replace(WHITESPACE, " ")
        .trim()

    private object Mapper {
        fun mapTmdbToMovie(tmdb: TmdbSeries, isSeries: Boolean): Movie = Movie(
            id = -tmdb.id,
            title = tmdb.name ?: tmdb.originalName,
            originalTitle = tmdb.originalName,
            localizedTitle = tmdb.name ?: tmdb.originalName,
            imdbCode = null,
            posterPath = tmdb.posterPath?.let { RemoteHosts.TMDB_POSTER_BASE_URL + it.removePrefix("/") },
            mediumPosterPath = tmdb.posterPath?.let { RemoteHosts.TMDB_POSTER_BASE_URL + it.removePrefix("/") },
            year = tmdb.firstAirDate?.take(4)?.toIntOrNull(),
            ytTrailerCode = null,
            genres = tmdb.genreIds?.map { genreName(it, isSeries) },
            rating = tmdb.voteAverage,
            torrents = null,
            isSeries = isSeries,
            tmdbId = tmdb.id
        )

        fun genreId(genre: String, isSeries: Boolean): Int? = when (genre) {
            "Action" -> if (isSeries) 10759 else 28
            "Adventure" -> if (isSeries) 10759 else 12
            "Animation" -> 16
            "Comedy" -> 35
            "Crime" -> 80
            "Documentary" -> 99
            "Drama" -> 18
            "Family" -> 10751
            "Fantasy" -> if (isSeries) 10765 else 14
            "History" -> 36
            "Horror" -> 27
            "Music" -> 10402
            "Mystery" -> 9648
            "Romance" -> 10749
            "Sci-Fi" -> if (isSeries) 10765 else 878
            "Thriller" -> 53
            "War" -> if (isSeries) 10768 else 10752
            "Western" -> 37
            else -> null
        }

        fun matchesTmdbFilters(item: TmdbSeries, state: MovieUiState, isSeries: Boolean): Boolean {
            val expectedGenre = genreId(state.selectedGenre, isSeries)
            if (expectedGenre != null && expectedGenre !in item.genreIds.orEmpty()) return false
            val minimumRating = state.selectedRating.removeSuffix("+").toDoubleOrNull()
            if (minimumRating != null && (item.voteAverage ?: 0.0) < minimumRating) return false
            val expectedYear = state.selectedYear.takeUnless { it == "All" }?.toIntOrNull()
            if (expectedYear != null && item.firstAirDate?.take(4)?.toIntOrNull() != expectedYear) return false
            return true
        }

        fun matchesYtsFilters(movie: Movie, state: MovieUiState): Boolean {
            val expectedYear = state.selectedYear.takeUnless { it == "All" }?.toIntOrNull()
            if (expectedYear != null && movie.year != expectedYear) return false
            val minimumRating = state.selectedRating.removeSuffix("+").toDoubleOrNull()
            if (minimumRating != null && (movie.rating ?: 0.0) < minimumRating) return false
            if (state.selectedGenre != "All" && movie.genres.orEmpty().none {
                    it.equals(state.selectedGenre, ignoreCase = true)
                }
            ) return false
            if (state.selectedQuality != "All" && movie.torrents.orEmpty().none {
                    it.quality.equals(state.selectedQuality, ignoreCase = true)
                }
            ) return false
            return true
        }

        private fun genreName(id: Int, isSeries: Boolean): String = when (id) {
            28, 10759 -> if (isSeries) "Action & Adventure" else "Action"
            12 -> "Adventure"
            16 -> "Animation"
            35 -> "Comedy"
            80 -> "Crime"
            18 -> "Drama"
            14, 10765 -> if (isSeries) "Sci-Fi & Fantasy" else "Fantasy"
            27 -> "Horror"
            878 -> "Sci-Fi"
            53 -> "Thriller"
            99 -> "Documentary"
            10751 -> "Family"
            36 -> "History"
            10752, 10768 -> if (isSeries) "War & Politics" else "War"
            37 -> "Western"
            10402 -> "Music"
            9648 -> "Mystery"
            10749 -> "Romance"
            else -> if (isSeries) "TV" else "Movie"
        }
    }

    private class SearchUnavailableException : IOException("All movie sources failed")
    private class TmdbNotConfiguredException : IllegalStateException("TMDB is not configured")

    private companion object {
        const val TAG = "MovieViewModel"
        const val SETTINGS_PREFS = "settings"
        const val APP_PREFS = "app_prefs"
        const val APP_LANGUAGE = "app_language"
        const val HISTORY_PREFS = "history_prefs"
        const val HISTORY_JSON = "history_movies_json"
        const val HISTORY_FORMAT_VERSION = 1
        const val FAVORITES_PREFS = "favorites_prefs"
        const val FAVORITES_JSON = "favorite_movies_json"
        const val FAVORITES_FORMAT_VERSION = 1
        const val SELECTION_PREFS = "selection_prefs"
        const val SELECTED_MOVIE_JSON = "selected_movie_json"
        const val SEARCH_HISTORY_PREFS = "search_history_prefs"
        const val SEARCH_HISTORY_JSON = "search_history_json"
        const val TORRSERVER_ADDRESS = "torrserver_address"
        const val TORRSERVER_SELECTION_REVISION = "torrserver_selection_revision"
        const val CACHE_VERSION = "v7"
        const val API_CACHE_ENTRIES = 100
        const val PAGE_SIZE = 49
        const val MAX_CATALOGUE_PAGE = 500
        const val MAX_CATALOGUE_RESPONSE_ITEMS = 100
        const val MAX_SEARCH_QUERY_CHARS = 200
        const val MAX_FILTER_CHARS = 60
        const val MAX_PENDING_URL_CHARS = 2_048
        const val MAX_TITLE_CHARS = 300
        const val MAX_GENRE_CHARS = 60
        const val MAX_GENRES_PER_MOVIE = 20
        const val MAX_TORRENTS_PER_MOVIE = 24
        const val MAX_TORRENT_LABEL_CHARS = 80
        const val MAX_SWARM_COUNT = 100_000_000
        const val MIN_CATALOGUE_YEAR = 1870
        const val TMDB_PAGES_PER_APP_PAGE = 2
        const val MIN_MOVIE_VOTE_COUNT = 50
        const val MIN_TV_VOTE_COUNT = 20
        const val MAX_HISTORY_ITEMS = 100
        const val MAX_FAVORITE_ITEMS = 200
        const val MAX_SEARCH_HISTORY_ITEMS = 12
        const val MAX_SEARCH_HISTORY_JSON_CHARS = 8_192
        const val MAX_HISTORY_JSON_CHARS = 1_000_000
        const val MAX_SELECTED_MOVIE_JSON_CHARS = 100_000
        const val RETRY_DELAY_MS = 500L
        const val YTS_TOTAL_TIMEOUT_MS = 30_000L
        const val YTS_MIRROR_TIMEOUT_MS = 7_000L
        const val TMDB_TOTAL_TIMEOUT_MS = 30_000L

        const val KEY_SEARCH_QUERY = "searchQuery"
        const val KEY_CURRENT_PAGE = "currentPage"
        const val KEY_SELECTED_GENRE = "selectedGenre"
        const val KEY_SELECTED_YEAR = "selectedYear"
        const val KEY_SELECTED_QUALITY = "selectedQuality"
        const val KEY_SELECTED_RATING = "selectedRating"
        const val KEY_SERIES_MODE = "isSeriesMode"
        const val KEY_HISTORY_MODE = "isHistoryMode"
        const val KEY_FAVORITES_MODE = "isFavoritesMode"
        const val KEY_SELECTED_MOVIE = "selectedMovieJson"
        const val KEY_PENDING_URL = "pendingUrl"

        val YTS_DOMAINS = io.github.flavyu22.movietorrentsearchtv.config.RemoteHosts.ytsMirrorBaseUrls
        val WHITESPACE_PATTERN = Regex("\\s+")
        val YTS_ALLOWED_HOST_SUFFIXES =
            io.github.flavyu22.movietorrentsearchtv.config.RemoteHosts.ytsHostSuffixes
        val CATALOGUE_IMAGE_HOST_SUFFIXES =
            io.github.flavyu22.movietorrentsearchtv.config.RemoteHosts.catalogueImageHostSuffixes
        val IMDB_CODE = Regex("tt\\d{5,12}", RegexOption.IGNORE_CASE)
        val INFO_HASH = Regex("(?:[a-f0-9]{40}|[a-z2-7]{32})", RegexOption.IGNORE_CASE)
        val FOUR_DIGIT_YEAR = Regex("(?:19|20)\\d{2}")
        val NON_TITLE_CHARS = Regex("[^\\p{L}\\p{N}\\s]")
        val WHITESPACE = Regex("\\s+")
        // Hoisted out of cleanTitle(): these two patterns are applied to every matched
        // title during a search, and recompiling them per call dominated that loop.
        val YEAR_PATTERN = Regex("""(?i)\b(19|20)\d{2}\b""")
        val QUALITY_NOISE_PATTERN = Regex(
            """(?i)\b(bluray|brrip|web-dl|webrip|h264|h265|x264|x265|1080p|720p|2160p|4k)\b""",
        )
    }
}

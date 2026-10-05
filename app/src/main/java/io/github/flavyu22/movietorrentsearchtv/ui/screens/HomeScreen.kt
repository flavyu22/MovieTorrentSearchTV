package io.github.flavyu22.movietorrentsearchtv.ui.screens

import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import io.github.flavyu22.movietorrentsearchtv.model.AppStrings
import io.github.flavyu22.movietorrentsearchtv.model.Movie
import io.github.flavyu22.movietorrentsearchtv.model.Translations
import io.github.flavyu22.movietorrentsearchtv.ui.OptimizedTorrserverDialog
import io.github.flavyu22.movietorrentsearchtv.ui.components.CenteredLoadingIndicator
import io.github.flavyu22.movietorrentsearchtv.ui.components.FocusableIconButton
import io.github.flavyu22.movietorrentsearchtv.ui.components.HorizontalSelectionMenu
import io.github.flavyu22.movietorrentsearchtv.ui.components.MoviePosterCard
import io.github.flavyu22.movietorrentsearchtv.ui.components.NetflixHeaderButton
import io.github.flavyu22.movietorrentsearchtv.ui.components.NetflixPaginationButton
import io.github.flavyu22.movietorrentsearchtv.ui.components.preferredPosterUrl
import io.github.flavyu22.movietorrentsearchtv.ui.dialogs.AboutDialog
import io.github.flavyu22.movietorrentsearchtv.ui.theme.AlmostBlack
import io.github.flavyu22.movietorrentsearchtv.ui.theme.Grey
import io.github.flavyu22.movietorrentsearchtv.ui.theme.SoftBlue
import io.github.flavyu22.movietorrentsearchtv.viewmodel.AppViewModel
import io.github.flavyu22.movietorrentsearchtv.viewmodel.MovieViewModel
import io.github.flavyu22.movietorrentsearchtv.viewmodel.TorrserverViewModel
import io.github.flavyu22.movietorrentsearchtv.playback.PreferredPlayerStore
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// ─── FIX #5: Liste ca object singleton — alocate O SINGURĂ dată pe durata app ──
private object FilterLists {
    val GENRES = listOf(
        "All", "Action", "Adventure", "Animation", "Comedy", "Crime",
        "Documentary", "Drama", "Family", "Fantasy", "History", "Horror", "Music",
        "Mystery", "Romance", "Sci-Fi", "Thriller", "War", "Western"
    )
    val YEARS = listOf("All") +
        (java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) downTo 1950).map(Int::toString)
    val QUALITIES = listOf("All", "2160p", "1080p", "720p")
    val RATINGS = listOf("All", "9+", "8+", "7+", "6+", "5+", "4+", "3+", "2+", "1+")
}

// ─── HomeScreen ────────────────────────────────────────────────────────────────
@Composable
fun MovieSearchApp(
    viewModel: MovieViewModel,
    appViewModel: AppViewModel,
    currentLanguageCode: String,
    onLanguageChange: (String) -> Unit,
    onLogoutRequest: () -> Unit,
    onMovieClick: (Movie) -> Unit,
    onHistoryRequest: () -> Unit = {},
    onFavoritesRequest: () -> Unit = {},
) {
    val s = remember(currentLanguageCode) { Translations[currentLanguageCode] ?: Translations["EN"]!! }

    // FIX #7: un singur collect — O singură recompoziție când se schimbă orice câmp
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val appUiState by appViewModel.appState.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val catalogueIssueMessage = when (uiState.catalogueIssue) {
        MovieViewModel.CatalogueIssue.NETWORK -> s.catalogueNetworkIssue
        MovieViewModel.CatalogueIssue.TMDB_NOT_CONFIGURED -> s.tmdbNotConfigured
        MovieViewModel.CatalogueIssue.PARTIAL_RESULTS -> s.partialResults
        null -> null
    }

    val menuFocusRequester = remember { FocusRequester() }

    var showAboutDialog      by remember { mutableStateOf(false) }
    var showTorrserverDialog by remember { mutableStateOf(false) }
    var showTmdbDialog       by remember { mutableStateOf(false) }
    
    LaunchedEffect(viewModel) {
        if (uiState.movies.isEmpty() && !uiState.isLoading) {
            when {
                uiState.isFavoritesMode -> viewModel.loadFavoritesMovies()
                uiState.isHistoryMode -> viewModel.loadHistoryMovies()
                uiState.searchQuery.isNotBlank() ||
                    uiState.selectedGenre != "All" ||
                    uiState.selectedYear != "All" ||
                    uiState.selectedQuality != "All" ||
                    uiState.selectedRating != "All" -> viewModel.retrySearch()
                else -> viewModel.loadPopularMovies(isSeries = uiState.isSeriesMode)
            }
        }
        androidx.compose.runtime.withFrameNanos { }
        if (uiState.lastClickedMovieId == null) {
            runCatching { menuFocusRequester.requestFocus() }
        }
    }

    // A restored target can disappear after filters, pagination, or catalogue refreshes.
    // Clear that stale target so focus never gets stranded outside the focus tree.
    LaunchedEffect(
        uiState.lastClickedMovieId,
        uiState.movies,
        uiState.isLoading,
        uiState.error,
    ) {
        val targetId = uiState.lastClickedMovieId ?: return@LaunchedEffect
        if (!uiState.isLoading && uiState.movies.none { it.id == targetId }) {
            viewModel.consumeLastClickedMovieId(targetId)
            androidx.compose.runtime.withFrameNanos { }
            runCatching { menuFocusRequester.requestFocus() }
        }
    }

    // Focus is retained by the navigation destination itself. Torrent discovery starts only
    // after the user explicitly opens a title; merely browsing posters must not leak intent.
    val onMovieFocusedStable = remember(viewModel) {
        { movie: Movie ->
            if (viewModel.lastClickedMovieId == movie.id) {
                viewModel.consumeLastClickedMovieId(movie.id)
            }
        }
    }
    val onMovieClickStable = remember(viewModel, onMovieClick) { { movie: Movie ->
        viewModel.onMovieClicked(movie.id)
        onMovieClick(movie)
    } }
    val onRetryStable = remember(viewModel) { { viewModel.retrySearch() } }
    val onPreviousPageStable = remember(viewModel) { { viewModel.goToPreviousPage() } }
    val onNextPageStable = remember(viewModel) { { viewModel.goToNextPage() } }

    val onRecentSearchStable = remember(viewModel) { { query: String ->
        viewModel.searchMovies(
            query,
            viewModel.selectedGenre,
            viewModel.selectedYear,
            viewModel.selectedQuality,
            viewModel.selectedRating,
        )
    } }
    val onClearSearchHistoryStable = remember(viewModel) { { viewModel.clearSearchHistory() } }

    if (showAboutDialog) {
        AboutDialog(s, onDismiss = { showAboutDialog = false })
    }
    if (showTorrserverDialog) {
        TorrserverSettingsDialog(
            s = s,
            viewModel = viewModel,
            onDismiss = { showTorrserverDialog = false },
        )
    }
    if (showTmdbDialog) {
        TmdbSettingsDialog(
            s = s,
            currentKey = appUiState.tmdbApiKey,
            onSave = { appViewModel.saveTmdbApiKey(it); viewModel.retrySearch() },
            onDismiss = { showTmdbDialog = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(AlmostBlack)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 8.dp), // Adăugat margini pentru TV overscan
        ) {
            HomeHeader(
                viewModel = viewModel,
                searchQuery = uiState.searchQuery,
                selectedGenre = uiState.selectedGenre,
                selectedYear = uiState.selectedYear,
                selectedQuality = uiState.selectedQuality,
                selectedRating = uiState.selectedRating,
                isHistoryMode = uiState.isHistoryMode,
                isFavoritesMode = uiState.isFavoritesMode,
                s = s,
                langCode = currentLanguageCode,
                onLanguageChange = onLanguageChange,
                menuFocusRequester = menuFocusRequester,
                onShowAbout = { showAboutDialog = true },
                onShowTorrserver = { showTorrserverDialog = true },
                onShowTmdb = { showTmdbDialog = true },
                onLogoutRequest = onLogoutRequest,
                onShowHistory = onHistoryRequest,
                onShowFavorites = onFavoritesRequest,
            )

            // Bannerul explicativ (TMDB neconfigurat, eșec rețea, rezultate parțiale)
            // trebuie să fie vizibil și în starea de eroare — nu doar pe rezultate parțiale.
            // Altfel, la un catalog de seriale indisponibil (de ex. fără cheie TMDB), utilizatorul
            // vedea doar un ecran generic de eroare, fără motivul clar. Aici bannerul e afișat
            // mereu când există un mesaj de catalog; gridul de mai jos păstrează butonul Retry.
            if (catalogueIssueMessage != null) {
                CatalogueWarningBanner(catalogueIssueMessage)
            }

            if (searchHistory.isNotEmpty() &&
                uiState.searchQuery.isBlank() &&
                !uiState.isHistoryMode && !uiState.isFavoritesMode &&
                !uiState.isLoading && !uiState.error
            ) {
                RecentSearchesRow(
                    searches = searchHistory,
                    s = s,
                    onSelect = onRecentSearchStable,
                    onClear = onClearSearchHistoryStable,
                )
            }

            MovieGrid(
                isLoading = uiState.isLoading,
                movies = uiState.movies,
                error = uiState.error,
                isEmptyResult = uiState.isEmptyResult,
                errorMessage = catalogueIssueMessage ?: uiState.errorMessage,
                isHistoryMode = uiState.isHistoryMode,
                isFavoritesMode = uiState.isFavoritesMode,
                focusTargetMovieId = uiState.lastClickedMovieId,
                currentPage = uiState.currentPage,
                s = s,
                onMovieClick = onMovieClickStable,
                onMovieFocused = onMovieFocusedStable,
                onRetry = onRetryStable,
                onPreviousPage = onPreviousPageStable,
                onNextPage = onNextPageStable,
            )
        }
    }
}

// ─── Istoric căutări (chip-uri focalizabile pentru TV) ────────────────────────
@Composable
private fun RecentSearchesRow(
    searches: List<String>,
    s: AppStrings,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.History,
            contentDescription = null,
            tint = Grey,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = s.recentSearches,
            color = Grey,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Spacer(Modifier.width(12.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(searches, key = { it }) { term ->
                RecentSearchChip(text = term, onClick = { onSelect(term) })
            }
            item(key = "__clear_search_history") {
                RecentSearchChip(
                    text = s.clearSearchHistory,
                    icon = Icons.Default.Delete,
                    onClick = onClear,
                )
            }
        }
    }
}

@Composable
private fun RecentSearchChip(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        color = if (isFocused) Color.White else Color(0xFF222222),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (isFocused) 0f else 0.15f)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (isFocused) Color.Black else Grey,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = text,
                color = if (isFocused) Color.Black else Color.White,
                fontSize = 12.sp,
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CatalogueWarningBanner(message: String) {
    Surface(
        color = Color(0xFF5D4513),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = message
            },
    ) {
        Text(
            text = message,
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MovieGrid(
    isLoading: Boolean,
    movies: List<Movie>,
    error: Boolean,
    isEmptyResult: Boolean,
    errorMessage: String?,
    isHistoryMode: Boolean,
    isFavoritesMode: Boolean,
    focusTargetMovieId: Long?,
    currentPage: Int,
    s: AppStrings,
    onMovieClick: (Movie) -> Unit,
    onMovieFocused: (Movie) -> Unit,
    onRetry: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
) {
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val resultSignature = remember(movies, currentPage, isHistoryMode, isFavoritesMode) {
        val modeHash = when {
            isFavoritesMode -> 2L
            isHistoryMode -> 1L
            else -> 0L
        }
        movies.fold(31L * currentPage + modeHash) { hash, movie ->
            hash * 1_000_003L + movie.id
        }
    }
    var lastDisplayedResultSignature by rememberSaveable { mutableStateOf<Long?>(null) }

    // Scroll only when a completed result set actually changes. Editing the query no longer
    // snaps the grid to the beginning on every character, and returning from details keeps it.
    LaunchedEffect(resultSignature, isLoading, error) {
        if (!isLoading && !error && movies.isNotEmpty() &&
            lastDisplayedResultSignature != resultSignature
        ) {
            gridState.scrollToItem(0)
            lastDisplayedResultSignature = resultSignature
        }
    }

    // --- Ultra-Pro: Image Preloading logic ---
    val context = LocalContext.current
    val isLowRamDevice = remember(context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.isLowRamDevice == true || (manager?.memoryClass ?: 256) <= 128
    }
    val preloadAhead = if (isLowRamDevice) 0 else 4
    val posterWidthPx = if (isLowRamDevice) 220 else 300
    val posterHeightPx = if (isLowRamDevice) 330 else 450
    val imageLoader = remember(context) { context.imageLoader }
    LaunchedEffect(
        gridState,
        movies,
        imageLoader,
        isLoading,
        error,
        preloadAhead,
        posterWidthPx,
        posterHeightPx,
    ) {
        if (isLoading || error || movies.isEmpty()) return@LaunchedEffect

        // Prefetching competes with the actually-visible cards for the same limited
        // image dispatcher (see MovieTorrentApplication: 4 fetches / 2 decodes). On a
        // low-RAM TV the previous 3-item window meant speculative decodes regularly
        // delayed the poster the user was focusing on, so prefetch is disabled there.
        if (preloadAhead <= 0) return@LaunchedEffect

        val requestedPosters = HashSet<String>()
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex < 0 || movies.isEmpty()) return@collect
                val preloadStart = lastVisibleIndex + 1
                val preloadEnd = minOf(lastVisibleIndex + preloadAhead, movies.lastIndex)
                if (preloadStart > preloadEnd) return@collect

                for (i in preloadStart..preloadEnd) {
                    val movie = movies[i]
                    val poster = preferredPosterUrl(
                        movie.largePosterPath,
                        movie.mediumPosterPath,
                        movie.posterPath,
                    ) ?: continue
                    if (!requestedPosters.add(poster)) continue

                    val request = ImageRequest.Builder(context)
                        .data(poster)
                        .size(posterWidthPx, posterHeightPx)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .precision(Precision.INEXACT)
                        .build()
                    // Structured children are cancelled with this effect and detach when done,
                    // so completed ImageResults are not retained for the lifetime of the grid.
                    launch { imageLoader.execute(request) }
                }
            }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            CenteredLoadingIndicator(message = s.loading)
        } else if (error) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        liveRegion = LiveRegionMode.Assertive
                        contentDescription = errorMessage ?: s.noSources
                    },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Search, 
                    contentDescription = null, 
                    tint = Color.Gray, 
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = errorMessage ?: s.noSources,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(24.dp))
                
                val retryFocusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    androidx.compose.runtime.withFrameNanos { }
                    runCatching { retryFocusRequester.requestFocus() }
                }
                
                NetflixPaginationButton(
                    text = s.retry,
                    onClick = onRetry,
                    modifier = Modifier.focusRequester(retryFocusRequester)
                )
            }
        } else if (isEmptyResult) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = if (isFavoritesMode) s.myListEmpty else s.noSources
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isFavoritesMode) s.myListEmpty else s.noSources,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 142.dp),
                contentPadding = PaddingValues(bottom = 32.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                // Ultra-Optimization: Pre-încărcăm itemii pentru scroll fluid
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = true,
            ) {
                gridItems(
                    items = movies,
                    key = { it.id },
                    contentType = { "movie" },
                ) { movie ->
                    // Stable per-item focus callback: a lambda allocated inside the item
                    // scope is a new instance on every recomposition of the grid, which
                    // prevents the card from ever being skipped.
                    val cardOnFocused = remember(movie.id) { { onMovieFocused(movie) } }
                    NetflixMovieCard(
                        movie = movie,
                        s = s,
                        onMovieClick = onMovieClick,
                        onFocused = cardOnFocused,
                        isTargetFocus = movie.id == focusTargetMovieId,
                        posterWidthPx = posterWidthPx,
                        posterHeightPx = posterHeightPx,
                    )
                }
                if (!isHistoryMode && !isFavoritesMode) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PaginationRow(
                            currentPage = currentPage,
                            s = s,
                            onPrevious = onPreviousPage,
                            onNext = onNextPage
                        )
                    }
                }
            }
        }
    }
}

// ─── Card film ────────────────────────────────────────────────────────────────
@Composable
fun NetflixMovieCard(
    movie: Movie,
    s: AppStrings,
    onMovieClick: (Movie) -> Unit,
    onFocused: () -> Unit = {},
    isTargetFocus: Boolean = false,
    posterWidthPx: Int = 300,
    posterHeightPx: Int = 450,
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }

    if (isTargetFocus && !isFocused) {
        LaunchedEffect(focusRequester) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }

    val imageRequest = remember(
        context,
        movie.id,
        movie.largePosterPath,
        movie.mediumPosterPath,
        movie.posterPath,
        posterWidthPx,
        posterHeightPx,
    ) {
        ImageRequest.Builder(context)
            .data(preferredPosterUrl(movie.largePosterPath, movie.mediumPosterPath, movie.posterPath))
            .size(posterWidthPx, posterHeightPx)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .precision(Precision.INEXACT)
            .build()
    }

    // FOCUS SIMPLU: fără animație de scale (cost GPU/RAM pe TV-uri slabe).
    // Feedback-ul de focus este dat doar de borderul alb și de fundalul cardului.

    val quality = remember(movie.torrents) {
        movie.torrents?.maxByOrNull { t ->
            val q = t.quality?.lowercase() ?: ""
            when {
                q.contains("2160") || q.contains("4k") -> 400
                q.contains("1080") -> 300
                q.contains("720") -> 200
                else -> 100
            }
        }?.quality
    }

    // Stable lambdas: a fresh lambda instance on every recomposition makes this card
    // unskippable and forces its whole subtree (Surface + AsyncImage + semantics) to
    // re-run whenever an unrelated sibling in the grid changes state.
    val stableOnClick = remember(movie.id, onMovieClick) { { onMovieClick(movie) } }
    val stableOnFocused = remember(movie.id, onFocused) { onFocused }

    MoviePosterCard(
        title = movie.title,
        imageRequest = imageRequest,
        isFocused = isFocused,
        scale = 1f,
        isSeries = movie.isSeries,
        label = if (movie.isSeries) s.seriesLabel else s.movieLabel,
        year = movie.year?.toString(),
        genre = movie.genres?.firstOrNull(),
        quality = quality,
        fallbackTitle = s.genericMovie,
        interactionSource = interactionSource,
        onClick = stableOnClick,
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                if (focusState.isFocused) stableOnFocused()
            }
    )
}

// ─── Rând paginare ─────────────────────────────────────────────────────────────
@Composable
private fun PaginationRow(
    currentPage: Int,
    s: AppStrings,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp), // Redus la 8dp
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetflixPaginationButton(s.prev, enabled = currentPage > 1, onClick = onPrevious)
        Spacer(Modifier.width(24.dp))
        Text("${s.page} $currentPage", color = Color.White)
        Spacer(Modifier.width(24.dp))
        NetflixPaginationButton(s.next, onClick = onNext)
    }
}

// ─── Header home cu filtre ─────────────────────────────────────────────────────
@Composable
private fun HomeHeader(
    viewModel: MovieViewModel,
    searchQuery: String,
    selectedGenre: String,
    selectedYear: String,
    selectedQuality: String,
    selectedRating: String,
    isHistoryMode: Boolean,
    isFavoritesMode: Boolean,
    s: AppStrings,
    langCode: String,
    onLanguageChange: (String) -> Unit,
    menuFocusRequester: FocusRequester,
    onShowAbout: () -> Unit,
    onShowTorrserver: () -> Unit,
    onShowTmdb: () -> Unit,
    onLogoutRequest: () -> Unit,
    onShowHistory: () -> Unit,
    onShowFavorites: () -> Unit,
) {
    var showMenu     by remember { mutableStateOf(false) }
    var showLangMenu by remember { mutableStateOf(false) }
    var showGenre    by remember { mutableStateOf(false) }
    var showYear     by remember { mutableStateOf(false) }
    var showQuality  by remember { mutableStateOf(false) }
    var showRating   by remember { mutableStateOf(false) }
    var externalActionError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val actionErrorFocus = remember { FocusRequester() }

    // Filters are passed in as plain values rather than the whole MovieUiState: the state
    // object carries the full movie list, so comparing it on every recomposition walked
    // all ~49 rows (18 fields each) even though the header only reads these few fields.
    val isCollectionMode = isHistoryMode || isFavoritesMode

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrEmpty()) {
                viewModel.searchMovies(
                    spoken,
                    selectedGenre,
                    selectedYear,
                    selectedQuality,
                    selectedRating
                )
            }
        }
    }

    externalActionError?.let { message ->
        LaunchedEffect(message) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { actionErrorFocus.requestFocus() }
        }
        AlertDialog(
            onDismissRequest = { externalActionError = null },
            title = { Text(s.actionUnavailableTitle) },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = { externalActionError = null },
                    modifier = Modifier.focusRequester(actionErrorFocus),
                ) {
                    Text(s.back)
                }
            },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp), // Redus la 64dp pentru mai mult spațiu grid
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            val menuInteraction = remember { MutableInteractionSource() }
            val isMenuFocused by menuInteraction.collectIsFocusedAsState()

            IconButton(
                onClick = {
                    showMenu = true
                    showLangMenu = false
                    showGenre = false
                    showYear = false
                    showQuality = false
                    showRating = false
                },
                modifier = Modifier
                    .focusRequester(menuFocusRequester)
                    .size(56.dp) // Mărit de la 48dp
                    .background(
                        color = if (isMenuFocused) Color.White else Color.Transparent,
                        shape = CircleShape,
                    ),
                interactionSource = menuInteraction,
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = s.menu,
                    tint = if (isMenuFocused) Color.Black else Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            HomeDropdownMenu(
                expanded = showMenu,
                showLangMenu = showLangMenu,
                langCode = langCode,
                s = s,
                onDismiss = { showMenu = false; showLangMenu = false },
                onMovies = { showMenu = false; viewModel.loadPopularMovies(isSeries = false) },
                onSeries = { showMenu = false; viewModel.loadPopularMovies(isSeries = true) },
                onSettings = { showMenu = false; onShowTorrserver() },
                onTmdbSettings = { showMenu = false; onShowTmdb() },
                onFavorites = { showMenu = false; onShowFavorites() },
                onHistory = { showMenu = false; onShowHistory() },
                onLangMenu = { showLangMenu = true },
                onLangSelect = { code -> showMenu = false; showLangMenu = false; onLanguageChange(code) },
                onAbout = { showMenu = false; onShowAbout() },
                onLogout = { showMenu = false; onLogoutRequest() },
                onBackFromLang = { showLangMenu = false },
            )
        }

        if (!isCollectionMode) {
            Spacer(Modifier.width(20.dp))

            // FIX #5: FilterLists.GENRES în loc de GENRES_LIST top-level
            FilterButton(
                label = if (selectedGenre == "All") s.genres else selectedGenre,
                active = selectedGenre != "All",
                expanded = showGenre,
                items = FilterLists.GENRES,
                itemLabel = { item -> if (item == "All") s.allFilter else item },
                selectedItem = selectedGenre,
                onExpand = {
                    showGenre = true; showMenu = false
                    showLangMenu = false; showYear = false; showQuality = false; showRating = false
                },
                onDismiss = { showGenre = false },
                onSelect = { genre ->
                    showGenre = false
                    viewModel.searchMovies(searchQuery, genre, selectedYear, selectedQuality, selectedRating)
                },
            )
            Spacer(Modifier.width(12.dp))

            FilterButton(
                label = if (selectedYear == "All") s.years else selectedYear,
                active = selectedYear != "All",
                expanded = showYear,
                items = FilterLists.YEARS,
                itemLabel = { item -> if (item == "All") s.allFilter else item },
                selectedItem = selectedYear,
                onExpand = {
                    showYear = true; showMenu = false
                    showLangMenu = false; showGenre = false; showQuality = false; showRating = false
                },
                onDismiss = { showYear = false },
                onSelect = { year ->
                    showYear = false
                    viewModel.searchMovies(searchQuery, selectedGenre, year, selectedQuality, selectedRating)
                },
            )
            Spacer(Modifier.width(12.dp))

            FilterButton(
                label = if (selectedQuality == "All") s.quality else selectedQuality,
                active = selectedQuality != "All",
                expanded = showQuality,
                items = FilterLists.QUALITIES,
                itemLabel = { item -> if (item == "All") s.allFilter else item },
                selectedItem = selectedQuality,
                onExpand = {
                    showQuality = true; showMenu = false
                    showLangMenu = false; showGenre = false; showYear = false; showRating = false
                },
                onDismiss = { showQuality = false },
                onSelect = { q ->
                    showQuality = false
                    viewModel.searchMovies(searchQuery, selectedGenre, selectedYear, q, selectedRating)
                },
            )
            Spacer(Modifier.width(12.dp))

            FilterButton(
                label = if (selectedRating == "All") s.rating else selectedRating,
                active = selectedRating != "All",
                expanded = showRating,
                items = FilterLists.RATINGS,
                itemLabel = { item -> if (item == "All") s.allFilter else item },
                selectedItem = selectedRating,
                onExpand = {
                    showRating = true; showMenu = false
                    showLangMenu = false; showGenre = false; showYear = false; showQuality = false
                },
                onDismiss = { showRating = false },
                onSelect = { r ->
                    showRating = false
                    viewModel.searchMovies(searchQuery, selectedGenre, selectedYear, selectedQuality, r)
                },
            )
        } else {
            Spacer(Modifier.width(20.dp))
            Text(
                text = (if (isFavoritesMode) s.myList else s.history).uppercase(),
                color = SoftBlue,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            )

            Spacer(Modifier.width(20.dp))
            FocusableIconButton(
                    icon = Icons.Default.Delete,
                    contentDescription = if (isFavoritesMode) s.clearMyList else s.clearHistory,
                    onClick = { if (isFavoritesMode) viewModel.clearFavorites() else viewModel.clearHistory() },
                    tintNormal = Color.Gray,
                    tintFocused = Color.Black,
                )
        }

        Spacer(Modifier.weight(1f))

        FocusableIconButton(
            icon = Icons.Default.Mic,
            contentDescription = s.voiceSearch,
            onClick = {
                val voiceIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, s.voiceLang)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, s.searchPlaceholder)
                }
                try {
                    voiceLauncher.launch(voiceIntent)
                } catch (_: ActivityNotFoundException) {
                    externalActionError = s.voiceSearchUnavailable
                } catch (_: SecurityException) {
                    externalActionError = s.voiceSearchUnavailable
                }
            },
        )

        Spacer(Modifier.width(16.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.updateSearchQuery(it) },
            placeholder = { Text(s.searchPlaceholder, color = Grey, fontSize = 15.sp) },
            modifier = Modifier.width(240.dp).height(56.dp), // Redus la 240dp pentru a garanta spațiul pe 55"
            shape = RoundedCornerShape(28.dp),
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Grey, modifier = Modifier.size(20.dp)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = Color(0xFF262626),
                unfocusedContainerColor = Color(0xFF1A1A1A),
                focusedBorderColor = Color.White,
                unfocusedBorderColor = Color(0xFF333333),
            ),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Search
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSearch = {
                    viewModel.searchMovies(
                        searchQuery,
                        selectedGenre,
                        selectedYear,
                        selectedQuality,
                        selectedRating
                    )
                }
            ),
        )
    }
}

// ─── Buton filtru cu popup ─────────────────────────────────────────────────────
@Composable
private fun FilterButton(
    label: String,
    active: Boolean,
    expanded: Boolean,
    items: List<String>,
    itemLabel: (String) -> String,
    selectedItem: String,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Box {
        NetflixHeaderButton(text = label, active = active, onClick = onExpand)
        HorizontalSelectionMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            items = items,
            selectedItem = selectedItem,
            itemLabel = itemLabel,
            onItemSelected = onSelect,
        )
    }
}

// ─── Dropdown menu principal ───────────────────────────────────────────────────
@Composable
private fun HomeDropdownMenu(
    expanded: Boolean,
    showLangMenu: Boolean,
    langCode: String,
    s: AppStrings,
    onDismiss: () -> Unit,
    onMovies: () -> Unit,
    onSeries: () -> Unit,
    onSettings: () -> Unit,
    onTmdbSettings: () -> Unit,
    onFavorites: () -> Unit,
    onHistory: () -> Unit,
    onLangMenu: () -> Unit,
    onLangSelect: (String) -> Unit,
    onAbout: () -> Unit,
    onLogout: () -> Unit,
    onBackFromLang: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(Color(0xFF1E1E1E)),
    ) {
        if (!showLangMenu) {
            DropdownMenuItem(text = { Text(s.movies, color = Color.White) }, onClick = onMovies, leadingIcon = { Icon(Icons.Default.Movie, null, tint = SoftBlue) })
            DropdownMenuItem(text = { Text(s.series, color = Color.White) }, onClick = onSeries, leadingIcon = { Icon(Icons.Default.Tv, null, tint = SoftBlue) })
            HorizontalDivider(color = Color.DarkGray)
            DropdownMenuItem(text = { Text(s.myList, color = Color.White) }, onClick = onFavorites, leadingIcon = { Icon(Icons.Default.Favorite, null, tint = SoftBlue) })
            DropdownMenuItem(text = { Text(s.history, color = Color.White) }, onClick = onHistory, leadingIcon = { Icon(Icons.Default.History, null, tint = SoftBlue) })
            DropdownMenuItem(text = { Text(s.torrserverSettings, color = Color.White) }, onClick = onSettings, leadingIcon = { Icon(Icons.Default.Settings, null, tint = SoftBlue) })
            DropdownMenuItem(text = { Text(s.tmdbSettings, color = Color.White) }, onClick = onTmdbSettings, leadingIcon = { Icon(Icons.Default.Settings, null, tint = SoftBlue) })
            
            // Preferred player clearing option
            val context = LocalContext.current
            DropdownMenuItem(
                text = { Text(s.clearPreferredPlayer, color = Color.White) },
                onClick = { 
                    onDismiss()
                    PreferredPlayerStore.clear(context)
                },
                leadingIcon = { Icon(Icons.Default.PlayArrow, null, tint = SoftBlue) }
            )

            DropdownMenuItem(text = { Text(s.changeLanguage, color = Color.White) }, onClick = onLangMenu, leadingIcon = { Icon(Icons.Default.Language, null, tint = SoftBlue) })
            DropdownMenuItem(text = { Text(s.about, color = Color.White) }, onClick = onAbout, leadingIcon = { Icon(Icons.Default.Info, null, tint = SoftBlue) })
            HorizontalDivider(color = Color.DarkGray)
            DropdownMenuItem(
                text = { Text(s.logout, color = Color(0xFFE53935)) },
                onClick = onLogout,
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null, tint = Color(0xFFE53935)) },
            )
        } else {
            DropdownMenuItem(text = { Text(s.back, color = Grey) }, onClick = onBackFromLang, leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Grey) })
            Translations.forEach { (code, trans) ->
                DropdownMenuItem(
                    text = { Text(trans.langName, color = if (langCode == code) SoftBlue else Color.White) },
                    onClick = { onLangSelect(code) },
                )
            }
        }
    }
}

// ─── Dialog Torrserver Settings ────────────────────────────────────────────────
@Composable
private fun TorrserverSettingsDialog(
    s: AppStrings,
    viewModel: MovieViewModel,
    onDismiss: () -> Unit,
) {
    val torrserverViewModel: TorrserverViewModel = viewModel()
    val config by torrserverViewModel.config.collectAsStateWithLifecycle()

    LaunchedEffect(config.primaryAddress) {
        viewModel.saveTorrserverAddress(config.primaryAddress)
        if (config.primaryAddress.isNotEmpty()) {
            torrserverViewModel.testConnection(config.primaryAddress)
        }
    }

    OptimizedTorrserverDialog(
        s = s,
        torrserverViewModel = torrserverViewModel,
        onDismiss = onDismiss,
    )
}

@Composable
private fun TmdbSettingsDialog(
    s: AppStrings,
    currentKey: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var key by remember { mutableStateOf(currentKey) }
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.tmdbSettings, color = Color.White) },
        text = {
            Column {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API Key", color = Color.Gray) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = SoftBlue,
                    )
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Introduceți cheia v3 API de pe themoviedb.org pentru a activa catalogul complet de seriale și postere de înaltă calitate.",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(key); onDismiss() }) {
                Text(s.save, color = SoftBlue)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(s.cancel, color = Color.Gray)
            }
        },
        containerColor = Color(0xFF1A1A1A),
    )

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }
}

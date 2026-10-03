package io.github.flavyu22.movietorrentsearchtv.ui.screens

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.launch
import io.github.flavyu22.movietorrentsearchtv.model.*
import io.github.flavyu22.movietorrentsearchtv.playback.PlaybackIntentHelper
import io.github.flavyu22.movietorrentsearchtv.playback.PlaybackResult
import io.github.flavyu22.movietorrentsearchtv.playback.TorrserverPlaybackManager
import io.github.flavyu22.movietorrentsearchtv.ui.components.HorizontalSelectionMenu
import io.github.flavyu22.movietorrentsearchtv.ui.components.NetflixHeaderButton
import io.github.flavyu22.movietorrentsearchtv.ui.components.QualityBadge
import io.github.flavyu22.movietorrentsearchtv.ui.components.SourceLoadingChip
import io.github.flavyu22.movietorrentsearchtv.ui.components.TorrserverErrorBanner
import io.github.flavyu22.movietorrentsearchtv.ui.components.TorrserverProcessingBanner
import io.github.flavyu22.movietorrentsearchtv.ui.components.preferredPosterUrl
import io.github.flavyu22.movietorrentsearchtv.ui.theme.AlmostBlack
import io.github.flavyu22.movietorrentsearchtv.ui.theme.Grey
import io.github.flavyu22.movietorrentsearchtv.ui.theme.SoftBlue
import io.github.flavyu22.movietorrentsearchtv.viewmodel.AggregatedTorrentViewModel
import io.github.flavyu22.movietorrentsearchtv.viewmodel.MovieViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// MOVIE DETAILS SCREEN - ULTRA-PRO OPTIMIZED VERSION
// ─────────────────────────────────────────────────────────────────────────────

/** Rating rendering shared by the identity panel. */
private fun formatRating(rating: Double): String =
    String.format(java.util.Locale.US, "%.1f", rating)

@Composable
fun MovieDetailsScreen(
    movie: Movie,
    viewModel: MovieViewModel,
    langCode: String,
    onBack: () -> Unit,
    onTriggerPlayback: () -> Unit,
    onOpenBrowser: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aggregatedViewModel: AggregatedTorrentViewModel = viewModel()
    val s = remember(langCode) { Translations[langCode] ?: Translations["EN"]!! }
    val favoriteMovieIds by viewModel.favoriteMovieIds.collectAsStateWithLifecycle()
    val isFavorite = movie.id in favoriteMovieIds

    // ── State ────────────────────────────────────────────────────────────────
    var isProcessingTorrserver by remember { mutableStateOf(false) }
    var torrserverError by remember { mutableStateOf<String?>(null) }
    var playbackJob by remember(movie.id) { mutableStateOf<Job?>(null) }
    var playbackGeneration by remember(movie.id) { mutableIntStateOf(0) }
    val firstFocus = remember(movie.id) { FocusRequester() }
    val backFocus = remember(movie.id) { FocusRequester() }
    val retryFocus = remember(movie.id) { FocusRequester() }
    val actionErrorFocus = remember(movie.id) { FocusRequester() }
    var hasRequestedInitialFocus by remember(movie.id) { mutableStateOf(false) }
    var externalActionError by remember(movie.id) { mutableStateOf<String?>(null) }

    // ── Multi-source state ────────────────────────────────────────────────────
    val aggUiState by aggregatedViewModel.uiState.collectAsStateWithLifecycle()
    val unifiedTorrents = aggUiState.torrents
    val isMultiSourceLoading = aggUiState.isLoading
    val loadingProgress = aggUiState.loadingProgress
    val qualities = aggUiState.availableQualities
    val selectedQualityFilter = aggUiState.currentQuality
    val languageFilterEnabled by aggregatedViewModel.torrentLanguageFilterEnabled
        .collectAsStateWithLifecycle()

    val listState = rememberLazyListState()

    /**
     * Static, low-RAM-conscious hero sizing.
     *
     * An earlier revision animated the poster's width/height on scroll and mounted it
     * conditionally. On a constrained TV that was actively harmful: the conditional
     * mount destroyed and recreated the AsyncImage (a fresh RGB_565 decode of the
     * artwork on every collapse/expand) and the animated values recomposed the poster
     * subtree on every animation frame. The reclaimed height is instead obtained once,
     * statically, by showing less information above the list.
     */

    val loadingProgressEntries = remember(loadingProgress) {
        loadingProgress.entries.sortedBy(Map.Entry<String, Int>::key)
    }

    // ── Torrserver ────────────────────────────────────────────────────────────
    val torrserverAddress by viewModel.torrserverAddress.collectAsStateWithLifecycle()
    val torrserverBaseUrl = remember(torrserverAddress) { torrserverAddress.orEmpty().trimEnd('/') }
    val playbackManager = remember(torrserverBaseUrl) {
        if (torrserverBaseUrl.isNotEmpty()) TorrserverPlaybackManager(torrserverBaseUrl) else null
    }

    // ── ImageRequest poster (Ultra-Optimized) ─────────────────────────────────
    val isLowRamDevice = remember(context) {
        val manager = context.getSystemService(ActivityManager::class.java)
        manager?.isLowRamDevice == true || (manager?.memoryClass ?: 256) <= 128
    }
    // Decode size follows the rendered poster (112x168dp) so the bitmap matches what is
    // actually drawn. On low-RAM TVs a smaller decode directly reduces heap per request.
    val detailPosterWidthPx = if (isLowRamDevice) 224 else 336
    val detailPosterHeightPx = if (isLowRamDevice) 336 else 504
    val posterRequest = remember(
        movie.id,
        movie.largePosterPath,
        movie.mediumPosterPath,
        movie.posterPath,
        isLowRamDevice,
    ) {
        ImageRequest.Builder(context)
            .data(preferredPosterUrl(movie.largePosterPath, movie.mediumPosterPath, movie.posterPath))
            .size(detailPosterWidthPx, detailPosterHeightPx)
            .crossfade(!isLowRamDevice)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    // ── Playback Logic (Stabilized) ───────────────────────────────────────────
    val onAddMagnetAndPlayStable = remember(
        playbackManager,
        movie.id,
        movie.title,
        onTriggerPlayback,
        s,
    ) {
        { link: String, title: String ->
            playbackGeneration += 1
            val generation = playbackGeneration
            playbackJob?.cancel()
            val manager = playbackManager
            val isMagnet = link.startsWith("magnet:", ignoreCase = true)

            if (manager == null) {
                viewModel.addToHistory(movie)
                onTriggerPlayback()
                PlaybackIntentHelper.playFallback(
                    context,
                    link,
                    title,
                    isMagnet = isMagnet,
                    strings = s,
                )
            } else {
                val infoHash = manager.extractInfoHash(link)
                isProcessingTorrserver = true
                torrserverError = null
                playbackJob = scope.launch {
                    try {
                        val result = manager.resolveStream(link, title, infoHash)
                        if (generation != playbackGeneration) return@launch
                        when (result) {
                            is PlaybackResult.Stream -> {
                                viewModel.addToHistory(movie)
                                onTriggerPlayback()
                                PlaybackIntentHelper.playStream(
                                    context,
                                    result.url,
                                    result.title,
                                    s,
                                )
                            }
                            is PlaybackResult.Error -> {
                                torrserverError = s.actionUnavailableTitle
                                viewModel.addToHistory(movie)
                                onTriggerPlayback()
                                PlaybackIntentHelper.playFallback(
                                    context,
                                    result.magnetUrl,
                                    result.title,
                                    isMagnet = isMagnet,
                                    strings = s,
                                )
                            }
                            is PlaybackResult.Fallback -> {
                                viewModel.addToHistory(movie)
                                onTriggerPlayback()
                                PlaybackIntentHelper.playFallback(
                                    context,
                                    result.magnetUrl,
                                    result.title,
                                    isMagnet = isMagnet,
                                    strings = s,
                                )
                            }
                        }
                    } finally {
                        if (generation == playbackGeneration) isProcessingTorrserver = false
                    }
                }
            }
            Unit
        }
    }

    // ── Effects ──────────────────────────────────────────────────────────────
    LaunchedEffect(movie.id) {
        aggregatedViewModel.searchMovies(movie)
        withFrameNanos { }
        runCatching { backFocus.requestFocus() }
    }

    DisposableEffect(movie.id) {
        onDispose { playbackJob?.cancel() }
    }

    LaunchedEffect(unifiedTorrents) {
        val hasData = unifiedTorrents.isNotEmpty()
        if (hasData && !hasRequestedInitialFocus) {
            hasRequestedInitialFocus = true
            withFrameNanos { }
            try {
                listState.scrollToItem(0)
                firstFocus.requestFocus()
            } catch (error: Exception) {
                Log.d("MovieDetailsScreen", "Initial torrent focus was not available", error)
            }
        }
    }

    LaunchedEffect(isMultiSourceLoading, unifiedTorrents, aggUiState.isEmptyResult, aggUiState.sourceErrors) {
        if (!isMultiSourceLoading && unifiedTorrents.isEmpty()) {
            withFrameNanos { }
            if (aggUiState.sourceErrors.isNotEmpty() || aggUiState.isEmptyResult) {
                runCatching { retryFocus.requestFocus() }
            } else {
                runCatching { backFocus.requestFocus() }
            }
        }
    }

    externalActionError?.let { message ->
        LaunchedEffect(message) {
            withFrameNanos { }
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

    // ── UI UI UI ─────────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize().background(AlmostBlack)) {
        
        Column(modifier = Modifier.fillMaxSize()) {
            DetailsTopBar(
                s = s,
                backFocus = backFocus,
                onBack = onBack,
                onRefresh = {
                    hasRequestedInitialFocus = false
                    aggregatedViewModel.searchMovies(movie, forceRefresh = true)
                },
            )

            // Premium hero: real 2:3 artwork instead of the previous 40x60dp chip, which
            // read as a thumbnail beside a large empty area and looked unfinished. The hero
            // is one bounded block (gradient card + single identity column), so it still
            // costs far less height than the old full-height poster column, and the magnet
            // list below keeps the entire remaining width.
            // The artwork is always mounted (never conditionally) so constrained TVs never
            // pay for a repeated bitmap decode on focus changes.
            // The hero is a centred column rather than a full-width band: the poster stays
            // on the left and the identity block sits in the middle of the screen instead of
            // being pushed against the right edge with a large empty gap beside it.
            // Centring is layout only — it costs nothing on a constrained TV.
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF23252B), Color(0xFF15161A)),
                        ),
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(18.dp))
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = posterRequest,
                        contentDescription = movie.title,
                        modifier = Modifier
                            .width(112.dp)
                            .height(168.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                1.dp,
                                Color.White.copy(alpha = 0.12f),
                                RoundedCornerShape(12.dp),
                            ),
                        contentScale = ContentScale.Crop,
                    )

                    Spacer(Modifier.width(18.dp))

                // Identity: title/year on the first line, genres/rating on the second.
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = listOfNotNull(
                            movie.year?.toString()?.takeIf { it.isNotBlank() },
                            movie.title.orEmpty().takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 22.sp,
                        lineHeight = 26.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    val metaLine = listOfNotNull(
                        movie.genres?.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                        movie.rating?.takeIf { it > 0.0 }?.let { "⭐ ${formatRating(it)}" },
                    ).joinToString("    ")
                    if (metaLine.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = metaLine,
                            color = Grey,
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        NetflixHeaderButton(
                            text = if (isFavorite) s.removeFromMyList else s.addToMyList,
                            icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            active = isFavorite,
                            onClick = { viewModel.toggleFavorite(movie) },
                        )

                        if (!movie.ytTrailerCode.isNullOrBlank()) {
                            Spacer(Modifier.width(10.dp))
                            NetflixHeaderButton(
                                text = s.trailer,
                                icon = Icons.Default.PlayCircleOutline,
                                onClick = {
                                    movie.ytTrailerCode.let { code ->
                                        val trailerUri = Uri.Builder()
                                            .scheme("https")
                                            .authority("www.youtube.com")
                                            .path("watch")
                                            .appendQueryParameter("v", code)
                                            .build()
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, trailerUri))
                                        } catch (_: ActivityNotFoundException) {
                                            externalActionError = s.trailerUnavailable
                                        } catch (_: SecurityException) {
                                            externalActionError = s.trailerUnavailable
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                }
            }
            }

            Spacer(Modifier.height(8.dp))

            TorrentResultsColumnOptimized(
                    unifiedTorrents = unifiedTorrents,
                    isLoading = isMultiSourceLoading,
                    isEmptyResult = aggUiState.isEmptyResult,
                    sourceErrors = aggUiState.sourceErrors,
                    loadingProgressEntries = loadingProgressEntries,
                    qualities = qualities,
                    selectedQualityFilter = selectedQualityFilter,
                    onQualitySelected = { quality ->
                        aggregatedViewModel.filterByQuality(quality)
                    },
                    languageFilterEnabled = languageFilterEnabled,
                    onToggleLanguageFilter = {
                        aggregatedViewModel.setTorrentLanguageFilter(!languageFilterEnabled)
                    },
                    currentSort = aggUiState.currentSort,
                    onSortSelected = { option -> aggregatedViewModel.sortBy(option) },
                    isProcessingTorrserver = isProcessingTorrserver,
                    torrserverError = torrserverError,
                    firstFocus = firstFocus,
                    retryFocus = retryFocus,
                    listState = listState,
                    torrserverAddress = torrserverAddress.orEmpty(),
                    s = s,
                    onRetry = { aggregatedViewModel.searchMovies(movie, forceRefresh = true) },
                    onAddMagnetAndPlay = onAddMagnetAndPlayStable,
                    onOpenBrowser = onOpenBrowser,
            )
        }
    }
}

// ────────────────────────────── COMPONENTS ────────────────────────────────────

/**
 * Slim top bar: navigation on the left, refresh on the right.
 *
 * It carries no title or metadata; the film identity lives in the single-line header
 * row directly below it, so the results list starts as high as possible on a 540dp TV.
 */
@Composable
private fun DetailsTopBar(
    s: AppStrings,
    backFocus: FocusRequester,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetflixHeaderButton(
            text = s.back,
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            onClick = onBack,
            modifier = Modifier.focusRequester(backFocus),
        )

        Spacer(Modifier.weight(1f))

        NetflixHeaderButton(
            text = s.refresh,
            icon = Icons.Default.Refresh,
            onClick = onRefresh,
        )
    }
}

@Composable
private fun ColumnScope.TorrentResultsColumnOptimized(
    unifiedTorrents: List<UnifiedTorrent>,
    isLoading: Boolean,
    isEmptyResult: Boolean,
    sourceErrors: Map<String, String>,
    loadingProgressEntries: List<Map.Entry<String, Int>>,
    qualities: List<String>,
    selectedQualityFilter: String,
    onQualitySelected: (String) -> Unit,
    languageFilterEnabled: Boolean,
    onToggleLanguageFilter: () -> Unit,
    currentSort: AggregatedTorrentViewModel.SortOption,
    onSortSelected: (AggregatedTorrentViewModel.SortOption) -> Unit,
    isProcessingTorrserver: Boolean,
    torrserverError: String?,
    firstFocus: FocusRequester,
    retryFocus: FocusRequester,
    listState: LazyListState,
    torrserverAddress: String,
    s: AppStrings,
    onRetry: () -> Unit,
    onAddMagnetAndPlay: (String, String) -> Unit,
    onOpenBrowser: (String) -> Unit,
) {
    Column(modifier = Modifier.weight(1f)) {
        var showSortMenu by remember { mutableStateOf(false) }
        // --- Sources Loading Progress ---
        if (isLoading && loadingProgressEntries.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                items(loadingProgressEntries, key = { it.key }) { entry ->
                    SourceLoadingChip(entry.key, entry.value)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (unifiedTorrents.isNotEmpty() && qualities.size > 1) {
                val qualityListState = rememberLazyListState()
                LazyRow(
                    state = qualityListState,
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                    flingBehavior = rememberSnapFlingBehavior(lazyListState = qualityListState, snapPosition = SnapPosition.Start)
                ) {
                    items(qualities, key = { it }) { quality ->
                        val isSelected = selectedQualityFilter == quality
                        val interactionSource = remember { MutableInteractionSource() }
                        val isFocused by interactionSource.collectIsFocusedAsState()

                        // FOCUS SIMPLU: fără scale animat — doar culorile chip-ului schimbă.

                        FilterChip(
                            selected = isSelected,
                            onClick = { onQualitySelected(quality) },
                            interactionSource = interactionSource,
                            label = { 
                                Text(
                                    text = quality, 
                                    fontWeight = if (isSelected || isFocused) FontWeight.Black else FontWeight.Medium,
                                    fontSize = 14.sp
                                ) 
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SoftBlue,
                                containerColor = if (isFocused) Color.White else Color(0xFF262626),
                                labelColor = if (isFocused) Color.Black else Color.White,
                                selectedLabelColor = Color.Black
                            ),
                            shape = RoundedCornerShape(16.dp),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = Color.White.copy(alpha = 0.1f),
                                selectedBorderColor = SoftBlue,
                                borderWidth = 1.dp,
                                selectedBorderWidth = 2.dp,
                                enabled = true,
                                selected = isSelected
                            )
                        )
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }

            // --- Torrent language filter (only releases in the app language) ---
            if (unifiedTorrents.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                val languageChipInteraction = remember { MutableInteractionSource() }
                val languageChipFocused by languageChipInteraction.collectIsFocusedAsState()
                FilterChip(
                    selected = languageFilterEnabled,
                    onClick = onToggleLanguageFilter,
                    interactionSource = languageChipInteraction,
                    label = {
                        Text(
                            text = if (languageFilterEnabled) s.torrentLanguageFilter else s.allFilter,
                            fontWeight = if (languageFilterEnabled || languageChipFocused) FontWeight.Black else FontWeight.Medium,
                            fontSize = 14.sp
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SoftBlue,
                        containerColor = if (languageChipFocused) Color.White else Color(0xFF262626),
                        labelColor = if (languageChipFocused) Color.Black else Color.White,
                        selectedLabelColor = Color.Black
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = Color.White.copy(alpha = 0.1f),
                        selectedBorderColor = SoftBlue,
                        borderWidth = 1.dp,
                        selectedBorderWidth = 2.dp,
                        enabled = true,
                        selected = languageFilterEnabled
                    )
                )
            }

            // --- Sort selector ---
            if (unifiedTorrents.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Box {
                    val sortOptions = remember { AggregatedTorrentViewModel.SortOption.entries.toList() }
                    val sortLabel: (AggregatedTorrentViewModel.SortOption) -> String = remember(s) {
                        { option ->
                            when (option) {
                                AggregatedTorrentViewModel.SortOption.DATE_DESC -> s.sortDate
                                AggregatedTorrentViewModel.SortOption.SEEDS_DESC -> s.sortSeeds
                                AggregatedTorrentViewModel.SortOption.QUALITY_DESC -> s.sortQuality
                                AggregatedTorrentViewModel.SortOption.SIZE_ASC -> s.sortSize
                                AggregatedTorrentViewModel.SortOption.SOURCE -> s.sortSource
                            }
                        }
                    }
                    NetflixHeaderButton(
                        text = "${s.sortBy}: ${sortLabel(currentSort)}",
                        icon = Icons.AutoMirrored.Filled.Sort,
                        onClick = { showSortMenu = true },
                    )
                    HorizontalSelectionMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                        items = sortOptions.map(sortLabel),
                        selectedItem = sortLabel(currentSort),
                        onItemSelected = { label ->
                            showSortMenu = false
                            sortOptions.firstOrNull { sortLabel(it) == label }?.let(onSortSelected)
                        },
                    )
                }
            }

            // --- Torrserver Status ---
            if (isProcessingTorrserver || torrserverError != null) {
                Spacer(Modifier.width(12.dp))
                if (isProcessingTorrserver) {
                    TorrserverProcessingBanner(
                        message = s.processingTorrserver,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                torrserverError?.let {
                    TorrserverErrorBanner(
                        message = s.torrserverError.format(it),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
        
        Spacer(Modifier.height(4.dp))

        if (unifiedTorrents.isEmpty() && isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = s.searchingMultiple
                    },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = SoftBlue)
            }
        } else if (unifiedTorrents.isEmpty() && sourceErrors.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        liveRegion = LiveRegionMode.Assertive
                        contentDescription = s.noSources
                    },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = buildString {
                        append(s.catalogueNetworkIssue)
                        val sources = sourceErrors.keys.filter(String::isNotBlank)
                        if (sources.isNotEmpty()) append(" (${sources.joinToString()})")
                    },
                    color = Color.White,
                    fontSize = 16.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(16.dp))
                NetflixHeaderButton(
                    text = s.retry,
                    icon = Icons.Default.Refresh,
                    modifier = Modifier.focusRequester(retryFocus),
                    onClick = onRetry,
                )
            }
        } else if (unifiedTorrents.isEmpty() && isEmptyResult) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = s.noSources
                    },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = s.noSources, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                NetflixHeaderButton(
                    text = s.retry,
                    icon = Icons.Default.Refresh,
                    modifier = Modifier.focusRequester(retryFocus),
                    onClick = onRetry,
                )
            }
        } else if (unifiedTorrents.isEmpty() && !isLoading) {
            // Explicit state for when search finished but list is empty (fallback)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = s.noSources
                    },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = s.noSources, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                NetflixHeaderButton(
                    text = s.retry,
                    icon = Icons.Default.Refresh,
                    modifier = Modifier.focusRequester(retryFocus),
                    onClick = onRetry,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
            ) {
                itemsIndexed(
                    items = unifiedTorrents,
                    key = { _, it -> "${it.source}_${it.infoHash}" }
                ) { index, torrent ->
                    UnifiedTorrentRowOptimized(
                        torrent = torrent,
                        torrserverAddress = torrserverAddress,
                        onAddMagnetAndPlay = onAddMagnetAndPlay,
                        onOpenBrowser = onOpenBrowser,
                        s = s,
                        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier
                    )
                }
            }
        }
    }
}

@Composable
private fun UnifiedTorrentRowOptimized(
    torrent: UnifiedTorrent,
    torrserverAddress: String,
    onAddMagnetAndPlay: (String, String) -> Unit,
    onOpenBrowser: (String) -> Unit,
    s: AppStrings,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // Focus change is applied directly instead of through animateColorAsState.
    // An animation per row keeps a recomposition frame and an interpolator alive for every
    // visible card, and D-pad focus is instantaneous here anyway (the row simply turns
    // white). This is the cheapest win available on constrained TVs.
    val backgroundColor = if (isFocused) Color.White else Color(0xFF1E1E1E).copy(alpha = 0.7f)

    val sourceColor = remember(torrent.source) {
        when (torrent.source.uppercase()) {
            "YTS" -> Color(0xFF4CAF50)
            "1337X" -> Color(0xFFE91E63)
            "EZTV" -> Color(0xFFFF9800)
            "TPB" -> Color(0xFF9C27B0)
            "TGX" -> Color(0xFF00BCD4)
            else -> SoftBlue
        }
    }
    val safeOriginalLink = remember(torrent.originalLink) {
        torrent.originalLink?.let(::validateHttpsUrl)
    }
    val torrentDescription = remember(torrent, s) {
        s.torrentResultDescription.format(
            torrent.title,
            torrent.quality,
            torrent.size,
            torrent.seeds,
        )
    }

    Surface(
        onClick = { onAddMagnetAndPlay(torrent.magnetUrl, torrent.title) },
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = torrentDescription
            },
        color = backgroundColor,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (isFocused) 3.dp else 1.dp,
            color = if (isFocused) Color.White else Color.White.copy(alpha = 0.1f)
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(modifier = Modifier.padding(vertical = 5.dp, horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = sourceColor.copy(alpha = if (isFocused) 0.35f else 0.15f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(torrent.source, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                     color = if (isFocused) Color.Black else sourceColor, fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = torrent.title, 
                    color = if (isFocused) Color.Black else Color.White, 
                    fontSize = 15.sp, 
                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 2, 
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    QualityBadge(torrent.quality, isFocused)
                    Spacer(Modifier.width(12.dp))
                    Text(torrent.size, color = if (isFocused) Color.DarkGray else Grey, fontSize = 12.sp)
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                        contentDescription = s.sortSeeds,
                        tint = if (isFocused) Color.Black else Color(0xFF4CAF50),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(torrent.seeds.toString(), color = if (isFocused) Color.Black else Color(0xFF4CAF50), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    torrent.seasonEpisode?.let {
                        Spacer(Modifier.width(10.dp))
                        Text(it, color = if (isFocused) Color.Black else SoftBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            
            IconButton(onClick = {
                clipboardScope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText("magnet", torrent.magnetUrl))
                    )
                }
                Toast.makeText(context, s.magnetLinkCopied, Toast.LENGTH_SHORT).show()
            }) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = s.copyMagnetLink,
                    tint = if (isFocused) Color.Black else SoftBlue,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(4.dp))

            safeOriginalLink?.let { sourceUrl ->
                IconButton(onClick = { onOpenBrowser(sourceUrl) }) {
                    Icon(
                        imageVector = Icons.Default.OpenInBrowser,
                        contentDescription = s.openSourcePage,
                        tint = if (isFocused) Color.Black else SoftBlue,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = if (isFocused) Color.Black.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(22.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (torrserverAddress.isBlank()) Icons.Default.OpenInBrowser else Icons.Default.PlayArrow,
                    contentDescription = s.playTorrent,
                    tint = if (isFocused) Color.Black else SoftBlue,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

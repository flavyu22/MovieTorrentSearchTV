package io.github.flavyu22.movietorrentsearchtv.ui.screens

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Language
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt
import io.github.flavyu22.movietorrentsearchtv.model.*
import io.github.flavyu22.movietorrentsearchtv.playback.PlaybackIntentHelper
import io.github.flavyu22.movietorrentsearchtv.playback.PlaybackResult
import io.github.flavyu22.movietorrentsearchtv.playback.TorrserverPlaybackManager
import io.github.flavyu22.movietorrentsearchtv.ui.components.HorizontalSelectionMenu
import io.github.flavyu22.movietorrentsearchtv.ui.components.NetflixHeaderButton
import io.github.flavyu22.movietorrentsearchtv.ui.components.QualityBadge
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

// Hero poster decode bounds. The detail pane renders at most 200dp of artwork, so these
// only need to bracket the density range a TV panel can realistically report: the lower
// bound avoids decoding a visibly blocky poster on very low-density sets, the upper
// bound stops a high-density panel from pulling a multi-megabyte bitmap for a 232dp
// column. A low-RAM device additionally scales the result down.
private const val MIN_POSTER_DECODE_PX = 240
private const val MAX_POSTER_DECODE_PX = 512
private const val LOW_RAM_POSTER_SCALE = 0.75f

/**
 * How many consecutive frames the Details screen retries `requestFocus()` on the first
 * torrent row before giving up.
 *
 * A [androidx.compose.ui.focus.FocusRequester] bound to an item that the LazyColumn has
 * not composed yet throws on `requestFocus()`, and composition of item 0 can land a frame
 * or two after the list itself. Retrying for a bounded number of frames covers that window
 * without an unbounded loop that would keep spinning if the row genuinely never appears.
 */
private const val FOCUS_REQUEST_ATTEMPTS = 10

// ─────────────────────────────────────────────────────────────────────────────
// MOVIE DETAILS SCREEN - ULTRA-PRO OPTIMIZED VERSION
// ─────────────────────────────────────────────────────────────────────────────

/** Rating rendering shared by the identity panel. */
private fun formatRating(rating: Double): String =
    String.format(Locale.US, "%.1f", rating)

/**
 * Calendar day of an upload timestamp, formatted for a 10-foot UI: a short numeric date
 * (`2024-03-07`) instead of a locale-formatted long date, because the row already has to fit
 * several chips on one line and a word like "March" would overflow it.
 *
 * [SimpleDateFormat] is expensive to construct and not thread-safe, so one instance per thread
 * is created lazily and reused for every visible row.
 */
private val UploadDayFormat: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
}

/** Returns a short upload day, or `null` when the timestamp is missing or unparseable. */
private fun formatUploadDay(millis: Long): String? {
    if (millis <= 0L) return null
    return runCatching { UploadDayFormat.get()!!.format(Date(millis)) }.getOrNull()
}

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
    // True while the first torrent row still owes this screen an initial focus request.
    // Cleared only once `requestFocus()` actually succeeded, so a failed attempt (the row was
    // not composed yet) is retried instead of being consumed.
    var pendingFirstRowFocus by remember(movie.id) { mutableStateOf(true) }
    var externalActionError by remember(movie.id) { mutableStateOf<String?>(null) }

    // ── Multi-source state ────────────────────────────────────────────────────
    val aggUiState by aggregatedViewModel.uiState.collectAsStateWithLifecycle()
    val unifiedTorrents = aggUiState.torrents
    val isMultiSourceLoading = aggUiState.isLoading
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
    // Decode size follows the rendered poster in the left detail pane. The pane is
    // 232dp wide with 16dp of padding on each side, so exactly 200dp of artwork is
    // displayed. Decoding a fixed 512x768 regardless of the panel wasted heap on every
    // TV whose density is lower than the ~640dpi this constant was tuned for (a 320dpi
    // set only ever needs ~400px, i.e. ~0.9 MB instead of ~1.5 MB per poster), while a
    // denser panel is capped so a poster can never balloon the heap.
    val density = LocalDensity.current.density
    val detailPosterWidthPx = remember(density, isLowRamDevice) {
        val paneArtworkDp = 200f
        val derived = (paneArtworkDp * density).roundToInt()
            .coerceIn(MIN_POSTER_DECODE_PX, MAX_POSTER_DECODE_PX)
        if (isLowRamDevice) (derived * LOW_RAM_POSTER_SCALE).roundToInt() else derived
    }
    // Posters are authored 2:3, so the height follows from the width in one multiply.
    val detailPosterHeightPx = remember(detailPosterWidthPx) {
        detailPosterWidthPx * 3 / 2
    }
    val posterRequest = remember(
        movie.id,
        movie.largePosterPath,
        movie.mediumPosterPath,
        movie.posterPath,
        detailPosterWidthPx,
        detailPosterHeightPx,
    ) {
        ImageRequest.Builder(context)
            .data(preferredPosterUrl(movie.largePosterPath, movie.mediumPosterPath, movie.posterPath))
            .size(detailPosterWidthPx, detailPosterHeightPx)
            // A crossfade keeps the previous and the incoming bitmap alive at the same
            // time. For the single largest image on the screen that doubles peak heap for
            // a purely cosmetic transition, so it stays off for constrained devices and
            // the poster simply appears when it is ready.
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
    }

    DisposableEffect(movie.id) {
        onDispose { playbackJob?.cancel() }
    }

    // ── Focus arbitration ─────────────────────────────────────────────────────
    // Three effects used to compete for the initial focus: the movie.id one parked
    // it on the Back button while loading, the torrents one asked for the first row,
    // and the empty-state one asked for Retry/Back. All of them resume on a frame
    // callback, so when a search resolved fast (or was still cached from a previous
    // visit) two of them ran in the same frame and the winner was arbitrary. That is
    // why focus sometimes landed on the Back button instead of the first torrent.
    //
    // One effect now owns the decision: the first row always wins whenever there are
    // rows, and Retry/Back are only fallbacks for the states that have no row to
    // focus. `firstFocus.requestFocus()` throws while the LazyColumn has not composed
    // its first item yet, so the request is retried across a few frames instead of
    // being swallowed by a try/catch that left the flag already consumed.
    LaunchedEffect(
        movie.id,
        pendingFirstRowFocus,
        unifiedTorrents,
        isMultiSourceLoading,
        aggUiState.isEmptyResult,
        aggUiState.sourceErrors,
        aggUiState.totalResultsCount,
    ) {
        if (unifiedTorrents.isNotEmpty()) {
            // Results are on screen, so the first row is the only correct landing spot,
            // even while other sources are still streaming in.
            if (!pendingFirstRowFocus) return@LaunchedEffect
            listState.scrollToItem(0)
            repeat(FOCUS_REQUEST_ATTEMPTS) {
                if (runCatching { firstFocus.requestFocus() }.isSuccess) {
                    pendingFirstRowFocus = false
                    return@LaunchedEffect
                }
                withFrameNanos { }
            }
            return@LaunchedEffect
        }

        if (isMultiSourceLoading) {
            // Nothing to focus but the chrome while the sources answer. Keep the Back
            // button reachable so the D-pad is not dead; the row branch above takes
            // over as soon as rows exist.
            runCatching { backFocus.requestFocus() }
            return@LaunchedEffect
        }

        withFrameNanos { }
        // `totalResultsCount > 0` means the sources did answer and the active filters hid
        // every row. That renders the filter-reset state, whose only button carries
        // `retryFocus`, so it must be selected here too. Falling through to the `else`
        // parked focus on the back button while the screen showed the filter-reset state.
        val filtersHidEverything = aggUiState.totalResultsCount > 0
        if (filtersHidEverything || aggUiState.sourceErrors.isNotEmpty() || aggUiState.isEmptyResult) {
            runCatching { retryFocus.requestFocus() }
        } else {
            runCatching { backFocus.requestFocus() }
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
                // Film identity now lives in the bar itself, centred between the two nav
                // buttons, so the results list can start higher on a 540dp TV.
                title = listOfNotNull(
                    movie.year?.toString()?.takeIf { it.isNotBlank() },
                    movie.title.orEmpty().takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                backFocus = backFocus,
                onBack = onBack,
                onRefresh = {
                    // Re-arm the first-row focus so Refresh lands on the first torrent
                    // again instead of leaving focus wherever the user had walked to.
                    pendingFirstRowFocus = true
                    aggregatedViewModel.searchMovies(movie, forceRefresh = true)
                },
            )

            // Premium two-pane detail layout (Netflix style): a tall poster panel anchored
            // on the left with the action buttons and metadata stacked under it, and the
            // magnet list taking the whole right side of the screen. The list now shares the
            // row with the artwork instead of starting below it, so it keeps full height and
            // 6+ rows are visible without scrolling on a 540dp TV.
            // The artwork is always mounted (never conditionally) so constrained TVs never
            // pay for a repeated bitmap decode on focus changes.
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Left pane: big poster card with actions underneath.
                Column(
                    modifier = Modifier
                        .width(232.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF23252B), Color(0xFF121317)),
                            ),
                        )
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // The poster absorbs the leftover vertical space of the pane, so it is
                    // as large as possible on every TV height without ever pushing the
                    // buttons or the metadata out of the card.
                    //
                    // No stroke is drawn around the artwork: `Modifier.border` on top of
                    // `Modifier.clip` forced Compose to allocate an extra offscreen
                    // layer for the rounded corners on every redraw of this pane. The
                    // pane background already separates the poster from the backdrop, so
                    // the stroke was pure cost.
                    AsyncImage(
                        model = posterRequest,
                        contentDescription = movie.title,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop,
                    )

                    Spacer(Modifier.height(12.dp))

                    NetflixHeaderButton(
                        text = if (isFavorite) s.removeFromMyList else s.addToMyList,
                        icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        active = isFavorite,
                        onClick = { viewModel.toggleFavorite(movie) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (!movie.ytTrailerCode.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        NetflixHeaderButton(
                            text = s.trailer,
                            icon = Icons.Default.PlayCircleOutline,
                            modifier = Modifier.fillMaxWidth(),
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

                    Spacer(Modifier.height(10.dp))

                    // Metadata only; the film title now lives in the top bar above.
                    val metaLine = listOfNotNull(
                        movie.genres?.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                        movie.rating?.takeIf { it > 0.0 }?.let { "⭐ ${formatRating(it)}" },
                    ).joinToString("    ")
                    if (metaLine.isNotBlank()) {
                        Text(
                            text = metaLine,
                            color = Grey,
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Right pane: quality/sort filters and the magnet link list.
                Column(modifier = Modifier.weight(1f)) {
            TorrentResultsColumnOptimized(
                    unifiedTorrents = unifiedTorrents,
                    isLoading = isMultiSourceLoading,
                    sourceErrors = aggUiState.sourceErrors,
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
                    totalResultsCount = aggUiState.totalResultsCount,
                    searchStats = aggUiState.searchStats,
                    onResetFilters = {
                        aggregatedViewModel.setTorrentLanguageFilter(false)
                        aggregatedViewModel.filterByQuality("All")
                    },
                    onRetry = { aggregatedViewModel.searchMovies(movie, forceRefresh = true) },
                    onAddMagnetAndPlay = onAddMagnetAndPlayStable,
                    onOpenBrowser = onOpenBrowser,
            )
                }
            }
        }
    }
}

// ────────────────────────────── COMPONENTS ────────────────────────────────────

/**
 * Slim top bar: navigation on the left, refresh on the right, film identity centred in
 * between. Keeping the title here (instead of in the hero card below) removes a whole text
 * row from the hero and lets the results list start higher on a 540dp TV.
 */
@Composable
private fun DetailsTopBar(
    s: AppStrings,
    title: String,
    backFocus: FocusRequester,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetflixHeaderButton(
            text = s.back,
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            onClick = onBack,
            modifier = Modifier.focusRequester(backFocus),
        )

        // The weighted centre box keeps the title visually centred between the two buttons
        // regardless of their differing widths. It never overlaps them: it shrinks and
        // ellipsises instead.
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
                lineHeight = 23.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
        }

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
    sourceErrors: Map<String, String>,
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
    /** Total rows across every source, before the active quality/language filters. */
    totalResultsCount: Int,
    /** Per-search source/timing breakdown, `null` until the search completes. */
    searchStats: AggregatedTorrentViewModel.SearchStats?,
    /** Clears the quality and language filters; used by the "filters hid everything" state. */
    onResetFilters: () -> Unit,
    onRetry: () -> Unit,
    onAddMagnetAndPlay: (String, String) -> Unit,
    onOpenBrowser: (String) -> Unit,
) {
    Column(modifier = Modifier.weight(1f)) {
        var showSortMenu by remember { mutableStateOf(false) }

        // The per-provider progress chips (SourceLoadingChip) used to be rendered here.
        // They showed raw provider names - "TorrentsCSV", "YTS", "TPB" - as soon as Details
        // opened, above the results list, which is the same provenance the release rows no
        // longer expose. Keeping it made the header noisy on every search and kept the row
        // height unstable while sources resolved at different speeds.
        // `sourceErrors` is still surfaced, but only in the "no sources" empty state, where
        // the failure text is the actionable message rather than decorative chrome.

        Row(
            modifier = Modifier.fillMaxWidth(), // Fără height fix: butonul Sort (~36dp) era tăiat la 30dp
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (unifiedTorrents.isNotEmpty() && qualities.size > 1) {
                val qualityListState = rememberLazyListState()
                LazyRow(
                    state = qualityListState,
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                Spacer(Modifier.width(8.dp))
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
                Spacer(Modifier.width(8.dp))
                Box {
                    // The label list is memoized on its real inputs. The previous code built it
                    // with `sortOptions.map(sortLabel)` on every recomposition, allocating a new
                    // list each time and making `HorizontalSelectionMenu`'s LaunchedEffect key
                    // change on every frame while the menu was open.
                    val sortOptions = remember { AggregatedTorrentViewModel.SortOption.entries.toList() }
                    val sortLabels = remember(s, sortOptions) {
                        sortOptions.map { option ->
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
                        text = "${s.sortBy}: ${sortLabels[sortOptions.indexOf(currentSort)]}",
                        icon = Icons.AutoMirrored.Filled.Sort,
                        onClick = { showSortMenu = true },
                    )
                    HorizontalSelectionMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                        items = sortLabels,
                        selectedItem = sortLabels[sortOptions.indexOf(currentSort)],
                        // Resolved by index rather than by comparing localized labels: two
                        // options can share a label in some translation, and the old
                        // `firstOrNull { sortLabel(it) == label }` then silently selected the
                        // wrong sort order.
                        onItemSelected = { label ->
                            showSortMenu = false
                            sortLabels.indexOf(label)
                                .takeIf { it >= 0 }
                                ?.let(sortOptions::get)
                                ?.let(onSortSelected)
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
        
        Spacer(Modifier.height(8.dp))

        // Result provenance: how many rows the sources returned, how many survived the
        // active filters, and how long the search took. The source counts that used to
        // appear here ("3/8 sources") were removed: the provider count is an
        // implementation detail of `defaultScrapers()` and telling the user how many
        // configured indexes existed meant nothing actionable, while changing with every
        // provider trim. `resultsSummary` was already translated into every supported
        // locale, so this only removed its two source placeholders.
        // Hidden while loading and for the zero-result states, where the empty-state text below
        // already explains the situation and a "0 results" header would only contradict it.
        val stats = searchStats
        if (!isLoading && unifiedTorrents.isNotEmpty() && stats != null) {
            val hiddenByFilter = totalResultsCount - unifiedTorrents.size
            Text(
                text = buildString {
                    append(
                        s.resultsSummary.format(
                            unifiedTorrents.size,
                            stats.durationMs,
                        )
                    )
                    if (hiddenByFilter > 0) append("  ·  +$hiddenByFilter")
                },
                color = Grey,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
        }

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
        } else if (unifiedTorrents.isEmpty() && totalResultsCount > 0) {
            // The sources did answer, but the quality/language filters hid every row. The old
            // code fell through to the generic "no sources found" state here, which blamed the
            // providers for a filter the user had just applied.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = s.noResultsForFilter
                    },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = s.noResultsForFilter,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
                Spacer(Modifier.height(16.dp))
                NetflixHeaderButton(
                    text = s.allFilter,
                    icon = Icons.Default.FilterAlt,
                    modifier = Modifier.focusRequester(retryFocus),
                    // Retry re-runs the same search with the same filters, so it cannot undo
                    // this state; reset the filters first.
                    onClick = { onResetFilters() },
                )
            }
        } else if (unifiedTorrents.isEmpty()) {
            // Genuine zero-result / fallback state. This also absorbs the previous separate
            // `isEmptyResult` and `!isLoading` branches, which were byte-identical.
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
            // Hoisted out of the LazyColumn scope: the LazyListScope lambda is not a
            // @Composable context, so `remember` is not permitted there.
            val firstRowHash = remember(unifiedTorrents) { unifiedTorrents.firstOrNull()?.infoHash }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(5.dp),
                contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp)
            ) {
                items(
                    items = unifiedTorrents,
                    // The infohash already uniquely identifies a row after
                    // aggregation/deduplication, so no string concatenation is
                    // needed. The previous "${source}_${infoHash}" key allocated a
                    // new String for every visible row on every measure/layout pass.
                    key = { it.infoHash },
                    contentType = { "torrent" },
                ) { torrent ->
                    UnifiedTorrentRowOptimized(
                        torrent = torrent,
                        torrserverAddress = torrserverAddress,
                        onAddMagnetAndPlay = onAddMagnetAndPlay,
                        onOpenBrowser = onOpenBrowser,
                        s = s,
                        // Comparing hashes avoids a full data-class equals() per row.
                        modifier = if (torrent.infoHash == firstRowHash) {
                            Modifier.focusRequester(firstFocus)
                        } else {
                            Modifier
                        }
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

    val safeOriginalLink = remember(torrent.originalLink) {
        torrent.originalLink?.let(::validateHttpsUrl)
    }
    // Peers and upload day are parsed by every scraper but were previously discarded by this
    // screen: `peers` was never rendered, and `uploadDate` was only ever used as a sort key.
    // They are the deciding factors on a TV, where picking between several releases of the same
    // film means comparing swarm health and age at a glance.
    val uploadDay = remember(torrent.uploadTimeMillis) { formatUploadDay(torrent.uploadTimeMillis) }
    val torrentDescription = remember(torrent, s) {
        s.torrentRowDescription.format(
            torrent.title,
            torrent.quality,
            torrent.size,
            torrent.seeds,
            torrent.peers,
        )
    }

    // Visual container only: no `onClick`, so this Surface is NOT a focus target. The play action
    // lives on the text block below, which is what keeps the trailing actions reachable by D-pad.
    Surface(
        modifier = modifier
            .fillMaxWidth()
            // `mergeDescendants` is deliberately NOT set: it folded the copy / open-source
            // buttons into the row's own description, so a screen-reader user could never reach
            // them. The row's spoken description is applied to the text block instead (below),
            // leaving the two actions as separate, individually announced buttons.
            .semantics { contentDescription = torrentDescription },
        color = backgroundColor,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (isFocused) 3.dp else 1.dp,
            color = if (isFocused) Color.White else Color.White.copy(alpha = 0.1f)
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(modifier = Modifier.padding(vertical = 3.dp, horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            // The provider badge that used to lead the row is gone: the details screen no
            // longer advertises source provenance (the "x/y sources" summary was removed
            // earlier), so the badge only added per-row noise.
            // The release-language badge stays in the metadata line: it describes the
            // release itself (which dub it carries), not which provider returned it.
            // The description lives on the text block, not on the row, so the trailing action
            // buttons remain individually reachable with a screen reader.
            Column(
                modifier = Modifier
                    .weight(1f)
                    // The play action is the TEXT block, not the whole row.
                    //
                    // Compose's directional focus search only offers D-pad focus to a target that
                    // lies outside the bounds of the currently focused node. While the row itself
                    // was the clickable, the copy / open-source buttons were its children and
                    // therefore inside those bounds, so RIGHT had no destination at all: focus
                    // stayed on the row and the actions could not be reached with a remote. As
                    // siblings of this block they are outside its bounds, where the same
                    // directional search finds them normally.
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { onAddMagnetAndPlay(torrent.magnetUrl, torrent.title) },
                    )
                    .semantics(mergeDescendants = true) { contentDescription = torrentDescription },
            ) {
                Text(
                    text = torrent.title,
                    color = if (isFocused) Color.Black else Color.White,
                    fontSize = 15.sp,
                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    QualityBadge(torrent.quality, isFocused)
                    Spacer(Modifier.width(12.dp))
                    Text(torrent.size, color = if (isFocused) Color.DarkGray else Grey, fontSize = 12.sp, lineHeight = 14.sp)
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                        contentDescription = s.sortSeeds,
                        tint = if (isFocused) Color.Black else Color(0xFF4CAF50),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(torrent.seeds.toString(), color = if (isFocused) Color.Black else Color(0xFF4CAF50), fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.ExtraBold)
                    // Leechers, previously parsed but never shown. A release with 0 seeders and a
                    // healthy peer count is still playable on many clients, so this is the info
                    // that tells apart a dead release from a merely unpopular one.
                    if (torrent.peers > 0) {
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.TrendingDown,
                            contentDescription = s.peers,
                            tint = if (isFocused) Color.Black else Color(0xFFFF9800),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            torrent.peers.toString(),
                            color = if (isFocused) Color.Black else Color(0xFFFF9800),
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    torrent.seasonEpisode?.let {
                        Spacer(Modifier.width(10.dp))
                        Text(it, color = if (isFocused) Color.Black else SoftBlue, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    // Release age, and the audio language of the release. Both were already
                    // known to the model; only the quality badge was reaching the screen.
                    uploadDay?.let {
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = s.uploadedOn,
                            tint = if (isFocused) Color.DarkGray else Grey,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(it, color = if (isFocused) Color.DarkGray else Grey, fontSize = 12.sp, lineHeight = 14.sp)
                    }
                    torrent.language?.takeIf { it.isNotBlank() }?.let { language ->
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = language,
                            tint = if (isFocused) Color.DarkGray else Grey,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(language.uppercase(), color = if (isFocused) Color.DarkGray else Grey, fontSize = 12.sp, lineHeight = 14.sp)
                    }
                }
            }
            
            Spacer(Modifier.width(8.dp))

            // ── Trailing actions ──────────────────────────────────────────────
            // Each action is its own focus target with its OWN focus indicator, and sits outside
            // the text block's bounds so D-pad-right can actually land on it.
            //
            // They used to be `Box(Modifier.clickable)` whose icon tint was driven by the *row's*
            // `isFocused`, so the row turned dark while the button itself never highlighted and
            // the selected action could not be identified. `TorrentRowAction` owns its
            // `MutableInteractionSource` and inverts the tint behind a white disc instead.
            TorrentRowAction(
                icon = Icons.Default.ContentCopy,
                actionContentDescription = s.copyMagnetLink,
                onClick = {
                    clipboardScope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("magnet", torrent.magnetUrl))
                        )
                    }
                    Toast.makeText(context, s.magnetLinkCopied, Toast.LENGTH_SHORT).show()
                },
            )

            safeOriginalLink?.let { sourceUrl ->
                TorrentRowAction(
                    icon = Icons.Default.OpenInBrowser,
                    actionContentDescription = s.openSourcePage,
                    onClick = { onOpenBrowser(sourceUrl) },
                )
            }

            // Playback affordance. It is intentionally NOT a separate focus target: the whole row
            // is already the play action, and an extra stop between "row" and "play" would make
            // the most common action need an extra keypress. It stays as a state indicator.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = if (isFocused) Color.Black.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .semantics { contentDescription = s.playTorrent },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (torrserverAddress.isBlank()) Icons.Default.OpenInBrowser else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = if (isFocused) Color.Black else SoftBlue,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

/**
 * Trailing action of a magnet row.
 *
 * Each action carries its own [MutableInteractionSource] so it reports its own focus state
 * instead of borrowing the row's. The unfocused state is a bare icon; the focused state is a
 * solid white disc with a dark glyph, which stays legible at 3 metres and costs one background
 * draw — no animation, so no per-row interpolator on constrained TV hardware.
 */
@Composable
private fun TorrentRowAction(
    icon: ImageVector,
    actionContentDescription: String,
    onClick: () -> Unit,
) {
    val actionInteractionSource = remember { MutableInteractionSource() }
    val isActionFocused by actionInteractionSource.collectIsFocusedAsState()

    Surface(
        onClick = onClick,
        interactionSource = actionInteractionSource,
        modifier = Modifier.size(40.dp),
        shape = RoundedCornerShape(20.dp),
        color = if (isActionFocused) Color.White else Color.Transparent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = actionContentDescription,
                tint = if (isActionFocused) Color.Black else SoftBlue,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

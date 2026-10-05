package io.github.flavyu22.movietorrentsearchtv.viewmodel

import io.github.flavyu22.movietorrentsearchtv.data.scraper.normalizeQualityLabel
import io.github.flavyu22.movietorrentsearchtv.model.UnifiedTorrent
import io.github.flavyu22.movietorrentsearchtv.util.TorrentMatcher
import io.github.flavyu22.movietorrentsearchtv.viewmodel.AggregatedTorrentViewModel.SortOption
import java.util.Locale

/**
 * Outcome of one filter/sort pass over the accumulated source rows.
 *
 * [totalCount] is deliberately counted *after* the language filter but *before* the
 * quality filter. The details screen renders its "no results match the active filters"
 * state from `visible.isEmpty() && totalCount > 0`, so this is what distinguishes
 * "the sources answered but the filters hid everything" from a genuine zero-result
 * search. It also drives the "+N hidden by filters" count in the summary line.
 */
internal data class FilteredTorrentResults(
    val visible: List<UnifiedTorrent>,
    val totalCount: Int,
    val qualities: List<String>
)

/**
 * Pure filtering/sorting of the aggregated torrent rows.
 *
 * This used to live as a private method on [AggregatedTorrentViewModel], which made it
 * untestable: the method needs an `Application` to read the app-language preference,
 * and the project has no Robolectric dependency. Splitting it out lets JVM unit tests
 * reproduce the filter combinations directly — including the "filters hid every row"
 * state, which is very hard to trigger with live provider data because
 * `TorrentMatcher.matchesAppLanguage` only rejects titles carrying an explicit
 * foreign-language tag.
 */
internal object TorrentResultFilter {

    /**
     * @param appLanguage resolved app language code ("EN", "RO", …). Only consulted when
     *   [languageFilter] is `true`; pass a blank string to skip the language pass.
     */
    fun filterAndSort(
        baseResults: List<UnifiedTorrent>,
        targetQuality: String,
        sort: SortOption,
        languageFilter: Boolean,
        appLanguage: String
    ): FilteredTorrentResults {
        // Memoize the language lookup: the whole sort/filter pass runs per emission, and
        // each call would otherwise re-read the preference. One read per pass is
        // sufficient because the pass itself is atomic with respect to a single emission.
        val language = if (languageFilter) appLanguage else ""
        val languageFiltered = if (languageFilter) {
            baseResults.filter { TorrentMatcher.matchesAppLanguage(it.title, language) }
        } else {
            baseResults
        }
        val filtered = if (targetQuality == "All") languageFiltered else {
            languageFiltered.filter { normalizeQualityLabel(it.quality).equals(targetQuality, ignoreCase = true) }
        }
        val seriesMode = languageFiltered.any(UnifiedTorrent::isSeries)
        val sorted = when (sort) {
            SortOption.QUALITY_DESC -> if (seriesMode) {
                filtered.sortedWith(
                    compareBy<UnifiedTorrent> { it.seasonEpisode ?: "ZZZ" }
                        .thenByDescending { it.qualityScore }
                        .thenByDescending { it.seeds }
                )
            } else filtered.sorted()

            SortOption.SEEDS_DESC -> filtered.sortedWith(
                compareBy<UnifiedTorrent> { if (seriesMode) it.seasonEpisode ?: "ZZZ" else "" }
                    .thenByDescending { it.seeds }
                    .thenByDescending { it.qualityScore }
            )

            SortOption.SIZE_ASC -> filtered.sortedWith(
                compareBy<UnifiedTorrent> { it.sizeInBytes.takeIf { size -> size > 0L } ?: Long.MAX_VALUE }
                    .thenByDescending { it.qualityScore }
                    .thenByDescending { it.seeds }
            )

            SortOption.DATE_DESC -> filtered.sortedWith(
                compareByDescending<UnifiedTorrent> { it.uploadTimeMillis }
                    .thenByDescending { it.qualityScore }
                    .thenByDescending { it.seeds }
            )

            SortOption.SOURCE -> filtered.sortedWith(
                compareBy<UnifiedTorrent> { it.source.lowercase(Locale.ROOT) }
                    .thenByDescending { it.qualityScore }
                    .thenByDescending { it.seeds }
            )
        }
        val qualities = languageFiltered.asSequence()
            .map { normalizeQualityLabel(it.quality) }
            .distinct()
            .sortedByDescending(UnifiedTorrent::qualityScoreFor)
            .toList()
        return FilteredTorrentResults(sorted, languageFiltered.size, listOf("All") + qualities)
    }
}
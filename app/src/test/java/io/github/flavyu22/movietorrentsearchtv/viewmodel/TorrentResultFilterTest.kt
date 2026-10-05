package io.github.flavyu22.movietorrentsearchtv.viewmodel

import io.github.flavyu22.movietorrentsearchtv.model.UnifiedTorrent
import io.github.flavyu22.movietorrentsearchtv.viewmodel.AggregatedTorrentViewModel.SortOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure details-screen filter/sort pass.
 *
 * The main reason these exist: the "no results match the active filters" state
 * (`visible.isEmpty() && totalCount > 0`) is essentially impossible to trigger on a
 * device with live provider data. `TorrentMatcher.matchesAppLanguage` only rejects
 * titles carrying an explicit foreign-language tag, and `filterByQuality` refuses any
 * quality that is not in the currently available list, so the quality filter alone can
 * never empty a non-empty result set. Driving quality first and *then* the language
 * filter is the only reachable path — reproduced deterministically here.
 */
class TorrentResultFilterTest {

    private fun torrent(
        hash: String,
        title: String,
        quality: String,
        seeds: Int = 10,
        size: String = "1.5 GB",
        uploadDate: String? = "2026-10-01",
        source: String = "TPB",
        seasonEpisode: String? = null,
        isSeries: Boolean = false
    ) = UnifiedTorrent(
        infoHash = hash,
        title = title,
        magnetUrl = "magnet:?xt=urn:btih:$hash",
        size = size,
        seeds = seeds,
        peers = 1,
        quality = quality,
        source = source,
        uploadDate = uploadDate,
        seasonEpisode = seasonEpisode,
        isSeries = isSeries
    )

    private fun filter(
        rows: List<UnifiedTorrent>,
        quality: String = "All",
        sort: SortOption = SortOption.DATE_DESC,
        languageFilter: Boolean = false,
        appLanguage: String = "EN"
    ) = TorrentResultFilter.filterAndSort(rows, quality, sort, languageFilter, appLanguage)

    @Test
    fun allQualityKeepsEverythingAndCountsThem() {
        val rows = listOf(
            torrent("a", "Movie.2026.1080p.WEB-DL", "1080p"),
            torrent("b", "Movie.2026.720p.WEB-DL", "720p")
        )
        val result = filter(rows)
        assertEquals(2, result.visible.size)
        assertEquals(2, result.totalCount)
        assertEquals(listOf("All", "1080p", "720p"), result.qualities)
    }

    @Test
    fun qualityFilterNarrowsVisibleRowsButTotalStaysUnfiltered() {
        val rows = listOf(
            torrent("a", "Movie.2026.1080p.WEB-DL", "1080p"),
            torrent("b", "Movie.2026.720p.WEB-DL", "720p"),
            torrent("c", "Movie.2026.1080p.AMZN", "1080p")
        )
        val result = filter(rows, quality = "720p")
        assertEquals(listOf("b"), result.visible.map { it.infoHash })
        // totalCount is measured before the quality filter, so the UI can report the
        // "+N hidden by filters" count.
        assertEquals(3, result.totalCount)
    }

    @Test
    fun qualitiesAreOfferedForEveryRowSoAFilterCanNeverBeChosenEmpty() {
        // This is the invariant that makes the empty-filter state unreachable via the
        // quality chips alone: every offered quality has at least one matching row.
        val rows = listOf(
            torrent("a", "Movie.2026.2160p", "2160p"),
            torrent("b", "Movie.2026.1080p", "1080p x265")
        )
        val result = filter(rows)
        for (quality in result.qualities) {
            if (quality == "All") continue
            assertTrue(
                "quality '$quality' is offered but matches nothing",
                filter(rows, quality = quality).visible.isNotEmpty()
            )
        }
    }

    @Test
    fun languageFilterDropsForeignTaggedRowsButKeepsUntaggedAndMatching() {
        val rows = listOf(
            torrent("a", "Film.2026.ITA.1080p.BluRay", "1080p"),
            torrent("b", "Movie.2026.1080p.x264-GROUP", "1080p"),
            torrent("c", "Movie.2026.MULTi.1080p", "1080p")
        )
        val result = filter(rows, languageFilter = true, appLanguage = "EN")
        assertEquals(listOf("b", "c"), result.visible.map { it.infoHash }.sorted())
        assertEquals(2, result.totalCount)
    }

    @Test
    fun qualityThenLanguageFilterProducesTheExhaustedState() {
        // The only reachable path to "filters hid every row": pick a quality while the
        // language filter is off, then switch the language filter on so that every row
        // of that quality turns out to be tagged in a different language.
        val rows = listOf(
            torrent("a", "Film.2026.ITA.720p.BluRay", "720p"),
            torrent("b", "Film.2026.ITA.720p.DVDRip", "720p"),
            torrent("c", "Movie.2026.1080p.x264-GROUP", "1080p")
        )

        // Step 1 — quality "720p" with the language filter off: two rows match.
        val step1 = filter(rows, quality = "720p")
        assertEquals(2, step1.visible.size)
        assertTrue(step1.qualities.contains("720p"))

        // Step 2 — turning the language filter on empties the 720p slice.
        val step2 = filter(rows, quality = "720p", languageFilter = true, appLanguage = "EN")
        assertTrue(step2.visible.isEmpty())
        // …while the sources clearly answered, which is what drives the reset state.
        assertEquals(1, step2.totalCount)
        assertTrue(
            "details screen needs visible.isEmpty() && totalCount > 0",
            step2.visible.isEmpty() && step2.totalCount > 0
        )
        // The stale selection can no longer be offered, so no chip shows as selected;
        // that is harmless because the chips are not rendered on the reset state.
        assertFalse(step2.qualities.contains("720p"))
        assertEquals(listOf("All", "1080p"), step2.qualities)
    }

    @Test
    fun resettingToAllQualityClearsTheExhaustedState() {
        // Mirrors `onResetFilters`: clear the language filter and go back to "All".
        val rows = listOf(
            torrent("a", "Film.2026.ITA.720p.BluRay", "720p"),
            torrent("b", "Film.2026.ITA.720p.DVDRip", "720p"),
            torrent("c", "Movie.2026.1080p.x264-GROUP", "1080p")
        )
        val exhausted = filter(rows, quality = "720p", languageFilter = true, appLanguage = "EN")
        assertTrue(exhausted.visible.isEmpty())

        val reset = filter(rows, quality = "All", languageFilter = false)
        assertEquals(3, reset.visible.size)
        assertEquals(3, reset.totalCount)
        // "All" must always be offered, otherwise the reset button would be a no-op:
        // `filterByQuality` rejects any value missing from availableQualities.
        assertTrue(reset.qualities.contains("All"))
    }

    @Test
    fun seedsSortOrdersDescending() {
        val rows = listOf(
            torrent("a", "Movie.2026.1080p.A", "1080p", seeds = 5),
            torrent("b", "Movie.2026.1080p.B", "1080p", seeds = 500),
            torrent("c", "Movie.2026.1080p.C", "1080p", seeds = 50)
        )
        val result = filter(rows, sort = SortOption.SEEDS_DESC)
        assertEquals(listOf("b", "c", "a"), result.visible.map { it.infoHash })
    }

    @Test
    fun sizeSortKeepsUnknownSizesLast() {
        val rows = listOf(
            torrent("a", "Movie.2026.1080p.A", "1080p", size = "4 GB"),
            torrent("b", "Movie.2026.1080p.B", "1080p", size = "700 MB"),
            torrent("c", "Movie.2026.1080p.C", "1080p", size = "")
        )
        val result = filter(rows, sort = SortOption.SIZE_ASC)
        assertEquals(listOf("b", "a", "c"), result.visible.map { it.infoHash })
    }

    @Test
    fun seriesModeGroupsSeasonsBeforeRankingQuality() {
        val rows = listOf(
            torrent("s2", "Show.S02E01.1080p", "1080p", seasonEpisode = "S02E01", isSeries = true),
            torrent("s1", "Show.S01E01.2160p", "2160p", seasonEpisode = "S01E01", isSeries = true),
            torrent("s1b", "Show.S01E02.720p", "720p", seasonEpisode = "S01E02", isSeries = true)
        )
        val result = filter(rows, sort = SortOption.QUALITY_DESC)
        assertEquals(listOf("s1", "s1b", "s2"), result.visible.map { it.infoHash })
    }
}
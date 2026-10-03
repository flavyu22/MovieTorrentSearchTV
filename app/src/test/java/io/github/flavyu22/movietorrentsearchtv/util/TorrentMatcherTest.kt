package io.github.flavyu22.movietorrentsearchtv.util

import io.github.flavyu22.movietorrentsearchtv.model.Movie
import io.github.flavyu22.movietorrentsearchtv.model.UnifiedTorrent
import java.util.Calendar
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentMatcherTest {
    @Test
    fun acceptsShortNumericAndMarkerLikeRealTitles() {
        assertRelevant("It", 2017, "It.2017.1080p.BluRay")
        assertRelevant("1917", 2019, "1917.2019.2160p.WEB-DL")
        assertRelevant("2001: A Space Odyssey", 1968, "2001.A.Space.Odyssey.1968.1080p")
        assertRelevant("Eclipse", 2010, "Eclipse.2010.720p.BluRay")
        assertRelevant(
            title = "Trailer Park Boys",
            year = 2001,
            torrentTitle = "Trailer.Park.Boys.S03E04.1080p.WEBRip",
            isSeries = true
        )
    }

    @Test
    fun acceptsAReleaseYearWhenCatalogueYearIsUnknown() {
        assertRelevant("Arrival", null, "Arrival.2016.1080p.BluRay")
    }

    @Test
    fun rejectsPoorReleaseMarkersEvenForTheCurrentYear() {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        assertFalse(
            TorrentMatcher.isTorrentRelevant(
                torrent("Fresh.$currentYear.Trailer.1080p"),
                movie("Fresh", currentYear)
            )
        )
    }

    @Test
    fun rejectsConflictingYearsAndSequels() {
        assertFalse(
            TorrentMatcher.isTorrentRelevant(
                torrent("It.Chapter.Two.2019.1080p"),
                movie("It", 2017)
            )
        )
        assertFalse(
            TorrentMatcher.isTorrentRelevant(
                torrent("Arrival.1995.1080p"),
                movie("Arrival", 2016)
            )
        )
    }

    @Test
    fun cacheKeyIncludesMutableTorrentMetadata() {
        val target = movie("It", 2017)
        assertFalse(TorrentMatcher.isTorrentRelevant(torrent("Wrong.Movie.2017"), target))
        assertTrue(TorrentMatcher.isTorrentRelevant(torrent("It.2017.1080p"), target))
    }

    @Test
    fun acceptsRealEstrenosTorrentReleaseTitles() {
        // Titles exactly as published by estrenostorrent.org (verified live).
        assertTrue(
            TorrentMatcher.isTorrentRelevant(
                torrent(
                    "Dune [BluRay 1080p][AC3 5.1 Castellano][www.atomixHQ.ART]",
                    source = "EstrenosTorrent"
                ),
                movie("Dune", 2021)
            )
        )
        assertTrue(
            TorrentMatcher.isTorrentRelevant(
                torrent(
                    "Dune.2021.2160p.WEB-DL.Castellano.Multi",
                    source = "EstrenosTorrent"
                ),
                movie("Dune", 2021)
            )
        )
    }

    @Test
    fun acceptsLocalizedTitlesFromTmdb() {
        // A Spanish indexer may only expose the localized title. The magnet provider
        // result must be accepted when it matches the TMDB localized (Spanish) name,
        // even though the display/original titles are English.
        val localized = movie("The Lord of the Rings", 2001)
            .copy(localizedTitle = "El Señor de los Anillos")
        val englishOnly = movie("The Lord of the Rings", 2001)

        val spanishTorrent = torrent(
            "El.Senor.de.los.Anillos.2001.1080p.BluRay.Castellano",
            source = "EstrenosTorrent",
        )

        assertTrue(
            "Spanish release should match the TMDB localized title",
            TorrentMatcher.isTorrentRelevant(spanishTorrent, localized),
        )
        assertFalse(
            "English-only variant must not accept an unrelated Spanish title",
            TorrentMatcher.isTorrentRelevant(spanishTorrent, englishOnly),
        )
    }

    @Test
    fun acceptsTitlesWithRealisticLongReleaseTagsFromAggregators() {
        // Real aggregate-style rows: title + year + long release group in brackets/parentheses.
        val movie = movie("Dune", 2021)
        assertTrue(
            TorrentMatcher.isTorrentRelevant(
                torrent("Dune.2021.1080p.BluRay.x265-GalaxyRG265", source = "TPB"),
                movie,
            )
        )
        assertTrue(
            TorrentMatcher.isTorrentRelevant(
                torrent("Dune Part Two (2024) [1080p] [WEBRip] [YTS.MX]", source = "Solid"),
                movie("Dune Part Two", 2024),
            )
        )
        // The same hash may be re-listed by several aggregators with slightly different
        // titles; the added release-tag words must not push it over the extras budget.
        assertTrue(
            TorrentMatcher.isTorrentRelevant(
                torrent("Dune (2021) 1080p BrRip x264 - 1.85GB - YIFY", source = "TPB"),
                movie,
            )
        )
    }

    @Test
    fun acceptsEpisodeTitlesWithSeriesTags() {
        val series = movie("Severance", 2022, isSeries = true)
        assertTrue(
            "Series episode with SxxExx should be relevant",
            TorrentMatcher.isTorrentRelevant(
                torrent("Severance.S01E01.1080p.WEB-DL.x265-EVOLVE", source = "Eztv"),
                series,
            )
        )
        assertTrue(
            "Series title without year must still match when the torrent is episode-labelled",
            TorrentMatcher.isTorrentRelevant(
                torrent("Severance.S02E06.2160p.ATVP.WEB-DL.DDP5.1.DV.HDR.HEVC", source = "TPB"),
                series,
            )
        )
    }

    @Test
    fun retainsTorrentsWhenExactCatalogueYearIsAbsent() {
        // TPB rows frequently omit the release year entirely (e.g. "Inception.1080p.BluRay").
        // With no year on either side, relevance must not be lost to year mismatch.
        val movie = movie("Inception", 2010)
        assertTrue(
            "Missing year in the torrent title should not remove it",
            TorrentMatcher.isTorrentRelevant(
                torrent("Inception.1080p.BluRay.x264", source = "TPB"),
                movie,
            )
        )
    }

    @Test
    fun acceptsRealLiveAggregatorRowsForInception() {
        // Rows captured verbatim from live apibay.org / solidtorrents.eu responses.
        // Before the release-noise handling these were dropped from detail pages,
        // even though the providers clearly list them.
        val inception = movie("Inception", 2010)
        assertTrue(
            TorrentMatcher.isTorrentRelevant(
                torrent("Inception (2010) 1080p BrRip x264 - 1.85GB - YIFY", source = "TPB"),
                inception,
            )
        )
        assertTrue(
            TorrentMatcher.isTorrentRelevant(
                torrent("Inception.2010.1080p.BluRay.DDP5.1.x265.10bit-GalaxyRG265", source = "Solid"),
                inception,
            )
        )
    }

    private fun assertRelevant(
        title: String,
        year: Int?,
        torrentTitle: String,
        isSeries: Boolean = false
    ) {
        assertTrue(
            "$title should match $torrentTitle",
            TorrentMatcher.isTorrentRelevant(
                torrent(torrentTitle, isSeries),
                movie(title, year, isSeries)
            )
        )
    }

    private fun movie(title: String, year: Int?, isSeries: Boolean = false) = Movie(
        id = title.hashCode().toLong(),
        title = title,
        originalTitle = title,
        imdbCode = null,
        posterPath = null,
        mediumPosterPath = null,
        year = year,
        ytTrailerCode = null,
        genres = null,
        torrents = null,
        isSeries = isSeries
    )

    private fun torrent(title: String, isSeries: Boolean = false, source: String = "Test") = UnifiedTorrent(
        infoHash = HASH,
        title = title,
        magnetUrl = "magnet:?xt=urn:btih:$HASH&dn=release",
        size = "1 GB",
        seeds = 10,
        peers = 1,
        quality = "1080p",
        source = source,
        isSeries = isSeries
    )

    private companion object {
        const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}

package io.github.flavyu22.movietorrentsearchtv.data.scraper

import org.junit.Assert.assertEquals
import org.junit.Test

class ScraperUtilsTest {
    @Test
    fun parsesEpisodesAndSeasonPacksInBothWordOrders() {
        assertEquals("S02E07", extractSeasonEpisode("Show Season 2 Episode 7"))
        assertEquals("S03E04", extractSeasonEpisode("Show 3x04"))
        assertEquals("S02E00", extractSeasonEpisode("Show Complete Season 2 1080p"))
        assertEquals("S03E00", extractSeasonEpisode("Show Season 3 Complete"))
        assertEquals("S04E00", extractSeasonEpisode("Show Pack S4"))
    }

    @Test
    fun parsesOnlyRealXtParametersWithoutGloballyDecodingTheMagnet() {
        val realHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val injectedHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val magnet = "magnet:?dn=Movie%26xt%3Durn%3Abtih%3A$injectedHash" +
            "&xt=urn%3Abtih%3A$realHash"

        assertEquals(realHash, extractHashFromMagnet(magnet))
        assertEquals(
            "",
            extractHashFromMagnet(
                "magnet:?dn=Movie%26xt%3Durn%3Abtih%3A$injectedHash"
            )
        )
        assertEquals(
            "",
            extractHashFromMagnet(
                "magnet:?XT=urn:btih:$injectedHash&xt=urn:btih:$realHash"
            )
        )
    }

    @Test
    fun supportsBase32HashesAndPreservesSourceTrackers() {
        val base32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val magnet = "magnet:?xt=urn:btih:$base32&tr=https%3A%2F%2Ftracker.example%2Fa"

        assertEquals(base32.lowercase(), extractHashFromMagnet(magnet))
        assertEquals(magnet, enhanceMagnet(magnet))
    }

    /**
     * Release names glue the resolution to the next token without a separator, so a trailing
     * `\b` never fires: in "2160pHD" the character after "2160p" is "H", which is still a word
     * character, and in "1080p_nnm-club" the following "_" is a word character too. Every such
     * title therefore fell through to "Unknown" even though the resolution is right there.
     * The boundary must allow the usual release separators (".", "_", "-", space) to follow.
     */
    @Test
    fun qualityIsDetectedWhenTheResolutionIsGluedToTheNextToken() {
        assertEquals("2160p", extractQuality("Spider-Man Brand New Day 2160pHD (2026) MeGusta EZTV.exe"))
        assertEquals("1080p", extractQuality("Spider-Man Brand New Day 2026 1080p_nnm-club.mkv"))
        assertEquals("1080p", extractQuality("Movie.2026.1080p.WEB-DL.x264"))
        assertEquals("2160p", extractQuality("Movie.2026.2160p.UHD.BluRay"))
        assertEquals("720p", extractQuality("Show.S01E02.720p.HDTV"))
        assertEquals("1080p", extractQuality("Show S01E02 1080p WEB-DL"))
    }

    /**
     * A resolution must still win over the bare "HD"/"SD" tokens, in either order, because the
     * patterns are evaluated from the highest resolution down.
     */
    @Test
    fun explicitResolutionBeatsTheGenericHdToken() {
        assertEquals("2160p", extractQuality("2160p HD"))
        assertEquals("1080p", extractQuality("1080p HD"))
        assertEquals("720p", extractQuality("HD 720p"))
        assertEquals("480p", extractQuality("SD 480p"))
        // The ambiguous bare form stays 720p, as before.
        assertEquals("720p", extractQuality("Movie.HD.x264"))
    }
}

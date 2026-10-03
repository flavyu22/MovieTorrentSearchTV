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
}

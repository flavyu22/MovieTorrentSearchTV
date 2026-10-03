package io.github.flavyu22.movietorrentsearchtv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the torrent title release-language detection used by the language filter. */
class TorrentLanguageTest {

    @Test
    fun detectsExplicitLanguageTags() {
        assertEquals("RO", TorrentMatcher.detectTitleLanguage("Inception.2010.1080p.BluRay.DUB.RO-xGroup"))
        assertEquals("RO", TorrentMatcher.detectTitleLanguage("Film tare 2023 Romana 720p"))
        assertEquals("RO", TorrentMatcher.detectTitleLanguage("Aparitii Dublat In Romana WEB-DL"))
        assertEquals("IT", TorrentMatcher.detectTitleLanguage("Movie.2022.1080p.BluRay.ITA.ENG-xGroup"))
        assertEquals("ES", TorrentMatcher.detectTitleLanguage("Pelicula.2021.Spanish.720p.WEBRip"))
        assertEquals("ES", TorrentMatcher.detectTitleLanguage("Pelicula.Castellano.1080p"))
        assertEquals("FR", TorrentMatcher.detectTitleLanguage("Film.2020.French.BDRip"))
        assertEquals("FR", TorrentMatcher.detectTitleLanguage("Film.2020.TRUEFRENCH.1080p"))
        assertEquals("DE", TorrentMatcher.detectTitleLanguage("Movie.2019.GERMAN.1080p.BluRay"))
        assertEquals("RU", TorrentMatcher.detectTitleLanguage("Film.2018.Rus.720p"))
        assertEquals("EN", TorrentMatcher.detectTitleLanguage("Movie.2020.1080p.ENG.WEB-DL"))
    }

    @Test
    fun untaggedAndMultiLanguageReleasesHaveNoLanguage() {
        assertNull(TorrentMatcher.detectTitleLanguage("Inception.2010.1080p.BluRay.x264-GalaxyRG265"))
        assertNull(TorrentMatcher.detectTitleLanguage(null))
        assertNull(TorrentMatcher.detectTitleLanguage(""))
        assertNull(TorrentMatcher.detectTitleLanguage("Movie.2020.MULTi.1080p.BluRay"))
        assertNull(TorrentMatcher.detectTitleLanguage("Movie.2020.DUAL.AUDIO.720p"))
    }

    @Test
    fun filterKeepsUntaggedMultiAndMatchingLanguage() {
        // Untagged international releases always pass.
        assertTrue(TorrentMatcher.matchesAppLanguage("Inception.2010.1080p.BluRay.x264", "RO"))
        // Multi-language releases always pass.
        assertTrue(TorrentMatcher.matchesAppLanguage("Movie.2020.MULTi.1080p", "ES"))
        // Matching language passes (case-insensitive).
        assertTrue(TorrentMatcher.matchesAppLanguage("Film.2022.Romana.720p", "RO"))
        assertTrue(TorrentMatcher.matchesAppLanguage("Movie.2022.ITA.1080p", "IT"))
        // Recognizably different language is rejected.
        assertFalse(TorrentMatcher.matchesAppLanguage("Movie.2022.ITA.1080p", "RO"))
        assertFalse(TorrentMatcher.matchesAppLanguage("Film.2020.TRUEFRENCH.1080p", "EN"))
        // Blank app language disables the filter.
        assertTrue(TorrentMatcher.matchesAppLanguage("Movie.2022.ITA.1080p", ""))
    }
}

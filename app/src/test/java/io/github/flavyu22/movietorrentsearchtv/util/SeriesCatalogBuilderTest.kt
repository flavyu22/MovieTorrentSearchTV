package io.github.flavyu22.movietorrentsearchtv.util

import io.github.flavyu22.movietorrentsearchtv.model.PirateBayTorrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesCatalogBuilderTest {

    private fun row(
        name: String?,
        category: String? = "205",
        seeders: String = "1",
        imdb: String? = null,
    ) = PirateBayTorrent(
        id = "1",
        name = name,
        info_hash = "AABBCCDDEEFF00112233445566778899AABBCCDD",
        seeders = seeders,
        leechers = "0",
        size = "100",
        category = category,
        imdb = imdb,
    )

    // Live rows captured from apibay.org q.php?q=category:205
    @Test
    fun `derives show titles from real release names`() {
        assertEquals(
            "Alone",
            SeriesCatalogBuilder.deriveShowTitle("Alone S13E11 Fire and Famine 480p WEB-DL x264-RMTeam"),
        )
        assertEquals(
            "The Young and the Restless",
            SeriesCatalogBuilder.deriveShowTitle("The Young and the Restless S53E226 XviD-AFG"),
        )
        assertEquals(
            "TNA iMPACT",
            SeriesCatalogBuilder.deriveShowTitle("TNA iMPACT 2026 08 27 AMC 480p WEBRip H264-Star [TJET]"),
        )
        assertEquals(
            "Show Name",
            SeriesCatalogBuilder.deriveShowTitle("Show.Name.2019.S01E01.720p.WEB.h264-GROUP"),
        )
    }

    @Test
    fun `groups episodes per show keeping imdb and ordering by seeders`() {
        val movies = SeriesCatalogBuilder.buildCatalogue(
            listOf(
                row("Alone S13E11 Fire and Famine 480p WEB-DL x264-RMTeam", seeders = "6", imdb = "tt5615840"),
                row("Alone S13E10 1080p x265-GalaxyRG", seeders = "18"),
                row("The Young and the Restless S53E226 XviD-AFG", seeders = "6", imdb = "tt0069658"),
            ),
            query = "",
        )
        assertEquals(2, movies.size)
        val alone = movies.first()
        assertEquals("Alone", alone.title)
        assertEquals("tt5615840", alone.imdbCode)
        assertTrue("fallback shows must use the negative TPB namespace", alone.id < 0)
        assertTrue(alone.isSeries)
        assertEquals(null, alone.posterPath)
        // The best-seeded show comes first, the rest alphabetical.
        assertEquals("The Young and the Restless", movies.last().title)
        assertEquals("tt0069658", movies.last().imdbCode)
    }

    @Test
    fun `filters by query and tv category`() {
        val movies = SeriesCatalogBuilder.buildCatalogue(
            listOf(
                row("Alone S13E11 720p x264"),
                row("Inception 2010 1080p BluRay x264", category = "201"),
                row("Some Movie 2020 720p WEBRip x264", category = "207"),
            ),
            query = "alone",
        )
        assertEquals(1, movies.size)
        assertEquals("Alone", movies.single().title)
    }

    @Test
    fun `tolerates the no-results row and unusable names`() {
        val noResultsRow = PirateBayTorrent(
            id = "no results",
            name = null,
            info_hash = null,
            seeders = "0",
            leechers = "0",
            size = "0",
            category = null,
            imdb = null,
        )
        val movies = SeriesCatalogBuilder.buildCatalogue(
            listOf(noResultsRow, row(null), row(""), row("x264")),
            query = "",
        )
        assertTrue(movies.isEmpty())
    }
}
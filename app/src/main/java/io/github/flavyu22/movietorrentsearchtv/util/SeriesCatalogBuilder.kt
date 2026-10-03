package io.github.flavyu22.movietorrentsearchtv.util

import io.github.flavyu22.movietorrentsearchtv.model.Movie
import io.github.flavyu22.movietorrentsearchtv.model.PirateBayTorrent
import java.util.Locale

/**
 * Builds a minimal series catalogue from The Pirate Bay's TV-show category rows.
 *
 * Used as the fallback series catalogue when TMDB is not configured: instead of a
 * blank error screen, the grid lists the shows behind the most recent TV releases
 * (rows are grouped per show; the IMDb id, when attached by the index, is kept so
 * the details screen can query providers directly by IMDb). This source offers no
 * posters or ratings, so the cards render their built-in title placeholder.
 */
object SeriesCatalogBuilder {

    /** The Pirate Bay category code for TV shows. */
    internal const val TV_CATEGORY = "205"

    // Titles are derived from release names: everything from the first season/episode
    // marker, resolution, quality tag or date onwards belongs to the release, not
    // to the show itself.
    private val SEASON_EPISODE = Regex("""(?i)\bs\d{1,3}\s?e\d{1,4}\b""")
    private val EPISODE_CROSS = Regex("""(?i)\b\d{1,3}x\d{1,4}\b""")
    private val RESOLUTION = Regex("""(?i)\b(?:480p|576p|720p|1080p|1440p|2160p|4k)\b""")
    private val QUALITY_TAG = Regex(
        """(?i)\b(?:web-?dl|webrip|web|pdtv|hdtv|bluray|blu-ray|bdrip|brrip|dvdrip|dvd|xvid|x264|x265|h264|h265|hevc|av1|aac|10bit|8bit|hdr)\b""",
    )
    private val YEAR = Regex("""\b(?:19|20)\d{2}\b""")
    private val SEPARATORS = Regex("""[._]+""")
    private val WHITESPACE = Regex("""\s+""")
    private val TRAILING_SEPARATORS = Regex("""[\s._\-]+$""")
    private val BRACKET_TAG = Regex("""\s*\[[^\]]*]\s*$""")
    private val IMDB = Regex("""tt\d{5,12}""", RegexOption.IGNORE_CASE)

    private const val MIN_TITLE_CHARS = 3
    private const val MAX_RAW_TITLE_CHARS = 60
    /** Negative namespace distinct from TMDB ids (-tmdbId) and positive YTS ids. */
    private const val FALLBACK_ID_BASE = 2_000_000_000L

    fun buildCatalogue(
        rows: List<PirateBayTorrent>,
        query: String,
        pageSize: Int = 49,
    ): List<Movie> {
        val shows = LinkedHashMap<String, Show>()
        for (row in rows) {
            val name = row.name?.trim().takeUnless { it.isNullOrEmpty() } ?: continue
            if (row.category != TV_CATEGORY) continue
            val title = deriveShowTitle(name) ?: continue
            if (query.isNotBlank() && !title.contains(query.trim(), ignoreCase = true)) continue

            val key = title.lowercase(Locale.ROOT)
            val seeds = row.seeders?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val existing = shows[key]
            if (existing == null) {
                shows[key] = Show(title, seeds, showImdb(row))
            } else {
                if (seeds > existing.seeders) existing.seeders = seeds
                if (existing.imdbCode == null) existing.imdbCode = showImdb(row)
            }
        }
        return shows.values
            .sortedWith(
                compareByDescending<Show> { it.seeders }
                    .thenBy { it.title.lowercase(Locale.ROOT) },
            )
            .take(pageSize)
            .map { show ->
                Movie(
                    id = -(FALLBACK_ID_BASE + stableHash(show.title)),
                    title = show.title,
                    originalTitle = show.title,
                    imdbCode = show.imdbCode,
                    posterPath = null,
                    mediumPosterPath = null,
                    largePosterPath = null,
                    year = null,
                    ytTrailerCode = null,
                    genres = null,
                    rating = null,
                    torrents = null,
                    isSeries = true,
                    tmdbId = null,
                    url = null,
                )
            }
    }

    /** Extracts the show title from a release name, or null when unusable. */
    internal fun deriveShowTitle(releaseName: String): String? {
        val cleaned = releaseName
            .replace(SEPARATORS, " ")
            .replace(WHITESPACE, " ")
            .trim()
        if (cleaned.isEmpty()) return null
        val cuts = listOfNotNull(
            SEASON_EPISODE.find(cleaned)?.range?.first,
            EPISODE_CROSS.find(cleaned)?.range?.first,
            RESOLUTION.find(cleaned)?.range?.first,
            QUALITY_TAG.find(cleaned)?.range?.first,
            YEAR.find(cleaned)?.range?.first,
        ).sorted()
        val base = when {
            // Release-only names (e.g. "x264") have cut markers but nothing left
            // of the marker worth keeping; reject them instead of inventing titles.
            cuts.isNotEmpty() -> cuts.firstOrNull { it >= MIN_TITLE_CHARS }
                ?.let { cleaned.substring(0, it) }
                ?: return null
            cleaned.length <= MAX_RAW_TITLE_CHARS -> cleaned
            else -> return null
        }
        val title = base
            .replace(BRACKET_TAG, "")
            .replace(TRAILING_SEPARATORS, "")
            .trim()
        return title.ifEmpty { null }
    }

    private fun showImdb(row: PirateBayTorrent): String? =
        IMDB.find(row.imdb.orEmpty())?.value?.lowercase(Locale.ROOT)

    private fun stableHash(value: String): Long =
        value.lowercase(Locale.ROOT).hashCode().toLong() and 0x7FFFFFFFL

    private class Show(
        val title: String,
        var seeders: Int,
        var imdbCode: String?,
    )
}
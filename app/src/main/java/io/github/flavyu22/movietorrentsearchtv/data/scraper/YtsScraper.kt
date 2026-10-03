package io.github.flavyu22.movietorrentsearchtv.data.scraper

import io.github.flavyu22.movietorrentsearchtv.model.TorrentSource
import io.github.flavyu22.movietorrentsearchtv.model.UnifiedTorrent
import io.github.flavyu22.movietorrentsearchtv.config.RemoteHosts
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

class YtsScraper(private val client: OkHttpClient) : TorrentScraper {
    override val source = TorrentSource(
        name = "YTS",
        url = DOMAINS.first(),
        reliability = 0.98f,
        supportedCategories = listOf(TorrentSource.Category.MOVIES)
    )

    override suspend fun search(
        query: String,
        category: TorrentSource.Category
    ): List<UnifiedTorrent> {
        if (category !in setOf(TorrentSource.Category.MOVIES, TorrentSource.Category.ALL)) {
            return emptyList()
        }
        return searchDomains(query.trim())
    }

    override suspend fun searchByImdb(imdbId: String): List<UnifiedTorrent> {
        val digits = imdbId.trim().removePrefix("tt")
        require(digits.isNotEmpty() && digits.all(Char::isDigit)) { "Invalid IMDb id" }
        return searchDomains("tt$digits")
    }

    private suspend fun searchDomains(query: String): List<UnifiedTorrent> {
        if (query.isBlank()) return emptyList()
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")
        return firstSuccessfulMirror(DOMAINS.map { domain ->
            suspend { fetchYtsJson("$domain/api/v2/list_movies.json?query_term=$encodedQuery&limit=50") }
        })
    }

    private suspend fun fetchYtsJson(url: String): List<UnifiedTorrent> {
        val body = client.awaitBody(Request.Builder().url(url).get().build())
        return parseYtsResponse(body)
    }

    internal fun parseYtsResponse(body: String): List<UnifiedTorrent> {
        val root = JSONObject(body)
        if (!root.optString("status").equals("ok", ignoreCase = true)) {
            throw IOException(root.optString("status_message", "YTS rejected the request"))
        }
        val data = root.optJSONObject("data") ?: throw IOException("YTS response is missing data")
        val movies = data.optJSONArray("movies")
        if (movies == null) {
            if (data.optInt("movie_count", 0) == 0) return emptyList()
            throw IOException("YTS response is missing movies")
        }
        val results = ArrayList<UnifiedTorrent>(
            minOf(movies.length(), MAX_SCRAPER_RESULTS) * 2,
        )

        movieLoop@ for (movieIndex in 0 until minOf(movies.length(), MAX_SCRAPER_RESULTS)) {
            val movie = movies.optJSONObject(movieIndex) ?: continue
            val movieTitle = movie.optString("title")
                .trim()
                .take(MAX_TORRENT_TITLE_CHARS)
                .takeIf(String::isNotEmpty)
                ?: continue
            val movieYear = movie.optInt("year").takeIf { it in 1870..2200 }
            val originalLink = trustedMovieUrl(movie.optString("url"))
            val torrents = movie.optJSONArray("torrents") ?: continue

            for (torrentIndex in 0 until minOf(torrents.length(), MAX_TORRENTS_PER_MOVIE)) {
                if (results.size >= MAX_SCRAPER_RESULTS) break@movieLoop
                val torrent = torrents.optJSONObject(torrentIndex) ?: continue
                val hash = torrent.optString("hash")
                    .trim()
                    .take(64)
                    .lowercase(Locale.ROOT)
                if (!INFO_HASH.matches(hash)) continue

                val rawQuality = listOf(
                    torrent.optString("quality").take(MAX_SOURCE_VALUE_CHARS),
                    torrent.optString("type").take(MAX_SOURCE_VALUE_CHARS),
                    torrent.optString("video_codec").take(MAX_SOURCE_VALUE_CHARS),
                ).joinToString(" ")
                val quality = extractQuality(rawQuality)
                val displayTitle = buildString {
                    append(movieTitle)
                    movieYear?.let { append(" (").append(it).append(')') }
                    if (quality != "Unknown") append(' ').append(quality)
                }.take(MAX_TORRENT_TITLE_CHARS)
                val encodedName = URLEncoder.encode(displayTitle, Charsets.UTF_8.name())
                val uploadDate = torrent.optString("date_uploaded")
                    .take(MAX_SOURCE_VALUE_CHARS)
                    .ifBlank {
                    torrent.optLong("date_uploaded_unix", 0L).takeIf { it > 0L }?.toString().orEmpty()
                }.ifBlank {
                    movie.optString("date_uploaded").take(MAX_SOURCE_VALUE_CHARS)
                }.ifBlank { null }

                results += UnifiedTorrent(
                    infoHash = hash,
                    title = displayTitle,
                    magnetUrl = enhanceMagnet("magnet:?xt=urn:btih:$hash&dn=$encodedName"),
                    size = torrent.optString("size", "N/A").take(64),
                    seeds = torrent.optInt("seeds", 0).coerceAtLeast(0),
                    peers = torrent.optInt("peers", 0).coerceAtLeast(0),
                    quality = quality,
                    source = source.name,
                    language = detectLanguage(movieTitle, source.name),
                    uploadDate = uploadDate,
                    originalLink = originalLink
                )
            }
        }
        return results
    }

    private fun trustedMovieUrl(value: String): String? = value
        .trim()
        .takeIf { it.length <= MAX_SOURCE_URL_CHARS }
        ?.toHttpUrlOrNull()
        ?.takeIf { url ->
            url.isHttps && TRUSTED_LINK_HOSTS.any { host ->
                url.host == host || url.host.endsWith(".$host")
            }
        }
        ?.toString()

    private companion object {
        val DOMAINS = RemoteHosts.ytsMirrorBaseUrls
        val TRUSTED_LINK_HOSTS = DOMAINS.mapNotNull { it.toHttpUrlOrNull()?.host }.toSet()
        val INFO_HASH = Regex("[a-f0-9]{40}", RegexOption.IGNORE_CASE)
    }
}

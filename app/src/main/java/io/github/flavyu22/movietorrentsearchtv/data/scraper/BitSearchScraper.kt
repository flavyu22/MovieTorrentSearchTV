package io.github.flavyu22.movietorrentsearchtv.data.scraper

import io.github.flavyu22.movietorrentsearchtv.model.TorrentSource
import io.github.flavyu22.movietorrentsearchtv.model.UnifiedTorrent
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/** Public API: https://bitsearch.eu/api (requests are subject to its public quota). */
class BitSearchScraper(private val client: OkHttpClient) : TorrentScraper {
    override val source = TorrentSource("BitSearch", "https://bitsearch.eu", 0.85f,
        listOf(TorrentSource.Category.MOVIES, TorrentSource.Category.TV_SHOWS, TorrentSource.Category.ALL))

    override suspend fun search(query: String, category: TorrentSource.Category): List<UnifiedTorrent> {
        if (query.isBlank() || category !in source.supportedCategories) return emptyList()
        val url = "${source.url}/api/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim()).addQueryParameter("limit", "20")
        when (category) {
            TorrentSource.Category.MOVIES -> url.addQueryParameter("category", "2")
            TorrentSource.Category.TV_SHOWS -> url.addQueryParameter("category", "3")
            else -> Unit
        }
        return parseResponse(client.awaitBody(Request.Builder().url(url.build()).build(), MAX_SCRAPER_JSON_BYTES))
    }

    private companion object {
        // Hoisted: parseResponse() validates one hash per result row, so a per-call
        // Regex compilation would run once per row of every search response.
        val HASH = Regex("[a-f0-9]{40}")
    }

    // This index searches titles; it does not document IMDb lookup.
    override suspend fun searchByImdb(imdbId: String): List<UnifiedTorrent> = emptyList()

    internal fun parseResponse(body: String): List<UnifiedTorrent> {
        val root = JSONObject(body)
        if (!root.optBoolean("success", false)) throw IOException("BitSearch rejected the request")
        val rows = root.optJSONArray("results") ?: throw IOException("BitSearch response is missing results")
        return (0 until minOf(rows.length(), MAX_SCRAPER_RESULTS)).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val hash = row.optString("infohash").trim().lowercase(Locale.ROOT)
            val title = row.optString("title").trim().take(MAX_TORRENT_TITLE_CHARS)
            if (!HASH.matches(hash) || title.isBlank()) return@mapNotNull null
            val episode = extractSeasonEpisode(title)
            UnifiedTorrent(infoHash = hash, title = title,
                magnetUrl = enhanceMagnet("magnet:?xt=urn:btih:$hash&dn=${URLEncoder.encode(title, "UTF-8")}"),
                size = normalizeSize(row.optLong("size", 0).coerceAtLeast(0).toString()),
                seeds = row.optInt("seeders", 0).coerceAtLeast(0),
                peers = row.optInt("leechers", 0).coerceAtLeast(0),
                quality = extractQuality(title), source = source.name,
                language = detectLanguage(title, source.name), uploadDate = row.optString("createdAt"),
                seasonEpisode = episode, isSeries = episode != null)
        }
    }
}

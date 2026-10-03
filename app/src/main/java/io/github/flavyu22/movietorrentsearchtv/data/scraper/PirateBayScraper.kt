package io.github.flavyu22.movietorrentsearchtv.data.scraper

import io.github.flavyu22.movietorrentsearchtv.model.TorrentSource
import io.github.flavyu22.movietorrentsearchtv.config.RemoteHosts
import io.github.flavyu22.movietorrentsearchtv.model.UnifiedTorrent
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.Locale

class PirateBayScraper(private val client: OkHttpClient) : TorrentScraper {
    override val source = TorrentSource(
        name = "TPB",
        url = RemoteHosts.PIRATE_BAY_SITE_URL,
        reliability = 0.90f,
        supportedCategories = listOf(TorrentSource.Category.ALL)
    )

    override suspend fun search(
        query: String,
        category: TorrentSource.Category
    ): List<UnifiedTorrent> {
        if (query.isBlank()) return emptyList()
        val categoryCode = when (category) {
            TorrentSource.Category.MOVIES -> "201"
            TorrentSource.Category.TV_SHOWS -> "205"
            else -> "0"
        }
        val encodedQuery = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        return fetchJson("${RemoteHosts.PIRATE_BAY_API_URL}/q.php?q=$encodedQuery&cat=$categoryCode")
    }

    override suspend fun searchByImdb(imdbId: String): List<UnifiedTorrent> {
        val digits = imdbId.trim().removePrefix("tt")
        require(digits.isNotEmpty() && digits.all(Char::isDigit)) { "Invalid IMDb id" }
        return fetchJson("${RemoteHosts.PIRATE_BAY_API_URL}/q.php?q=tt$digits&cat=0")
    }

    private suspend fun fetchJson(url: String): List<UnifiedTorrent> {
        val body = client.awaitBody(Request.Builder().url(url).get().build())
        return parsePirateBayResponse(body)
    }

    internal fun parsePirateBayResponse(body: String): List<UnifiedTorrent> {
        val json = JSONArray(body)
        val resultCount = minOf(json.length(), MAX_SCRAPER_RESULTS)
        val results = ArrayList<UnifiedTorrent>(resultCount)

        for (index in 0 until resultCount) {
            val item = json.optJSONObject(index) ?: continue
            val infoHash = item.optString("info_hash")
                .trim()
                .take(64)
                .lowercase(Locale.ROOT)
            if (!INFO_HASH.matches(infoHash) || infoHash == EMPTY_HASH) continue
            val name = item.optString("name")
                .trim()
                .take(MAX_TORRENT_TITLE_CHARS)
                .takeIf(String::isNotEmpty)
                ?: continue
            val encodedName = URLEncoder.encode(name, Charsets.UTF_8.name())
            val added = item.optString("added")
                .trim()
                .take(MAX_SOURCE_VALUE_CHARS)
                .takeIf(String::isNotEmpty)

            results += UnifiedTorrent(
                infoHash = infoHash,
                title = name,
                magnetUrl = enhanceMagnet("magnet:?xt=urn:btih:$infoHash&dn=$encodedName"),
                size = normalizeSize(item.optString("size").take(64)),
                seeds = item.optString("seeders").take(16).toIntOrNull()?.coerceAtLeast(0) ?: 0,
                peers = item.optString("leechers").take(16).toIntOrNull()?.coerceAtLeast(0) ?: 0,
                quality = extractQuality(name),
                source = source.name,
                language = detectLanguage(name, source.name),
                uploadDate = added,
                seasonEpisode = extractSeasonEpisode(name)
            )
        }
        return results
    }

    private companion object {
        const val EMPTY_HASH = "0000000000000000000000000000000000000000"
        val INFO_HASH = Regex("[a-f0-9]{40}", RegexOption.IGNORE_CASE)
    }
}

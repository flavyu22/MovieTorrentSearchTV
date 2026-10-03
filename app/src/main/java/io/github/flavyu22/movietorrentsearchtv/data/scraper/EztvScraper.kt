package io.github.flavyu22.movietorrentsearchtv.data.scraper

import io.github.flavyu22.movietorrentsearchtv.model.TorrentSource
import io.github.flavyu22.movietorrentsearchtv.config.RemoteHosts
import io.github.flavyu22.movietorrentsearchtv.model.UnifiedTorrent
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** EZTV's official API supports exact series lookup by IMDb id. */
class EztvScraper(private val client: OkHttpClient) : TorrentScraper {
    override val source = TorrentSource(
        name = "EZTV",
        url = RemoteHosts.EZTV_SITE_URL,
        reliability = 0.95f,
        supportedCategories = listOf(TorrentSource.Category.TV_SHOWS)
    )

    override suspend fun search(
        query: String,
        category: TorrentSource.Category
    ): List<UnifiedTorrent> {
        if (category !in setOf(TorrentSource.Category.TV_SHOWS, TorrentSource.Category.ALL)) {
            return emptyList()
        }
        // The official endpoint has no title-search parameter. Do not request the
        // global latest feed and pretend it is a result for the user's query.
        val imdbId = IMDB_IN_QUERY.find(query)?.value ?: return emptyList()
        return searchByImdb(imdbId)
    }

    override suspend fun searchByImdb(imdbId: String): List<UnifiedTorrent> {
        val digits = imdbId.trim().removePrefix("tt")
        require(digits.isNotEmpty() && digits.all(Char::isDigit)) { "Invalid IMDb id" }
        val request = Request.Builder()
            .url("${RemoteHosts.EZTV_API_URL}?imdb_id=$digits&limit=$RESULT_LIMIT&page=1")
            .get()
            .build()
        return parseEztvResponse(client.awaitBody(request))
    }

    internal fun parseEztvResponse(body: String): List<UnifiedTorrent> {
        val root = JSONObject(body)
        val torrents = root.optJSONArray("torrents")
        val advertisedCount = root.optInt("torrents_count", 0)
        if (torrents == null) {
            if (root.has("torrents_count") && advertisedCount == 0) return emptyList()
            throw IOException("EZTV response is missing torrents")
        }

        val resultCount = minOf(torrents.length(), MAX_SCRAPER_RESULTS)
        val results = ArrayList<UnifiedTorrent>(resultCount)
        for (index in 0 until resultCount) {
            val item = torrents.optJSONObject(index) ?: continue
            val title = item.optString("title").trim().ifEmpty {
                item.optString("filename").trim()
            }.take(MAX_TORRENT_TITLE_CHARS)
            if (title.isEmpty()) continue
            val magnet = item.optString("magnet_url")
                .trim()
                .takeIf { it.length <= MAX_MAGNET_CHARS }
                ?: continue
            val infoHash = extractHashFromMagnet(magnet)
            if (infoHash.isEmpty()) continue
            val encodedTitle = URLEncoder.encode(title, Charsets.UTF_8.name())
                .replace("+", "%20")
            val canonicalMagnet = "magnet:?xt=urn:btih:$infoHash&dn=$encodedTitle"

            val rawSize = item.opt("size_bytes")
            val sizeBytes = ((rawSize as? Number)?.toLong()
                ?: (rawSize as? String)?.toLongOrNull())
                ?.takeIf { it > 0L }
            val uploadDate = item.optLong("date_released_unix", 0L)
                .takeIf { it > 0L }
                ?.toString()
            val sourcePage = firstTrustedHttpsUrl(
                item.optString("episode_url"),
                item.optString("torrent_url")
            )

            results += UnifiedTorrent(
                infoHash = infoHash,
                title = title,
                magnetUrl = canonicalMagnet,
                size = sizeBytes?.let { normalizeSize("$it B") } ?: "N/A",
                seeds = item.optInt("seeds", 0).coerceAtLeast(0),
                peers = item.optInt("peers", 0).coerceAtLeast(0),
                quality = extractQuality(title),
                source = source.name,
                language = detectLanguage(title, source.name) ?: "EN",
                uploadDate = uploadDate,
                seasonEpisode = extractSeasonEpisode(title),
                originalLink = sourcePage,
                isSeries = true
            )
        }
        return results
    }

    private fun firstTrustedHttpsUrl(vararg values: String): String? = values.firstNotNullOfOrNull { value ->
        value.trim().takeIf { it.length <= MAX_SOURCE_URL_CHARS }?.toHttpUrlOrNull()?.takeIf { url ->
            url.isHttps && RemoteHosts.eztvLinkHostSuffixes.any { host ->
                url.host == host || url.host.endsWith(".$host")
            }
        }?.toString()
    }

    private companion object {
        const val RESULT_LIMIT = 100
        val IMDB_IN_QUERY = Regex("""(?i)\btt\d{5,10}\b""")
    }
}

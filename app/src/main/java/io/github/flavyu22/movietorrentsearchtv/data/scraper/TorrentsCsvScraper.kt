package io.github.flavyu22.movietorrentsearchtv.data.scraper

import io.github.flavyu22.movietorrentsearchtv.model.TorrentSource
import io.github.flavyu22.movietorrentsearchtv.model.UnifiedTorrent
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/**
 * TorrentsCSV (torrents-csv.com) — a crowdsourced, keyless JSON API aggregating
 * dozens of trackers. Live behaviour (verified 2026-08-28):
 *
 *   GET https://torrents-csv.com/service/search?q={query}&size={n}&page=1
 *     → { "torrents": [ { infohash, name, size_bytes, created_unix, seeders,
 *                          leechers, completed, scraped_date, id } ], "next": … }
 *
 * The endpoint accepts plain-title queries, so both the title flow and (as a
 * full-text fallback) the IMDb-id flow go through it. It is extremely stable:
 * a static Datalog-backed service with no CSRF, cookies or rate limits.
 */
class TorrentsCsvScraper(private val client: OkHttpClient) : TorrentScraper {
    override val source = TorrentSource(
        name = "TorrentsCSV",
        url = SITE_URL,
        reliability = 0.94f,
        supportedCategories = listOf(TorrentSource.Category.ALL)
    )

    override suspend fun search(
        query: String,
        category: TorrentSource.Category
    ): List<UnifiedTorrent> {
        val cleaned = sanitizeQuery(query)
        if (cleaned.isBlank()) return emptyList()
        return fetchSearch(cleaned)
    }

    override suspend fun searchByImdb(imdbId: String): List<UnifiedTorrent> {
        val cleaned = imdbId.trim().removePrefix("tt")
        require(cleaned.isNotEmpty() && cleaned.all(Char::isDigit)) { "Invalid IMDb id" }
        return fetchSearch("tt$cleaned")
    }

    private suspend fun fetchSearch(query: String): List<UnifiedTorrent> {
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("$SEARCH_URL?q=$encodedQuery&size=$RESULT_SIZE&page=1")
            .get()
            .build()
        val body = try {
            client.awaitBody(request, maxBytes = MAX_SCRAPER_JSON_BYTES)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw IOException("${source.name}: ${failure.userMessage()}", failure)
        }
        return try {
            parseTorrentsCsvResponse(body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw IOException("${source.name}: invalid response", failure)
        }
    }

    internal fun parseTorrentsCsvResponse(body: String): List<UnifiedTorrent> {
        val root = JSONObject(body)
        val torrents = root.optJSONArray("torrents")
            ?: throw IOException("TorrentsCSV response is missing torrents")
        val resultCount = minOf(torrents.length(), MAX_SCRAPER_RESULTS)
        val results = ArrayList<UnifiedTorrent>(resultCount)

        for (index in 0 until resultCount) {
            val item = torrents.optJSONObject(index) ?: continue
            val title = item.optString("name").trim().take(MAX_TORRENT_TITLE_CHARS)
            if (title.isEmpty()) continue
            val infoHash = item.optString("infohash")
                .trim()
                .lowercase(Locale.ROOT)
                .take(64)
            if (!INFO_HASH.matches(infoHash)) continue

            val sizeBytes = item.optLong("size_bytes", 0L).coerceAtLeast(0L)
            val createdUnix = item.optLong("created_unix", 0L).takeIf { it > 0L }?.toString()
            val encodedTitle = URLEncoder.encode(title, Charsets.UTF_8.name())
                .replace("+", "%20")

            results += UnifiedTorrent(
                infoHash = infoHash,
                title = title,
                magnetUrl = enhanceMagnet("magnet:?xt=urn:btih:$infoHash&dn=$encodedTitle"),
                size = normalizeSize(sizeBytes.toString()),
                seeds = item.optInt("seeders", 0).coerceAtLeast(0),
                peers = item.optInt("leechers", 0).coerceAtLeast(0),
                quality = extractQuality(title),
                source = source.name,
                language = detectLanguage(title, source.name),
                uploadDate = createdUnix,
                seasonEpisode = extractSeasonEpisode(title),
                originalLink = null,
                isSeries = extractSeasonEpisode(title) != null
            )
        }
        return results
    }

    /**
     * Local query cleanup: the aggregation layer sends catalogue-style queries
     * ("Dune 2021", "tt1234567") and the full-text index matches them as-is.
     * Collapses whitespace and strips wrapping brackets; IMDb ids are kept
     * because torrents-csv indexes release names containing them.
     */
    private fun sanitizeQuery(raw: String): String = raw
        .replace(WHITESPACE, " ")
        .trim()

    private fun Exception.userMessage(): String = when (this) {
        is java.net.SocketTimeoutException -> "timed out"
        is java.net.UnknownHostException -> "host unreachable"
        is javax.net.ssl.SSLException -> "secure connection failed"
        is HttpStatusException -> "HTTP ${statusCode}"
        is IOException -> message ?: "network error"
        else -> "network error"
    }

    private companion object {
        const val SITE_URL = "https://torrents-csv.com"
        const val SEARCH_URL = "$SITE_URL/service/search"
        const val RESULT_SIZE = 60
        val INFO_HASH = Regex("[a-f0-9]{40}")
        val WHITESPACE = Regex("\\s+")
    }
}

package io.github.flavyu22.movietorrentsearchtv.data.scraper

import io.github.flavyu22.movietorrentsearchtv.model.TorrentSource
import io.github.flavyu22.movietorrentsearchtv.model.UnifiedTorrent
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/**
 * Rutracker mirror (rutor.info) — a Russian-language torrent index exposing a keyless
 * public JSON endpoint. It is the highest-volume Cyrillic index reachable without
 * authentication, so it gives the app a genuinely Russian-language magnet source
 * alongside the existing Latin-script providers.
 *
 *   GET https://rutor.info/search/{query}/0/0/0
 *     → { "status": "ok" | "error", "total": …,
 *         "results": [ { id, name, torrent_id, size, seeders, leechers,
 *                        type, date, url, verified } ] }
 *
 * Only the JSON API is consumed. The remote `url` (detail page) is surfaced exclusively
 * when it is a same-host HTTPS absolute URL, and the magnet is always rebuilt locally
 * from the validated `torrent_id`, so a hostile payload can never supply an
 * attacker-chosen link.
 */
class RutorScraper(private val client: OkHttpClient) : TorrentScraper {
    override val source = TorrentSource(
        name = "Rutor",
        url = SITE_URL,
        reliability = 0.8f,
        supportedCategories = listOf(
            TorrentSource.Category.MOVIES,
            TorrentSource.Category.TV_SHOWS,
            TorrentSource.Category.ALL,
        ),
    )

    override suspend fun search(
        query: String,
        category: TorrentSource.Category,
    ): List<UnifiedTorrent> {
        if (category !in source.supportedCategories) return emptyList()
        return fetchSearch(query)
    }

    override suspend fun searchByImdb(imdbId: String): List<UnifiedTorrent> {
        // No documented IMDb lookup on this index. Release names here do carry "tt…"
        // identifiers, so the full-text title path is the honest fallback.
        return fetchSearch(imdbId)
    }

    private suspend fun fetchSearch(rawQuery: String): List<UnifiedTorrent> {
        val cleaned = sanitizeQuery(rawQuery)
        if (cleaned.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(cleaned, Charsets.UTF_8.name()).replace("+", "%20")
        val request = Request.Builder().url("$SEARCH_BASE/$encoded/0/0/0").get().build()
        val body = client.awaitBody(request, maxBytes = MAX_SCRAPER_JSON_BYTES)
        return parseRutorResponse(body)
    }

    internal fun parseRutorResponse(body: String): List<UnifiedTorrent> {
        val root = JSONObject(body)
        val rows = root.optJSONArray("results")
            ?: throw IOException("Rutor response is missing results")
        val count = minOf(rows.length(), MAX_SCRAPER_RESULTS)
        val results = ArrayList<UnifiedTorrent>(count)

        for (index in 0 until count) {
            val row = rows.optJSONObject(index) ?: continue
            val title = row.optString("name").stripTags().ifBlank { continue }
            val hash = row.optString("torrent_id")
                .trim()
                .lowercase(Locale.ROOT)
                .take(MAX_INFO_HASH_CHARS)
            if (!INFO_HASH.matches(hash)) continue

            val encodedName = URLEncoder.encode(title, Charsets.UTF_8.name()).replace("+", "%20")
            val episode = extractSeasonEpisode(title)

            results += UnifiedTorrent(
                infoHash = hash,
                title = title,
                magnetUrl = enhanceMagnet("magnet:?xt=urn:btih:$hash&dn=$encodedName"),
                size = normalizeSize(row.optString("size").take(MAX_SOURCE_VALUE_CHARS)),
                seeds = row.optInt("seeders", 0).coerceAtLeast(0),
                peers = row.optInt("leechers", 0).coerceAtLeast(0),
                quality = extractQuality(title),
                source = source.name,
                language = detectLanguage(title, source.name),
                uploadDate = row.optString("date").take(MAX_SOURCE_VALUE_CHARS).ifBlank { null },
                seasonEpisode = episode,
                originalLink = trustedDetailUrl(row.optString("url")),
                isSeries = episode != null,
            )
        }
        return results
    }

    /** The detail page is only surfaced when it is a real same-host HTTPS URL. */
    private fun trustedDetailUrl(raw: String): String? = raw
        .trim()
        .takeIf { it.length in 1..MAX_SOURCE_URL_CHARS }
        ?.toHttpUrlOrNull()
        ?.takeIf { url -> url.isHttps && url.host == SITE_HOST && url.port == 443 }
        ?.toString()

    /** Collapses whitespace and bounds the query length before it reaches the URL. */
    private fun sanitizeQuery(raw: String): String = raw
        .replace(WHITESPACE, " ")
        .trim()
        .take(MAX_QUERY_CHARS)

    private companion object {
        const val SITE_HOST = "rutor.info"
        const val SITE_URL = "https://$SITE_HOST"
        const val SEARCH_BASE = "$SITE_URL/search"
        const val MAX_QUERY_CHARS = 120
        const val MAX_INFO_HASH_CHARS = 40
        val INFO_HASH = Regex("[a-f0-9]{40}")
        val WHITESPACE = Regex("\\s+")
    }
}
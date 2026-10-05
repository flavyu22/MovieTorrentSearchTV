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
 * Rutracker (rutracker.org) — the reference Russian-language tracker, consuming its
 * documented public JSON API (`/api/v2.0/index.php`).
 *
 *   GET …/api/v2.0/index.php?method=search&apikey=…&query=<text>&fields=…
 *     → { "total_size_lines": …, "response": [ { topic_id, topic_title, size,
 *          seeders, lechers, topic_date_start, dl, imdb_id, category_name } ] }
 *
 * The search API requires a key. Rather than hard-coding or inventing one, [apiKey] is
 * supplied through `local.properties` / the environment (see README, `protectedConfig`).
 * When no key is configured the provider reports a clear, honest failure instead of
 * silently returning zero results — matching how the rest of the scraper layer treats
 * unreachable sources. Magnet links are always rebuilt locally from the validated hash,
 * never taken verbatim from the payload.
 *
 * This provider is what makes Russian-language magnets first-class: Rutracker is the
 * canonical RU index and its UTF-8 JSON carries Cyrillic titles that this app already
 * tokenises through `detectLanguage`.
 */
class RutrackerScraper(
    private val client: OkHttpClient,
    private val apiKey: String,
) : TorrentScraper {
    override val source = TorrentSource(
        name = "Rutracker",
        url = SITE_URL,
        reliability = 0.88f,
        supportedCategories = listOf(
            TorrentSource.Category.MOVIES,
            TorrentSource.Category.TV_SHOWS,
            TorrentSource.Category.ALL,
        ),
    )

    /**
     * Whether a usable API key was supplied. The app uses this to decide whether to register
     * the provider at all: an unconfigured instance throws on every call, so registering it
     * would spend a request and an error-slot per search for a guaranteed failure.
     */
    val isConfigured: Boolean = apiKey.isNotBlank()

    override suspend fun search(
        query: String,
        category: TorrentSource.Category,
    ): List<UnifiedTorrent> {
        if (category !in source.supportedCategories) return emptyList()
        return fetchSearch(query)
    }

    override suspend fun searchByImdb(imdbId: String): List<UnifiedTorrent> = fetchSearch(imdbId)

    private suspend fun fetchSearch(rawQuery: String): List<UnifiedTorrent> {
        val cleaned = sanitizeQuery(rawQuery)
        if (cleaned.isBlank()) return emptyList()
        if (!isConfigured) throw IOException("${source.name}: API key not configured")

        val url = API_URL.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("method", "search")
            ?.addQueryParameter("apikey", apiKey.trim())
            ?.addQueryParameter("query", cleaned)
            ?.addQueryParameter("fields", RESPONSE_FIELDS)
            ?.build()
            ?: throw IOException("${source.name}: malformed API url")

        val body = client.awaitBody(
            Request.Builder().url(url).get().build(),
            maxBytes = MAX_SCRAPER_JSON_BYTES,
        )
        return parseRutrackerResponse(body)
    }
internal fun parseRutrackerResponse(body: String): List<UnifiedTorrent> {
        val root = JSONObject(body)
        // The public API reports transport-level problems inside a 200 JSON envelope.
        val errorCode = root.opt("error_code")?.toString()?.takeIf { it != "null" }
        if (errorCode != null) throw IOException("${source.name}: API error $errorCode")

        val rows = root.optJSONArray("response")
            ?: throw IOException("${source.name}: response is missing 'response' array")
        val count = minOf(rows.length(), MAX_SCRAPER_RESULTS)
        val results = ArrayList<UnifiedTorrent>(count)

        for (index in 0 until count) {
            val row = rows.optJSONObject(index) ?: continue
            val title = row.optString("topic_title").stripTags().ifBlank { continue }
            // 'dl' carries the direct .torrent path; when it is a magnet the hash is read
            // out of it, otherwise it is recovered from the download path digits.
            val download = row.optString("dl").take(MAX_MAGNET_CHARS)
            val hash = extractHashFromMagnet(download).ifBlank { hashFromDownloadPath(download) }
            if (!INFO_HASH.matches(hash)) continue

            val encodedName = URLEncoder.encode(title, Charsets.UTF_8.name()).replace("+", "%20")
            val episode = extractSeasonEpisode(title)
            val sizeBytes = row.optLong("size", 0L).coerceAtLeast(0L)

            results += UnifiedTorrent(
                infoHash = hash,
                title = title,
                magnetUrl = enhanceMagnet("magnet:?xt=urn:btih:$hash&dn=$encodedName"),
                size = normalizeSize(sizeBytes.toString()),
                seeds = row.optInt("seeders", 0).coerceAtLeast(0),
                peers = row.optInt("lechers", 0).coerceAtLeast(0),
                quality = extractQuality(title),
                source = source.name,
                language = detectLanguage(title, source.name),
                uploadDate = row.optLong("topic_date_start", 0L).takeIf { it > 0L }?.toString(),
                seasonEpisode = episode,
                originalLink = topicUrl(row.optString("topic_id")),
                isSeries = episode != null,
            )
        }
        return results
    }

    /**
     * The API exposes a topic download path rather than an info hash, so the hash is
     * recovered from the hexadecimal `dl` value when present. Rows without either are
     * skipped instead of producing an unplayable magnet.
     */
    private fun hashFromDownloadPath(raw: String): String {
        val candidate = raw.substringAfterLast('/').removePrefix("torrent")
        return candidate.trim().lowercase(Locale.ROOT).takeIf { HEX.matches(it) }.orEmpty()
    }

    private fun topicUrl(topicId: String): String? = topicId
        .trim()
        .takeIf { it.length in 1..MAX_SOURCE_VALUE_CHARS && it.all(Char::isDigit) }
        ?.let { "$SITE_URL/forum/viewtopic.php?t=$it" }

    private fun sanitizeQuery(raw: String): String = raw
        .replace(WHITESPACE, " ")
        .trim()
        .take(MAX_QUERY_CHARS)

    private companion object {
        const val SITE_URL = "https://rutracker.org"
        const val API_URL = "$SITE_URL/api/v2.0/index.php"
        const val MAX_QUERY_CHARS = 120
        const val RESPONSE_FIELDS =
            "topic_id,topic_title,size,seeders,lechers,topic_date_start,dl,imdb_id"
        val INFO_HASH = Regex("[a-f0-9]{40}")
        val HEX = Regex("[a-f0-9]{40}")
        val WHITESPACE = Regex("\\s+")
    }
}
package io.github.flavyu22.movietorrentsearchtv.data.scraper

import io.github.flavyu22.movietorrentsearchtv.model.TorrentSource
import io.github.flavyu22.movietorrentsearchtv.model.UnifiedTorrent
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

class SolidTorrentsScraper(private val client: OkHttpClient) : TorrentScraper {
    override val source = TorrentSource(
        name = "Solid",
        url = "https://solidtorrents.eu",
        reliability = 0.92f,
        supportedCategories = listOf(TorrentSource.Category.ALL)
    )

    override suspend fun search(
        query: String,
        category: TorrentSource.Category
    ): List<UnifiedTorrent> {
        if (query.isBlank()) return emptyList()
        val encodedQuery = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("https://solidtorrents.eu/api/v1/search?q=$encodedQuery")
            .get()
            .build()
        // The public API is sometimes slow and answers HTTP 5xx on the first hit.
        // Retrying a finite number of times keeps the provider usable instead of
        // surfacing a misleading per-source failure for a live index.
        val body = try {
            fetchWithRetry(maxAttempts = MAX_ATTEMPTS, delayMs = RETRY_DELAY_MS) {
                client.awaitBody(request)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw IOException(
                "${source.name}: " + failure.userMessage(),
                failure,
            )
        }
        return parseSolidResponse(body)
    }

    /**
     * Bounded retry that treats HTTP 5xx and transport [IOException]s as transient.
     * A 4xx is never retried (it would only fan out the same invalid request).
     * Kept `internal` so unit tests can exercise it without a live network.
     */
    internal suspend fun fetchWithRetry(
        maxAttempts: Int = MAX_ATTEMPTS,
        delayMs: Long = RETRY_DELAY_MS,
        fetch: suspend () -> String,
    ): String {
        var lastFailure: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return fetch()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: HttpStatusException) {
                if (failure.statusCode < 500) throw failure
                lastFailure = failure
            } catch (failure: IOException) {
                lastFailure = failure
            }
            if (attempt < maxAttempts - 1) kotlinx.coroutines.delay(delayMs)
        }
        throw (lastFailure ?: IOException("${source.name}: request failed"))
    }

    private fun Exception.userMessage(): String = when (this) {
        is java.net.SocketTimeoutException -> "timed out"
        is java.net.UnknownHostException -> "host unreachable"
        is javax.net.ssl.SSLException -> "secure connection failed"
        is HttpStatusException -> "HTTP ${statusCode}"
        is IOException -> message ?: "network error"
        else -> "network error"
    }

    override suspend fun searchByImdb(imdbId: String): List<UnifiedTorrent> {
        return search(imdbId, TorrentSource.Category.ALL)
    }

    internal fun parseSolidResponse(body: String): List<UnifiedTorrent> {
        val root = JSONObject(body)
        if (!root.optBoolean("success", true)) throw IOException("Solid rejected the request")
        val resultsArray = root.optJSONArray("results")
            ?: throw IOException("Solid response is missing results")
        val resultCount = minOf(resultsArray.length(), MAX_SCRAPER_RESULTS)
        val results = ArrayList<UnifiedTorrent>(resultCount)

        for (index in 0 until resultCount) {
            val item = resultsArray.optJSONObject(index) ?: continue
            val title = item.optString("title").trim().take(MAX_TORRENT_TITLE_CHARS)
            // The current API exposes the raw info-hash instead of a magnet URI.
            val infoHash = item.optString("infohash")
                .trim()
                .lowercase(Locale.ROOT)
                .take(64)
            if (!INFO_HASH.matches(infoHash)) continue

            val sizeBytes = item.optLong("size", 0L).coerceAtLeast(0L)
            results += UnifiedTorrent(
                infoHash = infoHash,
                title = title,
                magnetUrl = enhanceMagnet("magnet:?xt=urn:btih:$infoHash&dn=${encodeMagnetName(title)}"),
                size = normalizeSize(sizeBytes.toString()),
                seeds = item.optInt("seeders", 0).coerceAtLeast(0),
                peers = item.optInt("leechers", 0).coerceAtLeast(0),
                quality = extractQuality(title),
                source = source.name,
                language = detectLanguage(title, source.name),
                uploadDate = item.optString("updatedAt"),
                seasonEpisode = extractSeasonEpisode(title)
            )
        }
        return results
    }

    private fun encodeMagnetName(title: String): String =
        URLEncoder.encode(title, Charsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        val INFO_HASH = Regex("[a-f0-9]{40}")

        /** Maximum attempts for the transient-5xx retry in [fetchWithRetry]. */
        const val MAX_ATTEMPTS = 3

        /** Delay between retry attempts, small enough to stay inside the search budget. */
        const val RETRY_DELAY_MS = 250L
    }
}

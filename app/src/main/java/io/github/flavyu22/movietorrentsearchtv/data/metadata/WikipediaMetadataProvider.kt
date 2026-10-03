package io.github.flavyu22.movietorrentsearchtv.data.metadata

import android.util.Log
import io.github.flavyu22.movietorrentsearchtv.config.RemoteHosts
import io.github.flavyu22.movietorrentsearchtv.util.TmdbLocale
import io.github.flavyu22.movietorrentsearchtv.util.readUpTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Wikipedia/Wikidata — the second multilingual movie-metadata provider, next to TMDB.
 *
 * Instead of screen-scraping a language edition, the provider asks Wikidata for the
 * entity behind the canonical (English) Wikipedia article of a film and reads back
 * the localized label/sitelink of the requested language:
 *
 *   https://www.wikidata.org/w/api.php?action=wbgetentities&sites=enwiki
 *       &titles=The+Lord+of+the+Rings&props=labels%7Csitelinks
 *       &languages=ro%7Ces%7Cfr%7Cit%7Cde%7Cpt%7Cru%7Cel&format=json&formatversion=2
 *
 * This is an exact cross-language mapping (no fuzzy search), needs **no API key**
 * and answers for every language the app supports. The resulting localized title
 * feeds the very same pipeline as TMDB's localized titles: it is sent as an extra
 * query to the magnet-link providers and used by
 * [io.github.flavyu22.movietorrentsearchtv.util.TorrentMatcher].
 *
 * The provider is strictly best-effort: every failure mode (network, malformed
 * payload, unknown title) yields `null` so callers simply keep the TMDB data.
 */
class WikipediaMetadataProvider(private val client: OkHttpClient) {

    data class LocalizedFilmInfo(
        /** ISO code of the language edition that answered (e.g. "es"). */
        val language: String,
        /** Film title in that language — the localized title. */
        val localizedTitle: String,
        /** Canonical web page of the localized article. */
        val pageUrl: String? = null,
    )

    /**
     * Resolves the localized title of [title] (a canonical English title) for
     * [languageCode] (an app-language code such as "ES"), or `null` when Wikidata
     * has no entity or the target language has no localized name.
     */
    suspend fun fetchLocalizedTitle(
        title: String,
        languageCode: String?,
    ): LocalizedFilmInfo? {
        val query = title.trim()
        if (query.isBlank()) return null
        val language = TmdbLocale.wikipediaLanguageCode(languageCode)

        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                try {
                    client.newCall(entityRequest(query)).execute().use { response ->
                        if (!response.isSuccessful) return@withTimeoutOrNull null
                        // Bounded payload read: reject anything larger than the hard cap
                        // instead of buffering an unbounded body into memory.
                        val payload = response.body.source().use { source ->
                            source.readUpTo(MAX_PAYLOAD_BYTES + 1)
                        }
                        if (payload.size > MAX_PAYLOAD_BYTES) {
                            Log.d(TAG, "Wikidata payload exceeded ${MAX_PAYLOAD_BYTES}B for \"$query\"")
                            return@withTimeoutOrNull null
                        }
                        parseEntityResponse(payload.decodeToString(), language)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.d(TAG, "Wikidata lookup failed for \"$query\": ${failure.message}")
                    null
                }
            }
        }
    }

    internal fun entityRequest(title: String): Request = Request.Builder()
        .url(entityLookupUrl(REFERENCE_SITE, title, SUPPORTED_LANGUAGES))
        .header("User-Agent", USER_AGENT)
        .get()
        .build()

    /**
     * Builds the `wbgetentities` URL. Internal + pure so unit tests can assert the
     * exact wire format without network access.
     */
    internal fun entityLookupUrl(
        referenceSite: String,
        title: String,
        languages: List<String>,
    ): String = buildString {
        append(RemoteHosts.WIKIDATA_API_URL)
        append("?action=wbgetentities")
        append("&sites=").append(encode(referenceSite))
        append("&titles=").append(encode(title))
        append("&props=labels%7Csitelinks")
        append("&languages=").append(encode(languages.joinToString("|")))
        append("&format=json&formatversion=2")
    }

    /**
     * Pure parsing/selection over the `wbgetentities` payload: prefer the human
     * label in [language], fall back to the localized sitelink title, reject
     * entities that have neither. Returns `null` for empty/malformed payloads.
     */
    internal fun parseEntityResponse(json: String, language: String): LocalizedFilmInfo? {
        val entities = runCatching { JSONObject(json) }
            .getOrNull()
            ?.optJSONObject("entities")
            ?: return null
        val ids = entities.names() ?: return null

        for (i in 0 until ids.length()) {
            val entity = entities.optJSONObject(ids.optString(i)) ?: continue
            val label = entity.optJSONObject("labels")
                ?.optJSONObject(language)
                ?.optString("value")
                ?.trim()
                .orEmpty()
            val sitelinkTitle = entity.optJSONObject("sitelinks")
                ?.optJSONObject("${language}wiki")
                ?.optString("title")
                ?.trim()
                .orEmpty()
            val localized = label.ifBlank { sitelinkTitle }
            if (localized.isBlank() || localized.equals("null", ignoreCase = true)) continue
            return LocalizedFilmInfo(
                language = language,
                localizedTitle = localized,
                pageUrl = pageUrl(language, localized),
            )
        }
        return null
    }

    private fun pageUrl(language: String, articleTitle: String): String =
        "https://$language.wikipedia.org/wiki/${encode(articleTitle)}"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val TAG = "WikipediaMeta"
        const val REFERENCE_SITE = "enwiki"

        /** App-supported languages, mirrored from [TmdbLocale]. */
        val SUPPORTED_LANGUAGES = listOf("ro", "es", "fr", "it", "de", "pt", "ru", "el")

        // Wikidata is a public API; identify honestly and stay out of its way.
        const val USER_AGENT = "MovieTorrentSearchTV/2.1 (metadata enrichment)"
        const val REQUEST_TIMEOUT_MS = 6_000L

        /** Hard cap for the wbgetentities JSON payload (real answers are a few KB). */
        const val MAX_PAYLOAD_BYTES = 1_048_576L
    }
}


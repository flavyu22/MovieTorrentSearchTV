package io.github.flavyu22.movietorrentsearchtv.data.scraper

import io.github.flavyu22.movietorrentsearchtv.model.TorrentSource
import io.github.flavyu22.movietorrentsearchtv.model.UnifiedTorrent
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.w3c.dom.Element
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Anime-only RSS index. A listed result does not guarantee active peers. */
class NyaaScraper(private val client: OkHttpClient) : TorrentScraper {
    override val source = TorrentSource("Nyaa", "https://nyaa.si", 0.85f,
        listOf(TorrentSource.Category.MOVIES, TorrentSource.Category.TV_SHOWS, TorrentSource.Category.ALL))

    override suspend fun search(query: String, category: TorrentSource.Category): List<UnifiedTorrent> {
        if (query.isBlank() || category !in source.supportedCategories) return emptyList()
        val url = source.url.toHttpUrl().newBuilder().addQueryParameter("page", "rss")
            .addQueryParameter("q", query.trim()).addQueryParameter("c", "1_0")
            .addQueryParameter("f", "0").build()
        return parseResponse(client.awaitBody(Request.Builder().url(url).build(), MAX_SCRAPER_JSON_BYTES))
    }

    override suspend fun searchByImdb(imdbId: String): List<UnifiedTorrent> = emptyList()

    internal fun parseResponse(body: String): List<UnifiedTorrent> {
        // Reject declarations before invoking the platform XML parser. Avoid optional
        // JAXP feature flags which differ between Android and desktop implementations.
        if (body.length > MAX_SCRAPER_JSON_BYTES || DECLARATION.containsMatchIn(body))
            throw IOException("Unsafe or oversized Nyaa RSS response")
        val document = try {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> throw IOException("External XML entities are forbidden") }
            }.parse(body.byteInputStream(Charsets.UTF_8))
        } catch (failure: Exception) {
            throw IOException("Invalid Nyaa RSS response", failure)
        }
        if (document.documentElement.tagName != "rss" || document.getElementsByTagName("channel").length != 1)
            throw IOException("Nyaa response is not RSS")
        val items = document.getElementsByTagName("item")
        return (0 until minOf(items.length, MAX_SCRAPER_RESULTS)).mapNotNull { index ->
            val item = items.item(index) as? Element ?: return@mapNotNull null
            fun field(name: String) = item.getElementsByTagName(name).item(0)?.textContent.orEmpty().trim()
            fun nyaa(name: String) = item.getElementsByTagNameNS(NS, name).item(0)?.textContent.orEmpty().trim()
            val hash = nyaa("infoHash").lowercase(Locale.ROOT)
            val title = field("title").take(MAX_TORRENT_TITLE_CHARS)
            if (!HASH.matches(hash) || title.isBlank()) return@mapNotNull null
            val episode = extractSeasonEpisode(title)
            UnifiedTorrent(infoHash = hash, title = title,
                magnetUrl = enhanceMagnet("magnet:?xt=urn:btih:$hash&dn=${URLEncoder.encode(title, "UTF-8")}"),
                size = normalizeSize(nyaa("size")), seeds = nyaa("seeders").toIntOrNull()?.coerceAtLeast(0) ?: 0,
                peers = nyaa("leechers").toIntOrNull()?.coerceAtLeast(0) ?: 0,
                quality = extractQuality(title), source = source.name,
                language = detectLanguage(title, source.name), seasonEpisode = episode,
                originalLink = field("guid").takeIf { VIEW_URL.matches(it) },
                isSeries = episode != null)
        }
    }

    private companion object {
        const val NS = "https://nyaa.si/xmlns/nyaa"
        val HASH = Regex("[a-f0-9]{40}")
        val DECLARATION = Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE)
        // Hoisted: the guid of every parsed row is matched against this pattern.
        val VIEW_URL = Regex("https://nyaa\\.si/view/[0-9]+")
    }
}

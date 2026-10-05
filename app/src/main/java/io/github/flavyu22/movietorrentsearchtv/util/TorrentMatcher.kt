package io.github.flavyu22.movietorrentsearchtv.util

import io.github.flavyu22.movietorrentsearchtv.data.scraper.extractHashFromMagnet
import io.github.flavyu22.movietorrentsearchtv.model.Movie
import io.github.flavyu22.movietorrentsearchtv.model.UnifiedTorrent
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Conservative, token-based matching between catalogue entries and torrent names. */
object TorrentMatcher {

    // Torrent aggregators (TPB, Solid) routinely re-post the same content under
    // slightly different release-tag wording. These tokens are pure noise for matching;
    // removing them before the extras budget is computed keeps those rows visible without
    // accepting genuinely different titles.
    // Set (not List): membership is tested per token in the hot matching loop, so
    // lookups must be O(1) instead of a linear scan over ~70 noise tokens.
    private val RELEASE_NOISE = setOf(
        "brrip", "bdrip", "bluray", "webrip", "webdl", "web", "hdtv", "dvdrip", "dvd",
        "x264", "x265", "h264", "h265", "hevc", "av1", "10bit", "8bit", "hdr", "hdr10",
        "dolby", "vision", "uhd", "fhd", "hd", "sd", "480p", "576p", "720p", "1080p",
        "2160p", "4320p", "4k", "8k", "proper", "repack", "extended", "unrated",
        "remastered", "imax", "internal", "aac", "dts", "ac3", "eac3", "ddp", "ddp5",
        "ddp51", "truehd", "atmos", "multi", "dual", "vose", "castellano", "spanish",
        "latin", "latino", "english", "french", "german", "italian", "russian",
        "galaxyrg265", "eztv", "yify", "yts", "rarbg", "atomixhq",
    )

    private val relevanceCache = ConcurrentHashMap<String, Boolean>()

    fun isTorrentRelevant(torrent: UnifiedTorrent, movie: Movie): Boolean {
        // Every input which can affect the decision belongs in the key. In particular,
        // hashes are reused by aggregators while their title/source metadata may change.
        //
        // The key is built with a StringBuilder instead of
        // `listOf(...).joinToString("\u0000") { it.lowercase() }`: the previous form
        // allocated an 11-element list plus 11 lower-cased throw-away strings for EVERY
        // torrent examined, which dominated the matching pass across a full result set.
        // Only fields that can differ in case or content take part, and the cheap
        // identity fields (hash/id) are appended without any transformation.
        val cacheKey = StringBuilder(torrent.infoHash.length + movie.title.orEmpty().length + 96)
            .append(torrent.infoHash)
            .append('\u0000')
            .append(movie.id)
            .append('\u0000')
            .append(torrent.title)
            .append('\u0000')
            .append(torrent.magnetUrl)
            .append('\u0000')
            .append(torrent.source)
            .append('\u0000')
            .append(movie.title)
            .append('\u0000')
            .append(movie.originalTitle)
            .append('\u0000')
            .append(movie.localizedTitle)
            .append('\u0000')
            .append(movie.year)
            .append('\u0000')
            .append(movie.isSeries)
            .append('\u0000')
            .append(torrent.isSeries)
            .toString()
        relevanceCache[cacheKey]?.let { return it }

        val result = calculateRelevance(torrent, movie)
        if (relevanceCache.size >= MAX_CACHE_ENTRIES) relevanceCache.clear()
        relevanceCache[cacheKey] = result
        return result
    }

    private fun calculateRelevance(torrent: UnifiedTorrent, movie: Movie): Boolean {
        val magnetHash = extractHashFromMagnet(torrent.magnetUrl)
        if (magnetHash.isBlank()) return false
        if (torrent.infoHash.isNotBlank() &&
            !torrent.infoHash.equals(magnetHash, ignoreCase = true)
        ) return false

        val torrentTokens = tokenize(torrent.title.replaceFirst(BRACKET_PREFIX, ""))
        if (torrentTokens.isEmpty()) return false

        val titleVariants = listOfNotNull(movie.title, movie.originalTitle, movie.localizedTitle)
            .map(::tokenize)
            .filter(List<String>::isNotEmpty)
            .distinct()
        if (titleVariants.isEmpty()) return false

        val matches = titleVariants.mapNotNull { findOrderedMatch(torrentTokens, it) }
        if (matches.isEmpty()) return false

        // Prefer the variant which explains the greatest part of the apparent title.
        val titleMatch = matches.minWithOrNull(
            compareBy<TitleMatch> { countTitleExtras(torrentTokens, it) }
                .thenByDescending { it.matchedIndices.size }
        ) ?: return false

        if (containsPoorReleaseMarker(torrentTokens, titleMatch.matchedIndices.toSet())) {
            return false
        }

        val releaseYears = torrentTokens.withIndex()
            .filterNot { it.index in titleMatch.matchedIndices }
            .mapNotNull { (_, token) -> token.toReleaseYearOrNull() }
            .toList()
        val catalogueYear = movie.year?.takeIf { it in MIN_RELEASE_YEAR..currentYear() + 2 }
        val hasMatchingYear = catalogueYear != null && releaseYears.any { foundYear ->
            if (movie.isSeries) {
                foundYear in (catalogueYear - 1)..(currentYear() + 2)
            } else {
                foundYear in (catalogueYear - 1)..(catalogueYear + 1)
            }
        }
        // A missing catalogue year is not evidence of a mismatch. When both sides do
        // provide a year, however, reject a conflicting movie/sequel immediately.
        // Missing year on the torrent side is extremely common on TPB/Solid rows
        // (e.g. "Inception.1080p.BluRay.x264"), so it must not disqualify a title
        // that otherwise matches perfectly.
        if (catalogueYear != null && releaseYears.isNotEmpty() && !hasMatchingYear) {
            return false
        }

        val extras = countTitleExtras(torrentTokens, titleMatch)
        val meaningfulTargetSize = titleMatch.meaningfulTarget.size
        val allowedExtras = when {
            torrent.source.uppercase(Locale.ROOT) in TRANSLATED_TITLE_SOURCES -> 5
            meaningfulTargetSize == 1 && hasMatchingYear -> 3
            meaningfulTargetSize == 1 -> 0
            meaningfulTargetSize == 2 -> 1
            meaningfulTargetSize > 4 -> 4 // More lenient for long titles like "Watch What Happens Live with Andy Cohen"
            else -> 2
        }
        if (extras > allowedExtras) return false

        return true
    }

    // ─── Release-language detection (torrent language filter) ───────────────────
    // Release-name language markers mapped to the app language codes. Tokens are
    // matched as whole words only, so "grove" never matches "gr".
    private val LANGUAGE_TOKENS: Map<String, Set<String>> = mapOf(
        "EN" to setOf("en", "eng", "english"),
        "RO" to setOf("ro", "rom", "romana", "romanian"),
        "IT" to setOf("it", "ita", "italian", "italiano"),
        "ES" to setOf("es", "spa", "spanish", "espanol", "castellano", "latino", "latin"),
        "FR" to setOf("fr", "fra", "fre", "french", "francais", "truefrench", "vf", "vfi", "vostfr"),
        "DE" to setOf("de", "ger", "german", "deutsch"),
        "RU" to setOf("ru", "rus", "russian"),
        "PT" to setOf("pt", "por", "portuguese", "brazilian", "dublado"),
        "EL" to setOf("el", "gr", "greek"),
    )

    /** Whole-token lookup table: release token → app language code. */
    private val TOKEN_TO_LANGUAGE: Map<String, String> = buildMap {
        LANGUAGE_TOKENS.forEach { (code, tokens) -> tokens.forEach { put(it, code) } }
    }

    /** Multi-language audio releases; they always contain the wanted language. */
    private val MULTI_LANGUAGE_TOKENS = setOf("multi", "dual", "multilang", "multilanguage", "vose")

    /**
     * Detects the release language from a torrent title. Returns one of the app
     * language codes ("RO", "EN", …) or `null` when the title carries no
     * recognizable language marker (untagged international releases).
     */
    fun detectTitleLanguage(title: String?): String? {
        if (title.isNullOrBlank()) return null
        val tokens = tokenize(title)
        if (tokens.any { it in MULTI_LANGUAGE_TOKENS }) return null
        return tokens.firstNotNullOfOrNull { TOKEN_TO_LANGUAGE[it] }
    }

    /**
     * Language filter rule: a torrent passes when its title is untagged, a
     * multi-language release, or tagged with [appLanguage]. Only torrents whose
     * release language is recognizably a different one are rejected.
     */
    fun matchesAppLanguage(title: String?, appLanguage: String): Boolean {
        if (appLanguage.isBlank()) return true
        val detected = detectTitleLanguage(title) ?: return true
        return detected.equals(appLanguage, ignoreCase = true)
    }

    private fun findOrderedMatch(torrentTokens: List<String>, targetTokens: List<String>): TitleMatch? {
        val meaningfulTarget = targetTokens.filterNot { it in STOP_WORDS }
            .ifEmpty { targetTokens }
        val matchedIndices = ArrayList<Int>(meaningfulTarget.size)
        var searchFrom = 0

        for (targetToken in meaningfulTarget) {
            val index = (searchFrom until torrentTokens.size)
                .firstOrNull { torrentTokens[it] == targetToken }
                ?: return null
            // Words skipped inside a title may only be articles/prepositions. This
            // accepts "Lord of the Rings" without matching unrelated bag-of-words.
            if (matchedIndices.isNotEmpty()) {
                val gap = torrentTokens.subList(matchedIndices.last() + 1, index)
                if (gap.any { it !in STOP_WORDS }) return null
            }
            matchedIndices += index
            searchFrom = index + 1
        }
        return TitleMatch(meaningfulTarget, matchedIndices)
    }

    private fun countTitleExtras(tokens: List<String>, match: TitleMatch): Int {
        val matched = match.matchedIndices.toSet()
        val boundary = ((match.matchedIndices.lastOrNull() ?: -1) + 1 until tokens.size)
            .firstOrNull { index -> isReleaseMetadata(tokens[index]) }
            ?: tokens.size

        // In addition to stop-words and known groups, count pure release-noise
        // (codec/quality/audio/language tags, aggregator groups) as non-title tokens.
        // This makes long rows like "Inception.2010.1080p.BluRay.x264-GalaxyRG265" fit
        // within the extras budget on single-word titles instead of being dropped.
        return (0 until boundary).count { index ->
            index !in matched &&
                tokens[index] !in STOP_WORDS &&
                tokens[index] !in IGNORED_RELEASE_GROUPS &&
                tokens[index] !in RELEASE_NOISE
        }
    }

    private fun containsPoorReleaseMarker(tokens: List<String>, matched: Set<Int>): Boolean {
        tokens.forEachIndexed { index, token ->
            if (index !in matched && token in POOR_RELEASE_TOKENS) return true
        }
        for (phrase in POOR_RELEASE_PHRASES) {
            if (tokens.size < phrase.size) continue
            for (start in 0..tokens.size - phrase.size) {
                if (tokens.subList(start, start + phrase.size) == phrase &&
                    (start until start + phrase.size).any { it !in matched }
                ) return true
            }
        }
        return false
    }

    private fun isReleaseMetadata(token: String): Boolean =
        token in RELEASE_METADATA ||
            EPISODE_TOKEN.matches(token) ||
            token.toReleaseYearOrNull() != null

    private fun String.toReleaseYearOrNull(): Int? {
        if (length != 4 || any { !it.isDigit() }) return null
        return toIntOrNull()?.takeIf { it in MIN_RELEASE_YEAR..(currentYear() + 2) }
    }

    /**
     * Tokenization runs [java.text.Normalizer] plus a regex scan, and it is invoked for
     * every torrent title *and* every catalogue title variant. Because the catalogue
     * titles are identical for every row of a search, the same handful of strings were
     * re-normalized thousands of times per search. Results are immutable and therefore
     * safe to share.
     */
    private val tokenCache = ConcurrentHashMap<String, List<String>>()

    private fun tokenize(value: String): List<String> {
        if (value.isEmpty()) return emptyList()
        tokenCache[value]?.let { return it }
        val tokens = TOKEN_REGEX.findAll(normalizeText(value))
            .map { it.value }
            .toList()
        if (tokenCache.size >= MAX_TOKEN_CACHE_ENTRIES) tokenCache.clear()
        tokenCache[value] = tokens
        return tokens
    }

    private fun normalizeText(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase(Locale.ROOT)

    /**
     * [Calendar.getInstance] reads the system clock and allocates a calendar object; the
     * previous implementation called it once per release-year token per torrent. The year
     * only changes once every few hours at most, so it is refreshed on a timer instead.
     */
    private fun currentYear(): Int {
        val now = System.currentTimeMillis()
        val cached = cachedYear
        if (cached != 0 && now - cachedYearAtMs < CURRENT_YEAR_TTL_MS) return cached
        return synchronized(this) {
            val recheck = System.currentTimeMillis()
            if (cachedYear != 0 && recheck - cachedYearAtMs < CURRENT_YEAR_TTL_MS) {
                cachedYear
            } else {
                Calendar.getInstance().get(Calendar.YEAR).also {
                    cachedYear = it
                    cachedYearAtMs = recheck
                }
            }
        }
    }

    @Volatile
    private var cachedYear: Int = 0

    @Volatile
    private var cachedYearAtMs: Long = 0L

    private data class TitleMatch(
        val meaningfulTarget: List<String>,
        val matchedIndices: List<Int>
    )

    private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val BRACKET_PREFIX = Regex("""^\s*\[[^]]{1,32}]\s*""")
    private val EPISODE_TOKEN = Regex("""s\d{1,2}(?:e\d{1,3})?""")
    private val STOP_WORDS = setOf(
        "the", "a", "an", "of", "and", "in", "to", "is", "on", "at", "for", "with", "by"
    )
    private val POOR_RELEASE_TOKENS = setOf(
        "hcam", "telesync", "hdts", "camrip", "hdtc", "dvdscr", "korsub",
        "cam", "ts", "tc", "scr", "hc", "trailer", "soundtrack", "ost",
        "clip", "teaser", "bonus"
    )
    private val POOR_RELEASE_PHRASES = listOf(listOf("making", "of"))
    private val RELEASE_METADATA = setOf(
        "480p", "576p", "720p", "1080p", "2160p", "4320p", "4k", "8k",
        "uhd", "fhd", "hd", "sd", "brrip", "bdrip", "bluray", "web", "webrip",
        "hdtv", "dvdr", "dvdrip", "dvd", "x264", "x265", "h264", "h265", "hevc",
        "av1", "hdr", "hdr10", "10bit", "aac", "dts", "ac3", "multi", "dual",
        "proper", "repack", "extended", "unrated", "remastered", "imax", "internal",
        "complete", "season", "pack"
    )
    private val IGNORED_RELEASE_GROUPS = setOf("yts", "yify", "rarbg", "eztv")
    private val TRANSLATED_TITLE_SOURCES = setOf(
        "MEJORTORRENT", "ELITETORRENT", "DIVXTOTAL", "DONTORRENT", "GRANTORRENT",
        "ESTRENOSTORRENT"
    )
    private const val MIN_RELEASE_YEAR = 1870
    private const val MAX_CACHE_ENTRIES = 2_000
    private const val MAX_TOKEN_CACHE_ENTRIES = 4_000
    private const val CURRENT_YEAR_TTL_MS = 6L * 60L * 60L * 1_000L
}

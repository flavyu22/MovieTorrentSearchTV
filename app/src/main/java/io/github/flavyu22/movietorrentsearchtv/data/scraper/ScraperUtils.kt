package io.github.flavyu22.movietorrentsearchtv.data.scraper

import io.github.flavyu22.movietorrentsearchtv.model.UnifiedTorrent
import io.github.flavyu22.movietorrentsearchtv.util.readUpTo
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToLong

fun normalizeQualityLabel(quality: String?): String {
    val value = quality.orEmpty()
    return when {
        CAM_PATTERN.containsMatchIn(value) -> "CAM"
        EIGHT_K_PATTERN.containsMatchIn(value) -> "4320p"
        FOUR_K_PATTERN.containsMatchIn(value) -> "2160p"
        FULL_HD_PATTERN.containsMatchIn(value) -> "1080p"
        HD_PATTERN.containsMatchIn(value) -> "720p"
        SD_PATTERN.containsMatchIn(value) -> "480p"
        else -> "Unknown"
    }
}

fun extractQuality(title: String): String {
    val label = normalizeQualityLabel(title)
    if (label == "Unknown" || label == "CAM") return label

    val codec = when {
        HEVC_PATTERN.containsMatchIn(title) -> " x265"
        AV1_PATTERN.containsMatchIn(title) -> " AV1"
        else -> ""
    }
    val hdr = if (HDR_PATTERN.containsMatchIn(title)) " HDR" else ""
    return label + codec + hdr
}

fun detectLanguage(title: String, source: String): String? {
    val normalized = title.replace('_', '.').replace('-', '.')
    if (MULTI_LANGUAGE_PATTERN.containsMatchIn(normalized)) return "Multi"

    for ((language, patterns) in LANGUAGE_PATTERNS) {
        for (pattern in patterns) {
            if (pattern.containsMatchIn(normalized)) return language
        }
    }

    return when (source.uppercase(Locale.ROOT)) {
        "MEJORTORRENT", "ELITETORRENT", "DIVXTOTAL", "DONTORRENT", "GRANTORRENT",
        "ESTRENOSTORRENT" -> "ES"
        "YTS" -> "EN"
        else -> null
    }
}

fun extractSeasonEpisode(title: String): String? {
    for (pattern in EPISODE_PATTERNS) {
        val match = pattern.find(title) ?: continue
        val season = match.groupValues[1].toIntOrNull() ?: continue
        val episode = match.groupValues[2].toIntOrNull() ?: continue
        if (season !in 0..99 || episode !in 0..999) continue
        return "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
    }

    for (pattern in SEASON_PACK_PATTERNS) {
        val seasonMatch = pattern.find(title) ?: continue
        val season = seasonMatch.groupValues[1].toIntOrNull()?.takeIf { it in 0..99 }
            ?: continue
        return "S${season.toString().padStart(2, '0')}E00"
    }
    return null
}

fun extractHashFromMagnet(magnet: String): String {
    if (magnet.length !in 1..MAX_MAGNET_CHARS ||
        !magnet.startsWith("magnet:?", ignoreCase = true)
    ) return ""

    // A magnet URI is opaque to java.net.URI/android.net.Uri. Parse its individual
    // parameters instead of decoding the whole URI: an encoded '&xt=' inside `dn`
    // must never become a second, attacker-controlled query parameter.
    val rawQuery = magnet.substringAfter('?', "").substringBefore('#')
    var exactTopic: String? = null
    var exactTopicCount = 0
    for (parameter in rawQuery.split('&')) {
        if (parameter.isEmpty()) continue
        val rawName = parameter.substringBefore('=')
        val rawValue = parameter.substringAfter('=', "")
        val name = decodeQueryComponent(rawName) ?: continue
        if (!name.equals("xt", ignoreCase = true)) continue
        exactTopicCount++
        if (exactTopicCount > 1) return ""

        val value = decodeQueryComponent(rawValue)?.trim().orEmpty()
        if (!value.startsWith(BTIH_PREFIX, ignoreCase = true)) return ""
        val hash = value.substring(BTIH_PREFIX.length)
        if (!INFO_HASH.matches(hash)) return ""
        exactTopic = hash.lowercase(Locale.ROOT)
    }
    return exactTopic.takeIf { exactTopicCount == 1 }.orEmpty()
}

fun enhanceMagnet(magnet: String): String {
    // Keep trackers chosen by the source/user. Automatically announcing every
    // search result to a hard-coded tracker list leaks viewing interests and is
    // unnecessary when TorrServer/DHT can resolve the info-hash.
    return magnet
}

/** Finds the first magnet URI embedded in a page's HTML, or null when absent. */
internal fun firstMagnetLink(html: String): String? = MAGNET_LINK.find(html)?.value

/** Returns (magnet, anchor-text) pairs for every magnet anchor embedded in a page. */
internal fun magnetAnchorCandidates(html: String): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    for (match in MAGNET_ANCHOR.findAll(html)) {
        val magnet = match.groupValues[1]
        if (extractHashFromMagnet(magnet).isEmpty()) continue
        val label = match.groupValues[2].stripTags().ifEmpty { magnetDisplayName(magnet) }
        out += magnet to label
    }
    return out
}

/** Removes HTML tags and collapses whitespace in a scraped label. */
internal fun String.stripTags(): String {
    // WHITESPACE_COLLAPSE is hoisted to a file-level constant: stripTags() runs once per
    // scraped anchor/label, and compiling the pattern per call dominated that loop.
    val cleaned = HTML_TAG.replace(this, " ").replace(WHITESPACE_COLLAPSE, " ").trim()
    return cleaned.take(MAX_TORRENT_TITLE_CHARS)
}

/**
 * Resolves a (possibly relative or scheme-relative) href against [base] and returns an
 * HTTPS absolute URL, or null when resolution fails or the result is not HTTPS.
 */
internal fun absoluteUrl(base: String, href: String): String? {
    val resolved = base.toHttpUrlOrNull()?.resolve(href) ?: return null
    if (!resolved.isHttps || resolved.username.isNotEmpty() || resolved.password.isNotEmpty()) {
        return null
    }
    return resolved.toString().takeIf { it.length <= MAX_SOURCE_URL_CHARS }
}

/**
 * Returns the raw bytes of a .torrent's bencoded `info` dictionary as the hex SHA-1
 * infohash, or null when the file is not parseable. Indexers that publish .torrent files
 * (rather than magnet URIs) can be converted into equivalent magnet links this way.
 */
fun magnetInfohashFromTorrent(torrentBytes: ByteArray): String? {
    val info = bencodedInfoDictionary(torrentBytes) ?: return null
    val digest = MessageDigest.getInstance("SHA-1").digest(info)
    // A lookup table replaces "%02x".format(byte) per digest byte. String.format allocates a
    // Formatter, parses the format string and boxes the argument, so the original 40-iteration
    // loop was orders of magnitude slower than a direct hex table lookup.
    val hex = CharArray(digest.size * 2)
    for (index in digest.indices) {
        val value = digest[index].toInt() and 0xff
        hex[index * 2] = HEX_DIGITS[value ushr 4]
        hex[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
    }
    return String(hex)
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/** Returns the raw bytes of the top-level `info` dictionary, or null when not parseable. */
private fun bencodedInfoDictionary(torrent: ByteArray): ByteArray? {
    if (torrent.isEmpty() || torrent.first() != 'd'.code.toByte()) return null
    var pos = 1
    while (pos < torrent.size) {
        val (value, length) = bencodeString(torrent, pos) ?: return null
        pos += length
        if (value == "info") {
            val start = pos
            val end = skipBencodeValue(torrent, pos) ?: return null
            if ((end - start).toLong() > MAX_INFOHASH_INFO_BYTES) return null
            return torrent.copyOfRange(start, end)
        }
        pos = skipBencodeValue(torrent, pos) ?: return null
    }
    return null
}

/** bencode byte-string "N:payload" -> decoded payload plus the consumed length. */
private fun bencodeString(data: ByteArray, start: Int): Pair<String, Int>? {
    val colon = indexOfByte(data, ':', start) ?: return null
    val lengthText = String(data, start, colon - start, StandardCharsets.US_ASCII)
    val length = lengthText.toIntOrNull()?.takeIf { it in 0..MAX_TORRENT_FILE_BYTES.toInt() } ?: return null
    val payloadStart = colon + 1
    val end = payloadStart + length
    if (end > data.size) return null
    return String(data, payloadStart, length, StandardCharsets.UTF_8) to (end - start)
}

/** Skips a whole bencode value (integer, list, dictionary or byte string). */
private fun skipBencodeValue(data: ByteArray, start: Int): Int? {
    if (start >= data.size) return null
    when (data[start]) {
        'i'.code.toByte() -> {
            val end = indexOfByte(data, 'e', start + 1) ?: return null
            return end + 1
        }
        'l'.code.toByte() -> {
            var pos = start + 1
            while (pos < data.size && data[pos] != 'e'.code.toByte()) {
                pos = skipBencodeValue(data, pos) ?: return null
            }
            return if (pos < data.size) pos + 1 else null
        }
        'd'.code.toByte() -> {
            var pos = start + 1
            while (pos < data.size && data[pos] != 'e'.code.toByte()) {
                val (_, length) = bencodeString(data, pos) ?: return null
                pos += length
                pos = skipBencodeValue(data, pos) ?: return null
            }
            return if (pos < data.size) pos + 1 else null
        }
        else -> {
            val (_, length) = bencodeString(data, start) ?: return null
            return start + length
        }
    }
}

private fun indexOfByte(data: ByteArray, target: Char, from: Int): Int? {
    val needle = target.code.toByte()
    var pos = from.coerceAtLeast(0)
    while (pos < data.size) {
        if (data[pos] == needle) return pos
        pos++
    }
    return null
}

/**
 * Display name carried by a magnet URI `dn` parameter (URL-decoded), or empty when absent.
 * Parsing is parameter-based and never decodes the whole URI, so an encoded `&` inside a
 * value cannot smuggle extra parameters.
 */
fun magnetDisplayName(magnet: String): String {
    if (magnet.length !in 1..MAX_MAGNET_CHARS) return ""
    val rawQuery = magnet.substringAfter('?', "").substringBefore('#')
    for (parameter in rawQuery.split('&')) {
        if (parameter.isEmpty()) continue
        if (!parameter.substringBefore('=').equals("dn", ignoreCase = true)) continue
        return decodeQueryComponent(parameter.substringAfter('=', ""))
            ?.trim()
            ?.take(MAX_TORRENT_TITLE_CHARS)
            .orEmpty()
    }
    return ""
}

/**
 * Parses the search-result HTML of a Spanish-language indexer. Every distinct magnet link
 * becomes a result; the title comes from the enclosing anchor text when present, otherwise
 * from the magnet `dn` parameter, so no site-specific layout is required. All results are
 * tagged with the Spanish language.
 */
fun parseMagnetSearchHtml(html: String, sourceName: String): List<UnifiedTorrent> {
    val titleByHash = LinkedHashMap<String, String>()

    // 1) Anchors wrapping a magnet link: the link text is the release name.
    for (match in MAGNET_ANCHOR.findAll(html)) {
        val magnet = match.groupValues[1]
        val infoHash = extractHashFromMagnet(magnet)
        if (infoHash.isEmpty()) continue
        val label = match.groupValues[2].stripTags()
            .ifEmpty { magnetDisplayName(magnet) }
        if (label.isNotEmpty()) titleByHash.putIfAbsent(infoHash, label)
    }

    // 2) Bare magnet links: fall back to the magnet `dn` display-name parameter.
    for (match in MAGNET_LINK.findAll(html)) {
        val magnet = match.value
        val infoHash = extractHashFromMagnet(magnet)
        if (infoHash.isEmpty() || titleByHash.containsKey(infoHash)) continue
        val label = magnetDisplayName(magnet)
        if (label.isNotEmpty()) titleByHash[infoHash] = label
    }

    val results = ArrayList<UnifiedTorrent>(titleByHash.size)
    for ((infoHash, title) in titleByHash) {
        if (results.size >= MAX_SCRAPER_RESULTS) break
        results += UnifiedTorrent(
            infoHash = infoHash,
            title = title,
            magnetUrl = enhanceMagnet("magnet:?xt=urn:btih:$infoHash&dn=${encodeTitle(title)}"),
            size = "N/A",
            seeds = 0,
            peers = 0,
            quality = extractQuality(title),
            source = sourceName,
            language = detectLanguage(title, sourceName),
            uploadDate = null,
            seasonEpisode = extractSeasonEpisode(title),
            isSeries = extractSeasonEpisode(title) != null,
        )
    }
    return results
}

fun normalizeSize(sizeStr: String): String {
    val bytes = UnifiedTorrent.parseSizeToBytes(sizeStr)
    if (bytes <= 0L) return sizeStr.trim().ifEmpty { "N/A" }
    // String.format is one of the most expensive calls available on Android (it parses the
    // format string, allocates a Formatter and boxes the arguments). It used to run once
    // per torrent row; plain StringBuilder concatenation is equivalent for these fixed
    // "%.2f"/"%.0f" shapes and dramatically cheaper.
    return when {
        bytes >= TIB -> buildString {
            append(formatTwoDecimals(bytes / TIB.toDouble()))
            append(" TB")
        }
        bytes >= GIB -> buildString {
            append(formatTwoDecimals(bytes / GIB.toDouble()))
            append(" GB")
        }
        bytes >= MIB -> buildString {
            append(formatNoDecimals(bytes / MIB.toDouble()))
            append(" MB")
        }
        bytes >= KIB -> buildString {
            append(formatNoDecimals(bytes / KIB.toDouble()))
            append(" KB")
        }
        else -> "$bytes B"
    }
}

/** Equivalent to String.format(Locale.US, "%.2f", value) without the Formatter overhead. */
private fun formatTwoDecimals(value: Double): String {
    // `.roundToLong()` rounds half-up for non-negative values, matching String.format("%.2f")
    // (which rounds half-up too) without the Math.round -> Long detour.
    val scaled = (value * 100.0).roundToLong()
    val whole = scaled / 100
    val fraction = scaled % 100
    return buildString(8) {
        append(whole)
        append('.')
        if (fraction < 10) append('0')
        append(fraction)
    }
}

/** Equivalent to String.format(Locale.US, "%.0f", value) without the Formatter overhead. */
private fun formatNoDecimals(value: Double): String = Math.round(value).toString()

internal class HttpStatusException(val statusCode: Int, url: String) :
    IOException("HTTP $statusCode from ${url.substringBefore('?')}")

internal class ResponseTooLargeException(url: String, maxBytes: Long) :
    IOException("Response from ${url.substringBefore('?')} exceeds $maxBytes bytes")

internal suspend fun OkHttpClient.awaitBody(
    request: Request,
    maxBytes: Long = MAX_JSON_RESPONSE_BYTES
): String =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw HttpStatusException(it.code, request.url.toString())
                        val body = it.body
                        val contentLength = body.contentLength()
                        if (contentLength > maxBytes) {
                            throw ResponseTooLargeException(request.url.toString(), maxBytes)
                        }
                        val bytes = body.source().readUpTo(maxBytes + 1L)
                        if (bytes.size.toLong() > maxBytes) {
                            throw ResponseTooLargeException(request.url.toString(), maxBytes)
                        }
                        bytes.toString(Charsets.UTF_8)
                    }
                }
                if (!continuation.isCancelled) {
                    result.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(it) }
                    )
                }
            }
        })
    }

/** Fetches a binary payload (for example a .torrent file) with the same safety limits. */
internal suspend fun OkHttpClient.awaitBytes(
    request: Request,
    maxBytes: Long = MAX_TORRENT_FILE_BYTES
): ByteArray =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw HttpStatusException(it.code, request.url.toString())
                        val stream = it.body.byteStream()
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = stream.read(chunk)
                            if (read <= 0) break
                            total += read
                            if (total > maxBytes) {
                                throw ResponseTooLargeException(request.url.toString(), maxBytes)
                            }
                            buffer.write(chunk, 0, read)
                        }
                        buffer.toByteArray()
                    }
                }
                if (!continuation.isCancelled) {
                    result.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(it) }
                    )
                }
            }
        })
    }

private val WHITESPACE_COLLAPSE = Regex("\\s+")
// Release names glue the resolution to the next token without any separator, so a trailing
// `\b` never fires: in "2160pHD" the next character is "H" (still a word character) and in
// "1080p_nnm-club" it is "_" (also a word character). Those titles therefore fell through to
// "Unknown" even though the resolution was right there. The trailing class instead ends the
// token on any non-alphanumeric separator, while still rejecting a longer number such as
// "10800p". A leading `\b` is kept so "x264" never matches the "264" of a resolution.
private val CAM_PATTERN = Regex("""\b(?:HD[ ._-]?CAM|CAM[ ._-]?RIP|CAM|TELESYNC|TS|TC)\b""", RegexOption.IGNORE_CASE)
private val EIGHT_K_PATTERN = Regex("""\b(?:4320P?|8K)(?![0-9])""", RegexOption.IGNORE_CASE)
private val FOUR_K_PATTERN = Regex("""\b(?:2160P?|4K|UHD)(?![0-9])""", RegexOption.IGNORE_CASE)
private val FULL_HD_PATTERN = Regex("""\b(?:1080P?|FHD)(?![0-9])""", RegexOption.IGNORE_CASE)
private val HD_PATTERN = Regex("""\b(?:720P?|HD)(?![0-9A-Za-z])""", RegexOption.IGNORE_CASE)
private val SD_PATTERN = Regex("""\b(?:480P?|576P?|SD|DVD)(?![0-9A-Za-z])""", RegexOption.IGNORE_CASE)
private val HEVC_PATTERN = Regex("""\b(?:X265|H[ ._-]?265|HEVC)\b""", RegexOption.IGNORE_CASE)
private val AV1_PATTERN = Regex("""\bAV1\b""", RegexOption.IGNORE_CASE)
private val HDR_PATTERN = Regex("""\b(?:HDR10\+?|HDR|DOLBY[ ._-]?VISION)\b""", RegexOption.IGNORE_CASE)

private val MULTI_LANGUAGE_PATTERN = Regex(
    """\b(?:MULTI(?:[ ._-]?(?:AUDIO|SUBS?))?|DUAL(?:[ ._-]?AUDIO)?|DUALLAT|DUALCAST|MVO|DVO)\b""",
    RegexOption.IGNORE_CASE
)
private val LANGUAGE_PATTERNS = linkedMapOf(
    "RO" to listOf(
        Regex("""\b(?:ROMANIAN|ROMANA|ROMÂNĂ|SUB[ ._-]?RO|DUBLAT|SUBTITRAT)\b""", RegexOption.IGNORE_CASE),
        Regex("""\[(?:RO|RON)\]""", RegexOption.IGNORE_CASE)
    ),
    "ES" to listOf(
        Regex("""\b(?:SPANISH|ESPANOL|ESPAÑOL|CASTELLANO|LATINO|VOSE)\b""", RegexOption.IGNORE_CASE),
        Regex("""\[(?:ES|ESP)\]""", RegexOption.IGNORE_CASE)
    ),
    "EN" to listOf(
        Regex("""\b(?:ENGLISH|ENG)\b""", RegexOption.IGNORE_CASE),
        Regex("""\[(?:EN|ENG)\]""", RegexOption.IGNORE_CASE)
    ),
    "FR" to listOf(
        Regex("""\b(?:FRENCH|FRA|TRUEFRENCH|VFF|VFQ)\b""", RegexOption.IGNORE_CASE),
        Regex("""\[FR\]""", RegexOption.IGNORE_CASE)
    ),
    "IT" to listOf(
        Regex("""\b(?:ITALIAN|ITALIANO|ITA)\b""", RegexOption.IGNORE_CASE),
        Regex("""\[IT\]""", RegexOption.IGNORE_CASE)
    ),
    "DE" to listOf(
        Regex("""\b(?:GERMAN|GER|DEUTSCH)\b""", RegexOption.IGNORE_CASE),
        Regex("""\[DE\]""", RegexOption.IGNORE_CASE)
    ),
    "RU" to listOf(
        Regex("""\b(?:RUSSIAN|RUS)\b""", RegexOption.IGNORE_CASE),
        Regex("""\[RU\]""", RegexOption.IGNORE_CASE)
    )
)

private val EPISODE_PATTERNS = listOf(
    Regex("""(?i)\bS\s*(\d{1,2})[ ._-]*E\s*(\d{1,3})\b"""),
    Regex("""(?i)\b(\d{1,2})\s*[xX]\s*(\d{1,3})\b"""),
    Regex("""(?i)\bSeason\s*(\d{1,2})[ ._-]*Episode\s*(\d{1,3})\b""")
)
private val SEASON_PACK_PATTERNS = listOf(
    Regex("""(?i)\b(?:Season\s*|S)(\d{1,2})\b(?:[ ._-]*(?:Complete|Pack))?"""),
    Regex("""(?i)\b(?:Complete|Pack)[ ._-]*(?:Season\s*|S)(\d{1,2})\b""")
)
private val INFO_HASH = Regex("""(?i)(?:[a-f0-9]{40}|[a-z2-7]{32})""")
private const val BTIH_PREFIX = "urn:btih:"
private val MAGNET_LINK = Regex("""magnet:\?xt=urn:btih:[a-zA-Z0-9]{32,40}[^"' <>]*""")
private val MAGNET_ANCHOR = Regex(
    """<a[^>]+href="(magnet:\?xt=urn:btih:[a-zA-Z0-9]{32,40}[^"<>]*)"[^>]*>(.*?)</a>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val HTML_TAG = Regex("""<[^>]*>""")
internal const val MAX_JSON_RESPONSE_BYTES = 2L * 1024L * 1024L
internal const val MAX_SCRAPER_JSON_BYTES = 2L * 1024L * 1024L
internal const val MAX_SCRAPER_HTML_BYTES = 8L * 1024L * 1024L
internal const val MAX_TORRENT_FILE_BYTES = 8L * 1024L * 1024L
private const val MAX_INFOHASH_INFO_BYTES = 4L * 1024L * 1024L
internal const val MAX_SCRAPER_RESULTS = 100
internal const val MAX_TORRENTS_PER_MOVIE = 20
internal const val MAX_TORRENT_TITLE_CHARS = 512
internal const val MAX_SOURCE_URL_CHARS = 2_048
internal const val MAX_SOURCE_VALUE_CHARS = 256
internal const val MAX_MAGNET_CHARS = 4_096
private const val KIB = 1_024L
private const val MIB = 1_024L * KIB
private const val GIB = 1_024L * MIB
private const val TIB = 1_024L * GIB

private fun decodeQueryComponent(value: String): String? = runCatching {
    URLDecoder.decode(value, Charsets.UTF_8.name())
}.getOrNull()


private fun encodeTitle(title: String): String =
    URLEncoder.encode(title.take(MAX_TORRENT_TITLE_CHARS), Charsets.UTF_8.name()).replace("+", "%20")

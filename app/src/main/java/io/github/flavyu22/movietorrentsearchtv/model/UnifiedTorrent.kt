package io.github.flavyu22.movietorrentsearchtv.model

import androidx.compose.runtime.Immutable
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToLong

@Immutable
data class TorrentSource(
    val name: String,
    val url: String,
    val reliability: Float,
    val supportedCategories: List<Category>
) {
    enum class Category { MOVIES, TV_SHOWS, GAMES, MUSIC, ALL }
}

@Immutable
data class UnifiedTorrent(
    val infoHash: String,
    val title: String,
    val magnetUrl: String,
    val size: String,
    val seeds: Int,
    val peers: Int,
    val quality: String,
    val source: String,
    val language: String? = null,
    val uploadDate: String? = null,
    val seasonEpisode: String? = null,
    val originalLink: String? = null,
    val isSeries: Boolean = false
) : Comparable<UnifiedTorrent> {

    // Derived sort keys are memoized per instance: the sort comparators in
    // AggregatedTorrentViewModel read them O(n log n) times per list refresh, and each
    // read used to re-run the size/date parsers from scratch. UnifiedTorrent is only
    // ever held in memory (never Gson-serialized), so the lazy delegates are safe.
    val sizeInBytes: Long by lazy { parseSizeToBytes(size) }

    val qualityScore: Int by lazy { qualityScoreFor(quality) }

    val uploadTimeMillis: Long by lazy { parseUploadDateToMillis(uploadDate) }

    override fun compareTo(other: UnifiedTorrent): Int =
        compareValuesBy(
            this,
            other,
            { -it.qualityScore },
            { -it.seeds },
            { it.seasonEpisode ?: "ZZZ" },
            { it.title.lowercase(Locale.ROOT) }
        )

    companion object {
        private val SIZE_PATTERN = Regex(
            """([0-9]+(?:[.,][0-9]+)?)\s*([KMGTPE]?I?B|BYTES?)?""",
            RegexOption.IGNORE_CASE
        )
        private val DATE_FORMATS = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd"
        )

        // Precompiled once: qualityScoreFor is called from sort comparators, so
        // compiling these patterns per call used to dominate torrent-list sorting.
        // Inputs are uppercased before matching, hence no IGNORE_CASE option.
        private val CAM_QUALITY_PATTERN = Regex("""\b(HDCAM|CAMRIP|CAM|TELESYNC|TS|TC)\b""")
        private val EIGHT_K_QUALITY_PATTERN = Regex("""\b(4320P|8K)\b""")
        private val FOUR_K_QUALITY_PATTERN = Regex("""\b(2160P|4K|UHD)\b""")
        private val FULL_HD_QUALITY_PATTERN = Regex("""\b(1080P|FHD)\b""")
        private val HD_QUALITY_PATTERN = Regex("""\b(720P|HD)\b""")
        private val SD_QUALITY_PATTERN = Regex("""\b(480P|576P|SD|DVD)\b""")
        private val EFFICIENT_CODEC_PATTERN = Regex("""\b(X265|H265|HEVC|AV1)\b""")
        private val HDR_QUALITY_PATTERN = Regex("""\b(HDR10\+?|DOLBY[ ._-]?VISION|DV)\b""")

        fun parseSizeToBytes(size: String): Long {
            val match = SIZE_PATTERN.find(size.trim()) ?: return 0L
            val numericText = normalizeDecimal(match.groupValues[1])
            val amount = numericText.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
                ?: return 0L
            val unit = match.groupValues[2].uppercase(Locale.ROOT)
            val multiplier = when (unit) {
                "KB", "KIB" -> 1_024.0
                "MB", "MIB" -> 1_024.0 * 1_024.0
                "GB", "GIB" -> 1_024.0 * 1_024.0 * 1_024.0
                "TB", "TIB" -> 1_024.0 * 1_024.0 * 1_024.0 * 1_024.0
                "PB", "PIB" -> 1_024.0 * 1_024.0 * 1_024.0 * 1_024.0 * 1_024.0
                "EB", "EIB" -> 1_024.0 * 1_024.0 * 1_024.0 * 1_024.0 * 1_024.0 * 1_024.0
                else -> 1.0
            }
            val bytes = amount * multiplier
            return if (bytes >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else bytes.roundToLong()
        }

        fun qualityScoreFor(quality: String?): Int {
            val normalized = quality.orEmpty().uppercase(Locale.ROOT)
            if (normalized.isBlank()) return 0
            if (CAM_QUALITY_PATTERN.containsMatchIn(normalized)) {
                return -100
            }

            var score = when {
                EIGHT_K_QUALITY_PATTERN.containsMatchIn(normalized) -> 500
                FOUR_K_QUALITY_PATTERN.containsMatchIn(normalized) -> 400
                FULL_HD_QUALITY_PATTERN.containsMatchIn(normalized) -> 300
                HD_QUALITY_PATTERN.containsMatchIn(normalized) -> 200
                SD_QUALITY_PATTERN.containsMatchIn(normalized) -> 100
                else -> 0
            }
            if (EFFICIENT_CODEC_PATTERN.containsMatchIn(normalized)) score += 25
            if (HDR_QUALITY_PATTERN.containsMatchIn(normalized)) score += 10
            return score
        }

        fun parseUploadDateToMillis(value: String?): Long {
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return 0L

            text.toLongOrNull()?.let { numeric ->
                return when {
                    numeric <= 0L -> 0L
                    numeric < 100_000_000_000L -> numeric * 1_000L
                    else -> numeric
                }
            }

            for (pattern in DATE_FORMATS) {
                val formatter = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val position = ParsePosition(0)
                val parsed = formatter.parse(text, position)
                if (parsed != null && position.index == text.length) return parsed.time
            }
            return 0L
        }

        private fun normalizeDecimal(value: String): String {
            if (',' !in value) return value
            val fractionalDigits = value.length - value.lastIndexOf(',') - 1
            return if (fractionalDigits == 3 && value.count { it == ',' } == 1) {
                value.replace(",", "")
            } else {
                value.replace(',', '.')
            }
        }
    }
}

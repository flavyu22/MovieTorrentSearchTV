package io.github.flavyu22.movietorrentsearchtv.util

import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Pure HTTPS allow-list policy for URLs originating in remote catalogue payloads. */
object RemoteUrlPolicy {
    private const val MAX_URL_LENGTH = 2_048
    private val YOUTUBE_VIDEO_ID = Regex("[A-Za-z0-9_-]{6,20}")

    fun allowlistedHttps(
        rawUrl: String?,
        allowedHostSuffixes: Set<String>,
        upgradeCleartext: Boolean = false,
    ): String? {
        val candidate = rawUrl?.trim()
            ?.takeIf { it.length in 1..MAX_URL_LENGTH }
            ?.takeIf { value -> value.none { it.isWhitespace() || it.isISOControl() } }
            ?: return null
        var parsed = candidate.toHttpUrlOrNull() ?: return null

        // Poster artwork restored from old local history can still be cleartext. Only
        // callers that opt in upgrade it to HTTPS on the same host; everything else is
        // rejected outright so a cleartext URL is never silently trusted.
        if (!parsed.isHttps) {
            if (!upgradeCleartext) return null
            parsed = parsed.newBuilder()
                .scheme("https")
                .port(if (parsed.port == 80) 443 else parsed.port)
                .build()
        }
        if (parsed.port != 443) return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null

        val host = parsed.host.lowercase(Locale.ROOT).removeSuffix(".")
        val allowed = allowedHostSuffixes.any { rawSuffix ->
            val suffix = rawSuffix.lowercase(Locale.ROOT).trim('.')
            suffix.isNotEmpty() && (host == suffix || host.endsWith(".$suffix"))
        }
        return parsed.toString().takeIf { allowed }
    }

    fun youtubeVideoId(rawId: String?): String? = rawId?.takeIf(YOUTUBE_VIDEO_ID::matches)
}

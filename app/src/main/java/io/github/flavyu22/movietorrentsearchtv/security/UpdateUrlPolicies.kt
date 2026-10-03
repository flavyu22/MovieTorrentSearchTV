package io.github.flavyu22.movietorrentsearchtv.security

import java.net.URI
import java.util.Locale

/** Build-time update manifest endpoints must remain ordinary public HTTPS JSON URLs. */
object UpdateManifestUrlPolicy {
    private val dnsHost = Regex(
        "(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}",
    )
    private val pathSegment = Regex("[A-Za-z0-9._~-]+")

    fun validate(url: String): String? {
        if (url.length !in 1..2_048 || url.any { it.isWhitespace() || it.isISOControl() }) {
            return null
        }
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.isOpaque ||
            !dnsHost.matches(host) ||
            uri.userInfo != null ||
            uri.query != null ||
            uri.fragment != null ||
            uri.port !in listOf(-1, 443)
        ) return null

        val segments = uri.rawPath.removePrefix("/").split('/')
        if (segments.isEmpty() ||
            segments.any { !pathSegment.matches(it) || it == "." || it == ".." } ||
            segments.last() != "update.json"
        ) return null

        return "https://${host.lowercase(Locale.ROOT)}${uri.rawPath}"
    }
}

/** HTTPS host policy for the GitHub release request and its signed asset redirects. */
object UpdateDownloadUrlPolicy {
    private const val MAX_URL_LENGTH = 4_096

    fun validate(url: String): String? {
        if (url.length !in 1..MAX_URL_LENGTH ||
            url.any { it.isWhitespace() || it.isISOControl() }
        ) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val rawPath = uri.rawPath ?: return null
        val trustedHost = host == "github.com" ||
            host == "githubusercontent.com" ||
            host.endsWith(".githubusercontent.com")
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.isOpaque ||
            !trustedHost ||
            uri.userInfo != null ||
            uri.fragment != null ||
            uri.port !in listOf(-1, 443) ||
            rawPath.isBlank() ||
            !rawPath.startsWith('/')
        ) return null
        return uri.toASCIIString()
    }
}

/** Defense in depth for externally supplied APK links from the update manifest. */
object UpdateUrlPolicy {
    private const val ALLOWED_HOST = "github.com"
    private val repository = Regex(
        "[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/" +
            "[A-Za-z0-9](?:[A-Za-z0-9_.-]{0,99})",
    )
    private val releaseSegment = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val apkSegment = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,191}\\.[aA][pP][kK]")

    fun validate(url: String, allowedRepository: String): String? {
        if (!repository.matches(allowedRepository)) return null
        val allowedPathPrefix = "/$allowedRepository/releases/download/"
        if (url.length !in 1..2_048 || url.any(Char::isISOControl)) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.isOpaque ||
            !uri.host.equals(ALLOWED_HOST, ignoreCase = true) ||
            uri.userInfo != null ||
            uri.fragment != null ||
            uri.query != null ||
            uri.port !in listOf(-1, 443) ||
            !uri.rawPath.startsWith(allowedPathPrefix)
        ) return null

        // Release URLs do not need percent-encoding. Restricting both remaining path
        // segments avoids encoded separators, dot traversal and ambiguous normalization.
        val remainder = uri.rawPath.removePrefix(allowedPathPrefix)
        val segments = remainder.split('/')
        if (segments.size != 2 ||
            !releaseSegment.matches(segments[0]) ||
            !apkSegment.matches(segments[1])
        ) return null

        return "https://$ALLOWED_HOST${uri.rawPath}"
    }
}

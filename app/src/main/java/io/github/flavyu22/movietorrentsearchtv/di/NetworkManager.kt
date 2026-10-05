package io.github.flavyu22.movietorrentsearchtv.di

import android.content.Context
import io.github.flavyu22.movietorrentsearchtv.BuildConfig
import io.github.flavyu22.movietorrentsearchtv.config.RemoteHosts
import okhttp3.*
import java.io.File
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/**
 * Process-wide HTTP client with conservative limits suitable for low-memory TVs.
 * Server cache directives are preserved; the client never turns private or stale
 * responses into cacheable responses on its own.
 */
object NetworkManager {
    @Volatile
    private var client: OkHttpClient? = null

    private const val CACHE_SIZE_BYTES = 32L * 1024L * 1024L
    private const val MAX_RESPONSE_BYTES = 16L * 1024L * 1024L
    private const val CONNECT_TIMEOUT_SECONDS = 15
    private const val READ_TIMEOUT_SECONDS = 40
    private const val WRITE_TIMEOUT_SECONDS = 40
    private const val CALL_TIMEOUT_SECONDS = 45
    /** Bounds the host-policy memo tables; the real host set is far smaller than this. */
    private const val MAX_HOST_POLICY_CACHE = 512

    fun getOkHttpClient(context: Context): OkHttpClient {
        return client ?: synchronized(this) {
            client ?: buildOptimizedClient(context).also { client = it }
        }
    }

    private fun buildOptimizedClient(context: Context): OkHttpClient {
        val appContext = context.applicationContext
        val cache = Cache(File(appContext.cacheDir, "http_cache"), CACHE_SIZE_BYTES)

        val dispatcher = Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 8
        }

        val clientBuilder = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .cache(cache)
            .connectionPool(
                ConnectionPool(
                    maxIdleConnections = 8,
                    keepAliveDuration = 5,
                    timeUnit = TimeUnit.MINUTES,
                )
            )
            .callTimeout(CALL_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            // Never follow a redirect across the HTTPS/HTTP boundary. In particular,
            // a compromised public endpoint cannot downgrade a TLS request.
            .followSslRedirects(false)

            .addInterceptor { chain ->
                val originalRequest = chain.request()
                enforceTransportPolicy(originalRequest)
                val requestBuilder = originalRequest.newBuilder()
                if (originalRequest.header("User-Agent") == null) {
                    // Using a standard browser User-Agent helps bypass basic anti-bot filters
                    // on many torrent sites and mirrors.
                    requestBuilder.header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    )
                }
                if (originalRequest.header("Accept") == null) {
                    val accept = if (isCatalogueImageHost(originalRequest.url.host)) {
                        "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
                    } else {
                        "application/json,text/html;q=0.9,*/*;q=0.8"
                    }
                    requestBuilder.header("Accept", accept)
                }

                limitResponse(chain.proceed(requestBuilder.build()))
            }
            // Application interceptors run only once for a redirect chain. Re-check every
            // network exchange so same-scheme redirects cannot escape the LAN HTTP policy.
            .addNetworkInterceptor { chain ->
                enforceTransportPolicy(chain.request())
                chain.proceed(chain.request())
            }
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))

        return clientBuilder.build()
    }

    private fun enforceTransportPolicy(request: Request) {
        val url = request.url
        if (url.scheme == "http" && !isCleartextHostPermitted(url.host)) {
            throw IOException("Cleartext HTTP is restricted to private TorrServer endpoints")
        }
    }

    /**
     * Loopback never leaves the device, so an on-device TorrServer stays reachable in
     * every build. Broader private ranges additionally require the direct distribution
     * capability flag. Public hosts are never eligible for cleartext.
     *
     * Results are memoized per host: the transport policy runs on every application
     * interceptor invocation *and* on every network hop of every redirect chain, and the
     * previous implementation re-lowercased the host, re-ran the IPv4 split and, for IPv6
     * literals, performed a full `InetAddress.getByName` each time. The set of hosts an
     * app talks to is small and bounded, so a small cache removes essentially all of that
     * repeated parsing without changing the decision.
     */
    internal fun isCleartextHostPermitted(host: String): Boolean {
        val normalized = host.lowercase(Locale.ROOT).removeSuffix(".")
        cleartextHostCache[normalized]?.let { return it }
        val permitted = if (isLoopbackHost(normalized)) true
        else BuildConfig.ALLOW_LAN_CLEARTEXT && isPrivateNetworkHost(normalized)
        if (cleartextHostCache.size >= MAX_HOST_POLICY_CACHE) cleartextHostCache.clear()
        cleartextHostCache[normalized] = permitted
        return permitted
    }

    private val cleartextHostCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun isLoopbackHost(normalizedHost: String): Boolean {
        if (normalizedHost == "localhost") return true
        parseIpv4(normalizedHost)?.let { octets -> return octets[0] == 127 }
        // OkHttp exposes IPv6 hosts without brackets; the compressed loopback form is
        // the only representation a parsed HttpUrl produces for ::1.
        return normalizedHost == "::1"
    }

    /**
     * Same memoization rationale as [isCleartextHostPermitted] for the image-host
     * classification that selects the `Accept` header on every poster request.
     */
    private fun isCatalogueImageHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.ROOT).removeSuffix(".")
        imageHostCache[normalized]?.let { return it }
        val isImageHost = matchesHostSuffix(normalized, RemoteHosts.catalogueImageHostSuffixes)
        if (imageHostCache.size >= MAX_HOST_POLICY_CACHE) imageHostCache.clear()
        imageHostCache[normalized] = isImageHost
        return isImageHost
    }

    private val imageHostCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun limitResponse(response: Response): Response {
        val body = response.body
        val declaredLength = body.contentLength()
        if (declaredLength > MAX_RESPONSE_BYTES) {
            response.close()
            throw IOException("Response exceeds the application safety limit")
        }
        return response.newBuilder()
            .body(LimitedResponseBody(body, MAX_RESPONSE_BYTES))
            .build()
    }

    private class LimitedResponseBody(
        private val delegate: ResponseBody,
        private val maxBytes: Long,
    ) : ResponseBody() {
        private val limitedSource: BufferedSource by lazy {
            object : ForwardingSource(delegate.source()) {
                private var totalRead = 0L

                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    val remainingWithSentinel = (maxBytes - totalRead + 1L).coerceAtLeast(1L)
                    val read = super.read(sink, minOf(byteCount, remainingWithSentinel))
                    if (read > 0L) {
                        totalRead += read
                        if (totalRead > maxBytes) {
                            delegate.close()
                            throw IOException("Response exceeds the application safety limit")
                        }
                    }
                    return read
                }
            }.buffer()
        }

        override fun contentType(): MediaType? = delegate.contentType()

        override fun contentLength(): Long = delegate.contentLength()

        override fun source(): BufferedSource = limitedSource
    }

    private fun matchesHostSuffix(host: String, suffixes: Set<String>): Boolean {
        val normalized = host.lowercase(Locale.ROOT).removeSuffix(".")
        if (normalized in suffixes) return true
        return suffixes.any { suffix ->
            normalized.length > suffix.length &&
                normalized[normalized.length - suffix.length - 1] == '.' &&
                normalized.endsWith(suffix)
        }
    }

    /** No DNS lookup is performed, avoiding a hostname-based DNS-rebinding exception. */
    internal fun isPrivateNetworkHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.ROOT).removeSuffix(".")
        if (normalized == "localhost") return true

        parseIpv4(normalized)?.let { octets ->
            return octets[0] == 10 ||
                octets[0] == 127 ||
                (octets[0] == 169 && octets[1] == 254) ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168)
        }

        if (':' !in normalized) return false
        val address = runCatching { InetAddress.getByName(normalized) }.getOrNull()
        if (address !is Inet6Address) return false
        val first = address.address[0].toInt() and 0xff
        val second = address.address[1].toInt() and 0xff
        return address.isLoopbackAddress ||
            first and 0xfe == 0xfc ||
            (first == 0xfe && second and 0xc0 == 0x80)
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        return IntArray(4) { index ->
            val part = parts[index]
            if (part.isBlank() || (part.length > 1 && part.startsWith('0'))) return null
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        }
    }
}

package io.github.flavyu22.movietorrentsearchtv.repository

import android.content.Context
import io.github.flavyu22.movietorrentsearchtv.BuildConfig
import io.github.flavyu22.movietorrentsearchtv.di.NetworkManager
import io.github.flavyu22.movietorrentsearchtv.util.readUpTo
import io.github.flavyu22.movietorrentsearchtv.model.DiscoveredServer
import io.github.flavyu22.movietorrentsearchtv.model.TorrserverStatus
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

/** Canonical validation shared by settings, discovery and playback. */
object TorrserverEndpoint {
    const val DEFAULT_PORT = 8090

    /**
     * Plain HTTP is restricted to loopback/private/link-local hosts. Public servers must
     * use HTTPS. Credentials, paths, query strings and fragments are never accepted.
     */
    fun normalize(
        untrustedAddress: String,
        allowPrivateCleartext: Boolean = BuildConfig.ALLOW_LAN_CLEARTEXT,
    ): String? {
        val trimmed = untrustedAddress.trim()
        if (trimmed.isBlank() || trimmed.length > 512 || trimmed.any(Char::isWhitespace)) {
            return null
        }

        val defaultScheme = if (allowPrivateCleartext) "http" else "https"
        val withScheme = if ("://" in trimmed) trimmed else "$defaultScheme://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in setOf("http", "https") ||
            uri.userInfo != null ||
            uri.query != null ||
            uri.fragment != null ||
            (uri.path.isNotEmpty() && uri.path != "/")
        ) return null

        val rawHost = uri.host?.removeSurrounding("[", "]") ?: return null
        val host = normalizeHost(rawHost) ?: return null
        val port = when {
            uri.port in 1..65_535 -> uri.port
            uri.port != -1 -> return null
            scheme == "https" -> 443
            else -> DEFAULT_PORT
        }

        // Loopback cleartext never leaves the device, so it stays allowed in every build
        // (on-device TorrServer). Broader private/LAN cleartext remains gated behind the
        // direct build's ALLOW_LAN_CLEARTEXT flag.
        if (scheme == "http" && !isLoopbackHost(host) &&
            (!allowPrivateCleartext || !isLocalOrPrivateHost(host))
        ) return null

        val authorityHost = if (host.contains(':')) "[$host]" else host
        val authority = when {
            scheme == "https" && port == 443 -> authorityHost
            else -> "$authorityHost:$port"
        }
        return "$scheme://$authority"
    }

    fun hostAndPort(
        normalizedAddress: String,
        allowPrivateCleartext: Boolean = BuildConfig.ALLOW_LAN_CLEARTEXT,
    ): Pair<String, Int>? {
        val normalized = normalize(normalizedAddress, allowPrivateCleartext) ?: return null
        val uri = URI(normalized)
        val port = if (uri.port != -1) uri.port else 443
        return uri.host.removeSurrounding("[", "]") to port
    }

    private fun normalizeHost(rawHost: String): String? {
        val candidate = rawHost.lowercase(Locale.ROOT).removeSuffix(".")
        if (candidate.isBlank()) return null

        if (candidate.contains(':')) {
            val address = runCatching { InetAddress.getByName(candidate) }.getOrNull()
            return if (address is Inet6Address) address.hostAddress?.substringBefore('%') else null
        }

        if (parseIpv4(candidate) != null) return candidate
        val ascii = runCatching { IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES) }
            .getOrNull()
            ?.lowercase(Locale.ROOT)
            ?: return null
        if (ascii.length > 253 || ascii.split('.').any { label ->
                label.isBlank() || label.length > 63 ||
                    label.startsWith('-') || label.endsWith('-')
            }
        ) return null
        return ascii
    }

    /** Loopback (127.0.0.0/8 and ::1) stays on the device, so cleartext there is safe. */
    private fun isLoopbackHost(host: String): Boolean {
        if (host == "localhost") return true
        parseIpv4(host)?.let { return it[0] == 127 }
        if (host.contains(':')) {
            val address = runCatching { InetAddress.getByName(host) }.getOrNull()
            if (address is Inet6Address) return address.isLoopbackAddress
        }
        return false
    }

    private fun isLocalOrPrivateHost(host: String): Boolean {
        if (host == "localhost") return true
        parseIpv4(host)?.let { octets ->
            return octets[0] == 10 ||
                octets[0] == 127 ||
                (octets[0] == 169 && octets[1] == 254) ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168)
        }
        if (host.contains(':')) {
            val address = runCatching { InetAddress.getByName(host) }.getOrNull()
            if (address is Inet6Address) {
                val first = address.address.first().toInt() and 0xff
                val second = address.address[1].toInt() and 0xff
                return address.isLoopbackAddress ||
                    first and 0xfe == 0xfc ||
                    (first == 0xfe && second and 0xc0 == 0x80)
            }
        }
        return false
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val values = IntArray(4)
        for (index in parts.indices) {
            if (parts[index].isBlank() ||
                (parts[index].length > 1 && parts[index].startsWith('0'))
            ) return null
            values[index] = parts[index].toIntOrNull()?.takeIf { it in 0..255 }
                ?: return null
        }
        return values
    }
}

class TorrserverRepository(private val context: Context) {
    private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discoveredServers = _discoveredServers.asStateFlow()
    private val scanMutex = Mutex()
    private val activeProbeCalls = ConcurrentHashMap.newKeySet<Call>()

    private val probeClient: OkHttpClient = NetworkManager.getOkHttpClient(context).newBuilder()
        .connectTimeout(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    companion object {
        const val DEFAULT_PORT = TorrserverEndpoint.DEFAULT_PORT
        const val SCAN_TIMEOUT_MS = 1_200L
        private const val TOTAL_SCAN_TIMEOUT_MS = 20_000L
        private const val MAX_CONCURRENT_SCANS = 32
        private const val MAX_PRIVATE_SUBNETS = 4
        private const val MAX_SCAN_HOSTS = 1_024
        private const val MAX_VERSION_BYTES = 64L
        private val VERSION_RESPONSE =
            Regex("(?i)^(?:matrix\\.)?\\d+(?:\\.\\d+){0,2}$")
    }

    suspend fun scanNetwork(): List<DiscoveredServer> = scanMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!BuildConfig.ALLOW_LAN_CLEARTEXT) {
                _discoveredServers.value = emptyList()
                return@withContext emptyList()
            }
            val candidates = buildScanCandidates(getLocalPrivateIpv4Addresses()).toMutableList()
            // Always include loopback for on-device TorrServer
            TorrserverEndpoint.normalize("http://127.0.0.1:$DEFAULT_PORT", true)?.let {
                candidates.add(0, ScanCandidate("127.0.0.1", it))
            }

            if (candidates.isEmpty()) {
                _discoveredServers.value = emptyList()
                return@withContext emptyList()
            }
            val semaphore = Semaphore(MAX_CONCURRENT_SCANS)
            val discovered = ConcurrentLinkedQueue<DiscoveredServer>()

            withTimeoutOrNull(TOTAL_SCAN_TIMEOUT_MS) {
                candidates.map { candidate ->
                    async {
                        semaphore.withPermit {
                            probe(candidate.address)?.let { status ->
                                discovered += DiscoveredServer(
                                    ip = candidate.ip,
                                    port = DEFAULT_PORT,
                                    name = "TorrServer ${status.version}",
                                    latencyMs = status.latencyMs,
                                    isOnline = true,
                                    address = candidate.address,
                                )
                            }
                        }
                    }
                }.awaitAll()
            }

            val results = discovered
                .distinctBy { it.address }
                .sortedBy(DiscoveredServer::latencyMs)
            _discoveredServers.value = results
            results
        }
    }

    suspend fun checkServerStatus(address: String): TorrserverStatus? =
        withContext(Dispatchers.IO) {
            val normalized = TorrserverEndpoint.normalize(address)
                ?: return@withContext null
            val result = probe(normalized) ?: return@withContext null
            TorrserverStatus(
                version = result.version,
                isRunning = true,
                torrentsCount = 0,
            )
        }

    fun validateAddress(address: String): Boolean = TorrserverEndpoint.normalize(address) != null

    fun normalizeAddress(address: String): String? = TorrserverEndpoint.normalize(address)

    private suspend fun probe(normalizedAddress: String): ProbeResult? {
        val normalized = TorrserverEndpoint.normalize(normalizedAddress) ?: return null
        val request = Request.Builder()
            .url("$normalized/echo")
            .header("Accept", "text/plain")
            .build()
        val startedAt = System.nanoTime()
        return suspendCancellableCoroutine { continuation ->
            val call = probeClient.newCall(request)
            activeProbeCalls += call
            continuation.invokeOnCancellation {
                activeProbeCalls -= call
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    activeProbeCalls -= call
                    if (!continuation.isCancelled) continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    activeProbeCalls -= call
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) return@use null
                            val body = it.body
                            if (body.contentLength() > MAX_VERSION_BYTES) return@use null
                            val bytes = body.source().readUpTo(MAX_VERSION_BYTES + 1L)
                            if (bytes.size.toLong() > MAX_VERSION_BYTES) return@use null
                            val version = bytes.toString(Charsets.UTF_8).trim()
                            if (!VERSION_RESPONSE.matches(version)) return@use null
                            ProbeResult(
                                version = version,
                                latencyMs = TimeUnit.NANOSECONDS.toMillis(
                                    System.nanoTime() - startedAt
                                ),
                            )
                        }
                    }.getOrNull()
                    if (!continuation.isCancelled) continuation.resume(result)
                }
            })
        }
    }

    private fun getLocalPrivateIpv4Addresses(): List<Inet4Address> {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { address ->
                    !address.isLoopbackAddress &&
                        TorrserverEndpoint.normalize(
                            "http://${address.hostAddress}:$DEFAULT_PORT",
                            allowPrivateCleartext = true
                        ) != null
                }
                .distinctBy { it.hostAddress }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun buildScanCandidates(addresses: List<Inet4Address>): List<ScanCandidate> = addresses
        .asSequence()
        .mapNotNull { it.hostAddress?.substringBeforeLast('.', "") }
        .filter(String::isNotBlank)
        .distinct()
        .take(MAX_PRIVATE_SUBNETS)
        .flatMap { subnet ->
            (1..254).asSequence().mapNotNull { suffix ->
                val ip = "$subnet.$suffix"
                val address = TorrserverEndpoint.normalize("http://$ip:$DEFAULT_PORT", true)
                    ?: return@mapNotNull null
                ScanCandidate(ip, address)
            }
        }
        .take(MAX_SCAN_HOSTS)
        .toList()

    fun destroy() {
        activeProbeCalls.toList().forEach(Call::cancel)
        activeProbeCalls.clear()
    }

    private data class ScanCandidate(val ip: String, val address: String)
    private data class ProbeResult(val version: String, val latencyMs: Long)
}

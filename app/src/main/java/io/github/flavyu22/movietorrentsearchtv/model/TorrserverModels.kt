package io.github.flavyu22.movietorrentsearchtv.model

data class TorrserverConfig(
    val primaryAddress: String = "",
    val discoveredServers: List<DiscoveredServer> = emptyList(),
    val isScanning: Boolean = false,
    val lastError: String? = null,
    val scanProgress: Float = 0f
)

data class DiscoveredServer(
    val ip: String,
    val port: Int,
    val name: String = "",
    val latencyMs: Long = 0,
    val isOnline: Boolean = false,
    /** Canonical endpoint, preserving HTTPS and any explicit non-default port. */
    val address: String? = null,
)

data class TorrserverStatus(
    val version: String = "",
    val isRunning: Boolean = false,
    val torrentsCount: Int = 0
)


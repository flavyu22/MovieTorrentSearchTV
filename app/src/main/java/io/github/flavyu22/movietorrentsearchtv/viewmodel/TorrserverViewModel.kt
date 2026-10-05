package io.github.flavyu22.movietorrentsearchtv.viewmodel

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.flavyu22.movietorrentsearchtv.model.DiscoveredServer
import io.github.flavyu22.movietorrentsearchtv.model.TorrserverConfig
import io.github.flavyu22.movietorrentsearchtv.repository.TorrserverEndpoint
import io.github.flavyu22.movietorrentsearchtv.repository.TorrserverRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class TorrserverViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TorrserverRepository(application)
    // This is the same preference contract consumed by MovieViewModel playback.
    private val preferences =
        application.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(TorrserverConfig())
    val config: StateFlow<TorrserverConfig> = _config

    private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers

    private val _connectionState =
        MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _manualAddress = MutableStateFlow("")
    val manualAddress: StateFlow<String> = _manualAddress

    private val _issue = MutableStateFlow<TorrserverIssue?>(null)
    val issue: StateFlow<TorrserverIssue?> = _issue

    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    private val scanGeneration = AtomicLong(0L)
    private val connectionGeneration = AtomicLong(0L)

    enum class TorrserverIssue {
        INVALID_ADDRESS,
        NO_SERVER_FOUND,
        SCAN_FAILED,
        SERVER_UNREACHABLE,
    }

    sealed interface ConnectionState {
        data object Disconnected : ConnectionState
        data class Connected(val address: String, val latency: Long) : ConnectionState
        data class Error(val address: String, val issue: TorrserverIssue) : ConnectionState
    }

    init {
        val saved = preferences.getString(ADDRESS_KEY, "").orEmpty()
        val normalized = repository.normalizeAddress(saved).orEmpty()
        if (saved.isNotBlank() && normalized.isBlank()) {
            preferences.edit {
                remove(ADDRESS_KEY)
                putLong(
                    SELECTION_REVISION_KEY,
                    preferences.getLong(SELECTION_REVISION_KEY, 0L) + 1L,
                )
            }
        }
        _config.value = TorrserverConfig(primaryAddress = normalized)
        _manualAddress.value = normalized
        viewModelScope.launch {
            repository.discoveredServers.collect { servers ->
                _discoveredServers.value = servers
            }
        }
        if (normalized.isBlank()) {
            scanNetwork()
        }
    }

    fun updateManualAddress(address: String) {
        _manualAddress.value = address.take(MAX_ADDRESS_LENGTH)
    }

    fun savePrimaryAddress(address: String): Boolean {
        val normalized = repository.normalizeAddress(address)
        if (normalized == null) {
            _issue.value = TorrserverIssue.INVALID_ADDRESS
            _config.value = _config.value.copy(lastError = null)
            return false
        }

        connectionGeneration.incrementAndGet()
        connectionJob?.cancel()
        connectionJob = null
        _connectionState.value = ConnectionState.Disconnected
        preferences.edit {
            putString(ADDRESS_KEY, normalized)
            putLong(
                SELECTION_REVISION_KEY,
                preferences.getLong(SELECTION_REVISION_KEY, 0L) + 1L,
            )
        }
        _manualAddress.value = normalized
        _config.value = _config.value.copy(primaryAddress = normalized, lastError = null)
        _issue.value = null
        return true
    }

    fun disableTorrserver() {
        connectionGeneration.incrementAndGet()
        connectionJob?.cancel()
        connectionJob = null
        preferences.edit {
            remove(ADDRESS_KEY)
            putLong(
                SELECTION_REVISION_KEY,
                preferences.getLong(SELECTION_REVISION_KEY, 0L) + 1L,
            )
        }
        _manualAddress.value = ""
        _config.value = TorrserverConfig()
        _connectionState.value = ConnectionState.Disconnected
        _issue.value = null
    }

    fun scanNetwork() {
        val generation = scanGeneration.incrementAndGet()
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _isScanning.value = true
            _config.value = _config.value.copy(scanProgress = 0f, lastError = null)
            _issue.value = null
            try {
                val servers = repository.scanNetwork()
                if (generation != scanGeneration.get()) return@launch
                _discoveredServers.value = servers
                _config.value = if (servers.isEmpty()) {
                    _issue.value = TorrserverIssue.NO_SERVER_FOUND
                    _config.value.copy(scanProgress = 1f, lastError = null)
                } else {
                    // Discovery never changes the active server. The user explicitly selects one.
                    _config.value.copy(scanProgress = 1f, lastError = null)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (generation == scanGeneration.get()) {
                    _issue.value = TorrserverIssue.SCAN_FAILED
                    _config.value = _config.value.copy(scanProgress = 0f, lastError = null)
                }
            } finally {
                if (generation == scanGeneration.get()) _isScanning.value = false
            }
        }
    }

    fun selectServer(address: String) {
        savePrimaryAddress(address)
    }

    fun saveManualAddress(address: String): Boolean {
        val normalized = repository.normalizeAddress(address)
        if (normalized == null || !savePrimaryAddress(normalized)) return false
        val (host, port) = TorrserverEndpoint.hostAndPort(normalized) ?: return false
        if (_discoveredServers.value.none { it.ip == host && it.port == port }) {
            _discoveredServers.value = _discoveredServers.value + DiscoveredServer(
                ip = host,
                port = port,
                name = "TorrServer",
                isOnline = false,
                address = normalized,
            )
        }
        return true
    }

    fun removeManualServer(address: String) {
        val normalized = repository.normalizeAddress(address) ?: return
        val endpoint = TorrserverEndpoint.hostAndPort(normalized) ?: return
        _discoveredServers.value = _discoveredServers.value.filterNot {
            it.address == normalized || (it.ip == endpoint.first && it.port == endpoint.second)
        }
    }

    fun selectDiscoveredServer(server: DiscoveredServer) {
        savePrimaryAddress(server.address ?: "http://${server.ip}:${server.port}")
    }

    fun testConnection(address: String) {
        val generation = connectionGeneration.incrementAndGet()
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            val normalized = repository.normalizeAddress(address)
            if (normalized == null) {
                if (generation != connectionGeneration.get()) return@launch
                _issue.value = TorrserverIssue.INVALID_ADDRESS
                _config.value = _config.value.copy(lastError = null)
                _connectionState.value =
                    ConnectionState.Error(address, TorrserverIssue.INVALID_ADDRESS)
                return@launch
            }

            _config.value = _config.value.copy(lastError = null)
            _issue.value = null
            _connectionState.value = ConnectionState.Disconnected
            val startedAt = System.nanoTime()
            val status = repository.checkServerStatus(normalized)
            if (generation != connectionGeneration.get()) return@launch
            if (status == null) {
                _issue.value = TorrserverIssue.SERVER_UNREACHABLE
                _config.value = _config.value.copy(lastError = null)
                _connectionState.value =
                    ConnectionState.Error(normalized, TorrserverIssue.SERVER_UNREACHABLE)
            } else {
                val latency = (System.nanoTime() - startedAt) / 1_000_000L
                _config.value = _config.value.copy(lastError = null)
                _connectionState.value = ConnectionState.Connected(normalized, latency)
            }
        }
    }

    fun clearError() {
        _config.value = _config.value.copy(lastError = null)
        _issue.value = null
        if (_connectionState.value is ConnectionState.Error) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    override fun onCleared() {
        scanJob?.cancel()
        connectionJob?.cancel()
        repository.destroy()
        // No super.onCleared(): ViewModel.onCleared() is an empty no-op, and calling it
        // only produced an "EmptySuperCall" compiler warning.
    }

    private companion object {
        const val SETTINGS_PREFS = "settings"
        const val ADDRESS_KEY = "torrserver_address"
        const val SELECTION_REVISION_KEY = "torrserver_selection_revision"
        const val MAX_ADDRESS_LENGTH = 512
    }
}

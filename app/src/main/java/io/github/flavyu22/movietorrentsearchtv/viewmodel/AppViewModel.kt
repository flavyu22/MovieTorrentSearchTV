package io.github.flavyu22.movietorrentsearchtv.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.flavyu22.movietorrentsearchtv.BuildConfig
import io.github.flavyu22.movietorrentsearchtv.api.MovieApiService
import io.github.flavyu22.movietorrentsearchtv.api.RetrofitClient
import io.github.flavyu22.movietorrentsearchtv.model.AppStrings
import io.github.flavyu22.movietorrentsearchtv.model.Translations
import io.github.flavyu22.movietorrentsearchtv.security.MagnetLinkValidator
import io.github.flavyu22.movietorrentsearchtv.security.SecurityUtils
import io.github.flavyu22.movietorrentsearchtv.security.SessionLockPolicy
import io.github.flavyu22.movietorrentsearchtv.security.UpdateManifestUrlPolicy
import io.github.flavyu22.movietorrentsearchtv.security.UpdateUrlPolicy
import io.github.flavyu22.movietorrentsearchtv.update.UpdateInstaller
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The injected context is always the Application instance (see AppViewModelFactory), never
// an Activity or a View, so this cannot leak an Activity. StaticFieldLeak is suppressed
// because the field is scoped to the ViewModel's own lifetime either way.
@SuppressLint("StaticFieldLeak")
class AppViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val authPreferences: SharedPreferences,
    private val context: Context,
) : ViewModel() {
    private val appPreferences =
        context.getSharedPreferences(APP_PREFS_FILE, Context.MODE_PRIVATE)

    private val initialLanguage = appPreferences.getString(LANGUAGE_KEY, null)
        ?.takeIf(Translations::containsKey)
        ?: DEFAULT_LANGUAGE

    // User-selected behavior: an authenticated session is persisted when login succeeds
    // and restored here on cold start, so the profile is never re-locked just because the
    // app process restarted. The persisted session is cleared only on a manual logout.
    private val _isLoggedIn = MutableStateFlow<Boolean>(
        BuildConfig.IS_BENCHMARK ||
            (SecurityUtils.isCredentialConfigured(authPreferences) &&
                appPreferences.getBoolean(LOGGED_IN_SESSION_KEY, false)),
    )
    private val _languageCode = MutableStateFlow(initialLanguage)
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    private val _pendingMagnet = MutableStateFlow<String?>(null)
    private val _magnetPlaybackRequest = MutableStateFlow<MagnetPlaybackRequest?>(null)
    private val _credentialConfigured = MutableStateFlow(
        SecurityUtils.isCredentialConfigured(authPreferences),
    )
    private val _savedUsername = MutableStateFlow(
        SecurityUtils.savedProfileName(authPreferences),
    )
    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    private val _tmdbApiKey = MutableStateFlow(
        appPreferences.getString(TMDB_API_KEY_KEY, BuildConfig.TMDB_API_KEY).orEmpty()
    )

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    private val requestIds = AtomicLong(0L)
    private var authenticationJob: Job? = null
    private var lockoutTickerJob: Job? = null
    private var backgroundLockJob: Job? = null
    private var updateCheckJob: Job? = null
    private val sessionLockPolicy = SessionLockPolicy(SESSION_LOCK_DELAY_MS)
    private var lastAcceptedMagnet: String? = null
    private var lastAcceptedMagnetAtMs = Long.MIN_VALUE
    // True while a movie has been handed off to an external player. Two minutes after
    // playback begins the application exits completely (see playbackExitJob), as
    // requested. Returning to the app before then cancels that exit.
    private var playbackActive = false
    private var playbackExitJob: Job? = null

    private val apiService: MovieApiService by lazy { RetrofitClient.getInstance(context) }

    init {
        // Remove the older unsafe persistent session key written by versions <= 1.9.2.
        // The current LOGGED_IN_SESSION_KEY is left intact and restored above so a cold
        // start does not re-lock the profile; it is cleared when the user logs out.
        appPreferences.edit { remove(LEGACY_LOGGED_IN_KEY) }
        resumeLockoutTickerIfNeeded()
        checkForUpdates()
    }

    val isReady: Boolean
        get() = Translations.containsKey(_languageCode.value)

    val appState: StateFlow<AppUiState> = combine(
        listOf(
            _isLoggedIn,
            _languageCode,
            _updateState,
            _playbackState,
            _pendingMagnet,
            _magnetPlaybackRequest,
            _credentialConfigured,
            _savedUsername,
            _authState,
            _tmdbApiKey,
        ),
    ) { values ->
        val language = values[1] as String
        AppUiState(
            isLoggedIn = values[0] as Boolean,
            languageCode = language,
            strings = Translations[language] ?: Translations.getValue(DEFAULT_LANGUAGE),
            updateState = values[2] as UpdateState,
            playbackState = values[3] as PlaybackState,
            pendingMagnet = values[4] as String?,
            magnetPlaybackRequest = values[5] as MagnetPlaybackRequest?,
            isCredentialConfigured = values[6] as Boolean,
            savedUsername = values[7] as String,
            authState = values[8] as AuthState,
            tmdbApiKey = values[9] as String,
        )
    }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppUiState(
                isLoggedIn = _isLoggedIn.value,
                languageCode = initialLanguage,
                strings = Translations[initialLanguage]
                    ?: Translations.getValue(DEFAULT_LANGUAGE),
                updateState = _updateState.value,
                playbackState = _playbackState.value,
                pendingMagnet = _pendingMagnet.value,
                magnetPlaybackRequest = _magnetPlaybackRequest.value,
                isCredentialConfigured = _credentialConfigured.value,
                savedUsername = _savedUsername.value,
                authState = _authState.value,
                tmdbApiKey = _tmdbApiKey.value,
            ),
        )

    /** Configures the first local credential or verifies the existing credential. */
    fun submitCredential(profileName: String, secret: String) {
        if (_authState.value is AuthState.Authenticating) return
        val remaining = SecurityUtils.remainingLockoutMs(authPreferences)
        if (remaining > 0L) {
            showLockout(remaining)
            return
        }

        authenticationJob?.cancel()
        authenticationJob = viewModelScope.launch {
            _authState.value = AuthState.Authenticating
            val result = withContext(Dispatchers.Default) {
                SecurityUtils.authenticateOrCreate(authPreferences, profileName, secret)
            }
            if (!isActive) return@launch

            when (result) {
                is SecurityUtils.AuthenticationResult.Success -> {
                    _credentialConfigured.value = true
                    _savedUsername.value = SecurityUtils.savedProfileName(authPreferences)
                    _isLoggedIn.value = true
                    // Persist the authenticated session so a cold-start restart does not
                    // ask for the credential again. Only a manual logout clears it.
                    appPreferences.edit { putBoolean(LOGGED_IN_SESSION_KEY, true) }
                    sessionLockPolicy.clear()
                    _authState.value = AuthState.Idle
                }
                is SecurityUtils.AuthenticationResult.InvalidCredential -> {
                    if (result.retryAfterMs > 0L) showLockout(result.retryAfterMs)
                    else _authState.value = AuthState.InvalidCredential
                }
                is SecurityUtils.AuthenticationResult.Locked -> showLockout(result.retryAfterMs)
                SecurityUtils.AuthenticationResult.InvalidInput ->
                    _authState.value = AuthState.InvalidInput
                SecurityUtils.AuthenticationResult.CorruptConfiguration ->
                    _authState.value = AuthState.StorageError
            }
        }
    }

    // Kept as a source-compatible alias for callers compiled against the previous API.
    fun login(username: String, password: String = "") = submitCredential(username, password)

    fun clearAuthFeedback() {
        if (_authState.value !is AuthState.Authenticating &&
            _authState.value !is AuthState.Locked
        ) {
            _authState.value = AuthState.Idle
        }
    }

    fun resetCorruptCredential() {
        if (_authState.value !is AuthState.StorageError) return
        authenticationJob?.cancel()
        authenticationJob = null
        val reset = SecurityUtils.resetLocalCredential(authPreferences)
        if (reset) {
            clearPersistedSession()
            _credentialConfigured.value = false
            _savedUsername.value = ""
            _isLoggedIn.value = false
            _authState.value = AuthState.Idle
        } else {
            _authState.value = AuthState.StorageError
        }
    }

    fun logout() {
        backgroundLockJob?.cancel()
        backgroundLockJob = null
        sessionLockPolicy.clear()
        clearPersistedSession()
        lockSession()
    }

    fun setLanguage(code: String) {
        if (!Translations.containsKey(code)) return
        _languageCode.value = code
        savedStateHandle[LANGUAGE_KEY] = code
        appPreferences.edit { putString(LANGUAGE_KEY, code) }
    }

    fun saveTmdbApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.length > 128) return
        _tmdbApiKey.value = trimmed
        appPreferences.edit { putString(TMDB_API_KEY_KEY, trimmed) }
    }

    fun dismissUpdate() {
        _updateState.value = UpdateState.Dismissed
    }

    fun reportUpdateLaunchError() {
        _updateState.value = UpdateState.Error
    }

    fun installAvailableUpdate() {
        if (!BuildConfig.ENABLE_SELF_UPDATE) return
        val available = _updateState.value as? UpdateState.Available ?: return
        _updateState.value = UpdateState.Downloading
        viewModelScope.launch {
            _updateState.value = when (
                UpdateInstaller.downloadVerifyAndLaunch(
                    context = context,
                    apkUrl = available.apkUrl,
                    expectedSha256 = available.sha256,
                    expectedSizeBytes = available.sizeBytes,
                )
            ) {
                UpdateInstaller.Result.InstallerLaunched -> UpdateState.Dismissed
                else -> UpdateState.Error
            }
        }
    }

    fun onPlaybackTriggered() {
        // A movie is being handed off to an external player. Per the requested behavior,
        // the application closes itself completely exactly two minutes after playback
        // starts, even if the external player is still running, so CPU/memory and the
        // network are freed for other applications. Returning to the app before then
        // cancels the exit.
        playbackActive = true
        backgroundLockJob?.cancel()
        backgroundLockJob = null
        playbackExitJob?.cancel()
        playbackExitJob = if (BuildConfig.IS_BENCHMARK) {
            null
        } else {
            viewModelScope.launch {
                delay(PLAYBACK_EXIT_DELAY_MS)
                if (playbackActive && _playbackState.value is PlaybackState.Backgrounded) {
                    _uiEvents.emit(UiEvent.ExitApp)
                }
            }
        }
    }

    fun onAppForegrounded() {
        _playbackState.value = PlaybackState.Foregrounded
        backgroundLockJob?.cancel()
        backgroundLockJob = null
        // Returning to the app (the external movie ended or the user came back) cancels
        // the two-minute playback auto-exit and is not a lock event: the profile stays
        // unlocked and the app does not terminate while the user is in it.
        val returningFromPlayback = playbackActive
        playbackActive = false
        playbackExitJob?.cancel()
        playbackExitJob = null
        // Closes the race between the two-minute background exit job and a return just
        // after the timeout: coming back after the lock window always requires the local
        // credential again, even while the process stayed alive.
        val lockExpired = !BuildConfig.IS_BENCHMARK &&
            _isLoggedIn.value &&
            !returningFromPlayback &&
            sessionLockPolicy.shouldLockOnForeground(SystemClock.elapsedRealtime())
        sessionLockPolicy.clear()
        if (lockExpired) {
            lockSession()
        }
    }

    fun onAppBackgrounded() {
        _playbackState.value = PlaybackState.Backgrounded
        // External requests never survive leaving the app.
        _pendingMagnet.value = null
        _magnetPlaybackRequest.value = null
        sessionLockPolicy.onBackgrounded(SystemClock.elapsedRealtime())
        backgroundLockJob?.cancel()
        // During playback the dedicated two-minute auto-exit (started when playback
        // began) already terminates the app, so do not schedule a second background timer.
        if (_isLoggedIn.value && !BuildConfig.IS_BENCHMARK && !playbackActive) {
            backgroundLockJob = viewModelScope.launch {
                delay(SESSION_LOCK_DELAY_MS)
                if (_playbackState.value is PlaybackState.Backgrounded) {
                    _uiEvents.emit(UiEvent.ExitApp)
                }
            }
        }
    }

    /**
     * Accepts a validated external magnet into a confirmation queue. Rapid duplicate
     * intents are rejected, and no playback begins merely because an intent was received.
     */
    @Synchronized
    fun setPendingMagnet(untrustedMagnet: String): Boolean {
        val magnet = MagnetLinkValidator.validate(untrustedMagnet) ?: return false
        val now = SystemClock.elapsedRealtime()
        val sinceLast = if (lastAcceptedMagnetAtMs == Long.MIN_VALUE) {
            Long.MAX_VALUE
        } else {
            now - lastAcceptedMagnetAtMs
        }
        if (sinceLast < EXTERNAL_MAGNET_RATE_LIMIT_MS ||
            (magnet == lastAcceptedMagnet && sinceLast < DUPLICATE_MAGNET_WINDOW_MS)
        ) return false

        lastAcceptedMagnet = magnet
        lastAcceptedMagnetAtMs = now
        _pendingMagnet.value = magnet
        return true
    }

    fun confirmPendingMagnet() {
        if (!_isLoggedIn.value) return
        val magnet = _pendingMagnet.value ?: return
        _pendingMagnet.value = null
        _magnetPlaybackRequest.value = MagnetPlaybackRequest(
            id = requestIds.incrementAndGet(),
            magnet = magnet,
        )
    }

    fun dismissPendingMagnet() {
        _pendingMagnet.value = null
    }

    fun consumePendingMagnet(): String? = _pendingMagnet.value.also {
        _pendingMagnet.value = null
    }

    fun completeMagnetPlayback(requestId: Long) {
        if (_magnetPlaybackRequest.value?.id == requestId) {
            _magnetPlaybackRequest.value = null
        }
    }

    @Synchronized
    private fun checkForUpdates() {
        if (!BuildConfig.ENABLE_SELF_UPDATE) {
            _updateState.value = UpdateState.UpToDate
            return
        }
        if (updateCheckJob?.isActive == true || _updateState.value != UpdateState.Idle) return
        val configuredUrl = io.github.flavyu22.movietorrentsearchtv.BuildConfig.UPDATE_MANIFEST_URL
        if (configuredUrl.isEmpty()) {
            _updateState.value = UpdateState.UpToDate
            return
        }
        val manifestUrl = UpdateManifestUrlPolicy.validate(configuredUrl)
        if (manifestUrl == null) {
            _updateState.value = UpdateState.Error
            return
        }
        _updateState.value = UpdateState.Checking
        updateCheckJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val updateInfo = apiService.checkForUpdates(manifestUrl)
                if (updateInfo.versionCode <= 0 ||
                    updateInfo.versionName.length !in 1..64 ||
                    updateInfo.versionName.any(Char::isISOControl) ||
                    updateInfo.releaseNotes.length > MAX_RELEASE_NOTES_LENGTH ||
                    !SHA256.matches(updateInfo.sha256) ||
                    updateInfo.sizeBytes !in 1..MAX_UPDATE_APK_BYTES
                ) {
                    _updateState.value = UpdateState.Error
                    return@launch
                }
                if (updateInfo.versionCode >
                    io.github.flavyu22.movietorrentsearchtv.util.Constants.BUILD_NUMBER
                ) {
                    val safeUrl = UpdateUrlPolicy.validate(
                        updateInfo.apkUrl,
                        io.github.flavyu22.movietorrentsearchtv.BuildConfig.UPDATE_REPOSITORY,
                    )
                    _updateState.value = if (safeUrl != null) {
                        UpdateState.Available(safeUrl, updateInfo.sha256, updateInfo.sizeBytes)
                    } else {
                        UpdateState.Error
                    }
                } else {
                    _updateState.value = UpdateState.UpToDate
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _updateState.value = UpdateState.Error
            } finally {
                updateCheckJob = null
            }
        }
    }

    private fun lockSession() {
        authenticationJob?.cancel()
        authenticationJob = null
        playbackExitJob?.cancel()
        playbackExitJob = null
        _isLoggedIn.value = false
        sessionLockPolicy.clear()
        _authState.value = AuthState.Idle
        _pendingMagnet.value = null
        _magnetPlaybackRequest.value = null
        lastAcceptedMagnet = null
        lastAcceptedMagnetAtMs = Long.MIN_VALUE
    }

    /** Removes the persisted session so the next cold start re-locks the profile. */
    private fun clearPersistedSession() {
        appPreferences.edit { remove(LOGGED_IN_SESSION_KEY) }
    }

    private fun resumeLockoutTickerIfNeeded() {
        val remaining = SecurityUtils.remainingLockoutMs(authPreferences)
        if (remaining > 0L) showLockout(remaining)
    }

    private fun showLockout(initialRemainingMs: Long) {
        lockoutTickerJob?.cancel()
        _authState.value = AuthState.Locked(millisecondsToSeconds(initialRemainingMs))
        lockoutTickerJob = viewModelScope.launch {
            while (isActive) {
                val remaining = SecurityUtils.remainingLockoutMs(authPreferences)
                if (remaining <= 0L) {
                    _authState.value = AuthState.Idle
                    return@launch
                }
                _authState.value = AuthState.Locked(millisecondsToSeconds(remaining))
                delay(250L.coerceAtMost(remaining))
            }
        }
    }

    private fun millisecondsToSeconds(value: Long): Long =
        ((value + 999L) / 1_000L).coerceAtLeast(1L)

    data class AppUiState(
        val isLoggedIn: Boolean = false,
        val languageCode: String = DEFAULT_LANGUAGE,
        val strings: AppStrings = Translations.getValue(DEFAULT_LANGUAGE),
        val updateState: UpdateState = UpdateState.Idle,
        val playbackState: PlaybackState = PlaybackState.Idle,
        val savedUsername: String = "",
        val pendingMagnet: String? = null,
        val magnetPlaybackRequest: MagnetPlaybackRequest? = null,
        val isCredentialConfigured: Boolean = false,
        val authState: AuthState = AuthState.Idle,
        val tmdbApiKey: String = "",
    )

    data class MagnetPlaybackRequest(val id: Long, val magnet: String)

    sealed interface AuthState {
        data object Idle : AuthState
        data object Authenticating : AuthState
        data object InvalidCredential : AuthState
        data object InvalidInput : AuthState
        data object StorageError : AuthState
        data class Locked(val remainingSeconds: Long) : AuthState
    }

    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data object UpToDate : UpdateState
        data object Dismissed : UpdateState
        data class Available(
            val apkUrl: String,
            val sha256: String,
            val sizeBytes: Long,
        ) : UpdateState
        data object Downloading : UpdateState
        data object Error : UpdateState
    }

    sealed interface PlaybackState {
        data object Idle : PlaybackState
        data object Foregrounded : PlaybackState
        data object Backgrounded : PlaybackState
    }

    sealed interface UiEvent {
        data object ShowExitDialog : UiEvent
        data object ShowLogoutDialog : UiEvent
        data object ExitApp : UiEvent
    }

    companion object {
        private const val APP_PREFS_FILE = "app_prefs"
        private const val LANGUAGE_KEY = "app_language"
        private const val LEGACY_LOGGED_IN_KEY = "is_logged_in"
        private const val LOGGED_IN_SESSION_KEY = "logged_in_session"
        private const val LOCK_ON_IDLE_KEY = "pref_session_lock_on_idle"
        private const val DEFAULT_LANGUAGE = "EN"
        private const val TMDB_API_KEY_KEY = "tmdb_api_key"
        private const val EXTERNAL_MAGNET_RATE_LIMIT_MS = 1_500L
        private const val DUPLICATE_MAGNET_WINDOW_MS = 10_000L
        private const val SESSION_LOCK_DELAY_MS = 120_000L
        // Two minutes of external playback, then the app closes itself to free
        // resources for other applications (see onPlaybackTriggered).
        private const val PLAYBACK_EXIT_DELAY_MS = 120_000L
        private const val MAX_RELEASE_NOTES_LENGTH = 20_000
        private const val MAX_UPDATE_APK_BYTES = 250L * 1024L * 1024L
        private val SHA256 = Regex("[a-fA-F0-9]{64}")
    }
}

class AppViewModelFactory(
    private val authPreferences: SharedPreferences,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        require(modelClass.isAssignableFrom(AppViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        val savedStateHandle = extras.createSavedStateHandle()
        val application = extras[APPLICATION_KEY]
            ?: throw IllegalArgumentException("Missing Application")
        @Suppress("UNCHECKED_CAST")
        return AppViewModel(savedStateHandle, authPreferences, application) as T
    }
}

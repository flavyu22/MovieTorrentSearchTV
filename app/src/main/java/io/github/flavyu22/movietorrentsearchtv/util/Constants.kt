package io.github.flavyu22.movietorrentsearchtv.util

import io.github.flavyu22.movietorrentsearchtv.BuildConfig
import io.github.flavyu22.movietorrentsearchtv.config.RemoteHosts

/**
 * Ultra-Professional Global Constants
 */
object Constants {
    val APP_VERSION: String = BuildConfig.VERSION_NAME
    val BUILD_NUMBER: Int = BuildConfig.VERSION_CODE

    // Injected by Gradle or configured by the user via SharedPreferences.
    // TMDB_CONFIGURED is now handled by the ViewModels to support dynamic keys.
    val TMDB_API_KEY: String = BuildConfig.TMDB_API_KEY
    
    // Base URLs
    const val YTS_BASE_URL = RemoteHosts.YTS_API_BASE_URL
    const val TMDB_BASE_URL = RemoteHosts.TMDB_API_BASE_URL
    
    // Timeouts
    const val NETWORK_TIMEOUT_SECONDS = 30L
    const val SEARCH_THROTTLE_MS = 300L
}

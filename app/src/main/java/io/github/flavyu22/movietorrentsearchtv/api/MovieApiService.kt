package io.github.flavyu22.movietorrentsearchtv.api

import io.github.flavyu22.movietorrentsearchtv.model.*
import io.github.flavyu22.movietorrentsearchtv.config.RemoteHosts
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface MovieApiService {
    // --- UPDATE SYSTEM ---
    @GET
    suspend fun checkForUpdates(@Url manifestUrl: String): UpdateInfo

    // --- YTS (FILME) ---
    @GET
    suspend fun searchMovies(
        @Url url: String,
        @Query("query_term") query: String? = null,
        @Query("genre") genre: String? = null,
        @Query("quality") quality: String? = null,
        @Query("minimum_rating") minimumRating: Int? = null,
        @Query("sort_by") sortBy: String = "year",
        @Query("order_by") orderBy: String = "desc",
        @Query("limit") limit: Int = 49,
        @Query("page") page: Int = 1
    ): YtsResponse

    // --- Pirate Bay (Aggregator) ---
    @GET("${RemoteHosts.PIRATE_BAY_API_URL}/q.php")
    suspend fun searchPirateBay(@Query("q") query: String): List<PirateBayTorrent>

    @GET("${RemoteHosts.TMDB_API_BASE_URL}search/movie")
    suspend fun searchTmdbMovies(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("page") page: Int = 1,
        @Query("primary_release_year") year: String? = null,
        @Query("language") language: String = "en-US"
    ): TmdbSeriesResponse

    @GET("${RemoteHosts.TMDB_API_BASE_URL}discover/movie")
    suspend fun discoverTmdbMovies(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1,
        @Query("primary_release_year") year: String? = null,
        @Query("with_genres") genre: String? = null,
        @Query("vote_average.gte") voteAverageGte: Float? = null,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("primary_release_date.lte") releaseDateLte: String? = null,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("language") language: String = "en-US"
    ): TmdbSeriesResponse

    // --- TMDB (SERIALE - Info & Postere de inalta calitate) ---
    @GET("${RemoteHosts.TMDB_API_BASE_URL}search/tv")
    suspend fun searchTmdbSeries(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("page") page: Int = 1,
        @Query("first_air_date_year") year: String? = null,
        @Query("language") language: String = "en-US"
    ): TmdbSeriesResponse

    @GET("${RemoteHosts.TMDB_API_BASE_URL}discover/tv")
    suspend fun discoverTmdbSeries(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1,
        @Query("first_air_date_year") year: String? = null,
        @Query("with_genres") genre: String? = null,
        @Query("vote_average.gte") voteAverageGte: Float? = null,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("first_air_date.lte") dateLte: String? = null,
        @Query("sort_by") sortBy: String = "first_air_date.desc",
        @Query("language") language: String = "en-US"
    ): TmdbSeriesResponse

    @GET
    suspend fun getPopularTmdbSeries(
        @Url url: String,
        @Query("page") page: Int = 1,
        @Query("language") language: String = "en-US"
    ): TmdbSeriesResponse

    @GET
    suspend fun getPopularTmdbMovies(
        @Url url: String,
        @Query("page") page: Int = 1,
        @Query("language") language: String = "en-US"
    ): TmdbSeriesResponse

    @GET("${RemoteHosts.TMDB_API_BASE_URL}{mediaType}/{tmdbId}/external_ids")
    suspend fun getTmdbExternalIds(
        @Path("mediaType") mediaType: String,
        @Path("tmdbId") tmdbId: Long,
        @Query("api_key") apiKey: String,
    ): TmdbExternalIds
}

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val releaseNotes: String,
)

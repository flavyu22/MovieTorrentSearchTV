package com.example.movietorrentsearchtv.api

import com.example.movietorrentsearchtv.model.*
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

interface MovieApiService {
    // --- YTS (FILME) ---
    @GET("list_movies.json")
    suspend fun searchMovies(
        @Query("query_term") query: String,
        @Query("genre") genre: String? = null,
        @Query("quality") quality: String? = null,
        @Query("sort_by") sortBy: String = "year",
        @Query("order_by") orderBy: String = "desc",
        @Query("limit") limit: Int = 49,
        @Query("page") page: Int = 1
    ): YtsResponse

    // --- EZTV (SERIALE - Torrents) ---
    @GET("https://eztv.re/api/get-torrents")
    suspend fun getTorrentsByImdb(
        @Query("imdb_id") imdbId: String,
        @Query("limit") limit: Int = 100
    ): EztvResponse

    // --- Pirate Bay (Aggregator) ---
    @GET("https://apibay.org/q.php")
    suspend fun searchPirateBay(@Query("q") query: String): List<PirateBayTorrent>

    // --- TMDB (SERIALE - Info & Postere de inalta calitate) ---
    @GET("https://api.themoviedb.org/3/search/tv")
    suspend fun searchTmdbSeries(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("first_air_date_year") year: String? = null,
        @Query("language") language: String = "en-US"
    ): TmdbSeriesResponse

    @GET("https://api.themoviedb.org/3/discover/tv")
    suspend fun discoverTmdbSeries(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1,
        @Query("first_air_date_year") year: String? = null,
        @Query("with_genres") genre: String? = null,
        @Query("first_air_date.lte") dateLte: String? = null,
        @Query("sort_by") sortBy: String = "first_air_date.desc",
        @Query("language") language: String = "en-US"
    ): TmdbSeriesResponse

    @GET("https://api.themoviedb.org/3/tv/popular")
    suspend fun getPopularTmdbSeries(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1,
        @Query("language") language: String = "en-US"
    ): TmdbSeriesResponse
}

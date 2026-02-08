package com.example.movietorrentsearchtv.model

import com.google.gson.annotations.SerializedName

data class Movie(
    val id: Long,
    val title: String?,
    @SerializedName("imdb_code") val imdbCode: String?,
    @SerializedName("small_cover_image") val posterPath: String?,
    @SerializedName("medium_cover_image") val mediumPosterPath: String?,
    val year: Int?,
    val ytTrailerCode: String?,
    val genres: List<String>?,
    val torrents: List<Torrent>?
)

data class Torrent(
    val url: String?,
    val hash: String?,
    val quality: String?,
    val type: String?,
    val size: String?,
    val seeds: Int?,
    val peers: Int?
)

data class YtsResponse(
    val data: YtsData?
)

data class YtsData(
    val movies: List<Movie>?
)

data class YtsDetailResponse(
    val data: YtsDetailData?
)

data class YtsDetailData(
    val movie: Movie
)

// --- Structuri pentru TV SHOWS (EZTV API) ---

data class EztvResponse(
    @SerializedName("torrents") val torrents: List<EztvTorrent>?
)

data class EztvTorrent(
    val id: Long,
    @SerializedName("title") val title: String?,
    @SerializedName("magnet_url") val magnetUrl: String?,
    @SerializedName("small_screenshot") val posterPath: String?,
    @SerializedName("large_screenshot") val largePosterPath: String?,
    @SerializedName("seeds") val seeds: Int?,
    @SerializedName("peers") val peers: Int?,
    @SerializedName("size_bytes") val size: Any?,
    @SerializedName("filename") val fileName: String?,
    @SerializedName("episode") val episode: String?,
    @SerializedName("season") val season: String?
)

// --- Pirate Bay Models ---
data class PirateBayTorrent(
    val id: String,
    val name: String?,
    val info_hash: String?,
    val seeders: String?,
    val leechers: String?,
    val size: String?,
    val category: String?
)

// --- TMDB Models (Pentru postere de inalta calitate) ---
data class TmdbSeriesResponse(
    val results: List<TmdbSeries>?
)

data class TmdbSeries(
    val id: Long,
    val name: String?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("first_air_date") val firstAirDate: String?,
    @SerializedName("vote_average") val voteAverage: Double?,
    @SerializedName("overview") val overview: String?,
    @SerializedName("genre_ids") val genreIds: List<Int>?
)

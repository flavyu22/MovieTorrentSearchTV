package io.github.flavyu22.movietorrentsearchtv.model

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

@Stable
data class Movie(
    val id: Long,
    val title: String?,
    @SerializedName("original_title", alternate = ["original_name"]) val originalTitle: String? = null,
    /**
     * Localized title as returned by TMDB for the app's currently selected language.
     * For example, when the app language is Spanish TMDB returns the Spanish title
     * ("El Señor de los Anillos") here. It is used only when searching the magnet-link
     * providers and when matching their results; it is never shown in the UI.
     */
    val localizedTitle: String? = null,
    @SerializedName("imdb_code") val imdbCode: String?,
    @SerializedName("small_cover_image") val posterPath: String?,
    @SerializedName("medium_cover_image") val mediumPosterPath: String?,
    @SerializedName("large_cover_image") val largePosterPath: String? = null,
    val year: Int?,
    @SerializedName("yt_trailer_code") val ytTrailerCode: String?,
    val genres: List<String>?,
    val rating: Double? = null,
    val torrents: List<Torrent>?,
    val isSeries: Boolean = false,
    /** Original TMDB identifier. `id` is namespaced to avoid collisions with YTS ids. */
    val tmdbId: Long? = null,
    /** Canonical public details page returned by YTS, when available. */
    val url: String? = null
)

@Immutable
data class Torrent(
    val url: String?,
    val hash: String?,
    val quality: String?,
    val type: String?,
    val size: String?,
    val seeds: Int?,
    val peers: Int?,
    val seasonEpisode: String? = null
)

@Immutable
data class YtsResponse(
    val status: String?,
    @SerializedName("status_message") val statusMessage: String?,
    val data: YtsData?
)

@Immutable
data class YtsData(
    val movies: List<Movie>?
)

@Immutable
data class YtsDetailResponse(
    val data: YtsDetailData?
)

@Immutable
data class YtsDetailData(
    val movie: Movie
)

// --- Structuri pentru TV SHOWS (EZTV API) ---

@Immutable
data class EztvResponse(
    @SerializedName("torrents") val torrents: List<EztvTorrent>?
)

@Immutable
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
    @SerializedName("season") val season: String?,
    @SerializedName("torrent_url") val torrentUrl: String? = null,
    @SerializedName("episode_url") val episodeUrl: String? = null
)

// --- Pirate Bay Models ---
@Immutable
data class PirateBayTorrent(
    val id: String,
    val name: String?,
    val info_hash: String?,
    val seeders: String?,
    val leechers: String?,
    val size: String?,
    val category: String?,
    /** IMDb identifier attached by The Pirate Bay when known (e.g. "tt5615840"). */
    val imdb: String? = null,
)

// --- TMDB Models (Pentru postere de inalta calitate) ---
@Immutable
data class TmdbSeriesResponse(
    val results: List<TmdbSeries>?
)

@Immutable
data class TmdbSeries(
    val id: Long,
    @SerializedName("name", alternate = ["title"]) val name: String?,
    @SerializedName("original_name", alternate = ["original_title"]) val originalName: String?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("first_air_date", alternate = ["release_date"]) val firstAirDate: String?,
    @SerializedName("vote_average") val voteAverage: Double?,
    @SerializedName("overview") val overview: String?,
    @SerializedName("genre_ids") val genreIds: List<Int>?
)

@Immutable
data class TmdbExternalIds(
    @SerializedName("imdb_id") val imdbId: String?
)

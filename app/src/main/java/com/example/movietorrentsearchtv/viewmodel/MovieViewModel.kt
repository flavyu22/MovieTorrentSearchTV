package com.example.movietorrentsearchtv.viewmodel

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.movietorrentsearchtv.api.RetrofitClient
import com.example.movietorrentsearchtv.model.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class MovieViewModel(application: Application) : AndroidViewModel(application) {
    private val _movies = mutableStateOf<List<Movie>>(emptyList())
    val movies: State<List<Movie>> = _movies

    private val _isLoading = mutableStateOf(false)
    val isLoading: State<Boolean> = _isLoading

    private val _currentPage = mutableStateOf(1)
    val currentPage: State<Int> = _currentPage

    private val _isSeriesMode = mutableStateOf(false)
    val isSeriesMode: State<Boolean> = _isSeriesMode

    val selectedGenre = mutableStateOf("All")
    val selectedYear = mutableStateOf("All")
    val selectedQuality = mutableStateOf("All")
    val searchQuery = mutableStateOf("")
    val lastClickedMovieId = mutableStateOf<Long?>(null)

    private val PAGE_LIMIT_SERIES = 20
    private val PAGE_LIMIT_MOVIES = 49
    
    private val TMDB_API_KEY = "5bc758ad2ae90c67d2361f3d63024357"
    private val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w342"
    
    private val apiService by lazy { RetrofitClient.getInstance(application) }
    private var searchJob: Job? = null

    private val TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://open.stealth.si:80/announce"
    ).joinToString("") { "&tr=${java.net.URLEncoder.encode(it, "UTF-8")}" }

    fun searchMovies(query: String, genre: String? = null, year: String? = null, quality: String? = null, page: Int = 1, isSeries: Boolean = _isSeriesMode.value) {
        searchQuery.value = query
        selectedGenre.value = genre ?: "All"
        selectedYear.value = year ?: "All"
        selectedQuality.value = quality ?: "All"
        _isSeriesMode.value = isSeries
        _currentPage.value = page
        executeSearch()
    }

    fun loadPopularMovies(genre: String? = null, year: String? = null, quality: String? = null, page: Int = 1, isSeries: Boolean = _isSeriesMode.value) {
        searchQuery.value = ""
        selectedGenre.value = genre ?: "All"
        selectedYear.value = year ?: "All"
        selectedQuality.value = quality ?: "All"
        _isSeriesMode.value = isSeries
        _currentPage.value = page
        executeSearch()
    }

    private fun executeSearch() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { _isLoading.value = true }
                
                val genreParam = if (selectedGenre.value == "All") null else selectedGenre.value
                val yearParam = if (selectedYear.value == "All") null else selectedYear.value

                if (_isSeriesMode.value) {
                    val results = try {
                        val response = when {
                            searchQuery.value.isNotEmpty() -> 
                                apiService.searchTmdbSeries(TMDB_API_KEY, searchQuery.value)
                            genreParam != null -> 
                                apiService.discoverTmdbSeries(TMDB_API_KEY, _currentPage.value, yearParam, mapGenreToTmdbId(genreParam))
                            else -> 
                                apiService.getPopularTmdbSeries(TMDB_API_KEY, _currentPage.value)
                        }
                        response.results
                    } catch (e: Exception) {
                        apiService.getPopularTmdbSeries(TMDB_API_KEY, _currentPage.value).results
                    }
                    
                    val mapped = (results ?: emptyList()).map { mapTmdbToMovie(it) }.distinctBy { it.id }
                    withContext(Dispatchers.Main) { _movies.value = mapped }
                } else {
                    val ytsQuery = if (yearParam != null && searchQuery.value.isEmpty()) yearParam 
                                  else if (yearParam != null) "${searchQuery.value} $yearParam" 
                                  else searchQuery.value

                    val response = try {
                        apiService.searchMovies(
                            query = ytsQuery ?: "",
                            genre = genreParam?.lowercase(),
                            quality = if (selectedQuality.value == "All") null else selectedQuality.value,
                            limit = PAGE_LIMIT_MOVIES,
                            page = _currentPage.value,
                            sortBy = "date_added", 
                            orderBy = "desc"
                        )
                    } catch (e: Exception) {
                        null
                    }
                    
                    val movieList = response?.data?.movies ?: emptyList()
                    withContext(Dispatchers.Main) { _movies.value = movieList.distinctBy { it.id } }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _movies.value = emptyList() }
            } finally {
                withContext(Dispatchers.Main) { _isLoading.value = false }
            }
        }
    }

    private fun mapGenreToTmdbId(genre: String?): String? {
        return when (genre) {
            "Action" -> "10759"
            "Adventure" -> "10759"
            "Animation" -> "16"
            "Comedy" -> "35"
            "Crime" -> "80"
            "Documentary" -> "99"
            "Drama" -> "18"
            "Family" -> "10751"
            "Fantasy" -> "10765"
            "History" -> "36"
            "Horror" -> "27"
            "Music" -> "10402"
            "Mystery" -> "9648"
            "Romance" -> "10749"
            "Sci-Fi" -> "10765"
            "Thriller" -> "53"
            "War" -> "10768"
            "Western" -> "37"
            else -> null
        }
    }

    private fun mapTmdbIdToName(id: Int): String {
        return when (id) {
            10759 -> "Action & Adventure"
            16 -> "Animation"
            35 -> "Comedy"
            80 -> "Crime"
            99 -> "Documentary"
            18 -> "Drama"
            10751 -> "Family"
            9648 -> "Mystery"
            10765 -> "Sci-Fi & Fantasy"
            10768 -> "War & Politics"
            37 -> "Western"
            else -> "TV"
        }
    }

    suspend fun fetchTorrentsForMovie(movie: Movie): List<Torrent> {
        return withContext(Dispatchers.IO) {
            val results = Collections.synchronizedList(mutableListOf<Torrent>())
            
            movie.torrents?.forEach {
                if (!it.hash.isNullOrBlank()) {
                    val magnetUrl = "magnet:?xt=urn:btih:${it.hash}&dn=${java.net.URLEncoder.encode(movie.title ?: "movie", "UTF-8")}$TRACKERS"
                    results.add(it.copy(url = magnetUrl))
                }
            }

            val eztvTask = async {
                if (movie.imdbCode != null) {
                    try {
                        val numericId = movie.imdbCode!!.replace("tt", "")
                        apiService.getTorrentsByImdb(numericId).torrents?.map { mapEztvTorrent(it) } ?: emptyList()
                    } catch (e: Exception) { emptyList() }
                } else emptyList()
            }

            val globalTask = async {
                try {
                    val baseQuery = movie.imdbCode ?: movie.title ?: ""
                    if (baseQuery.isBlank()) return@async emptyList<Torrent>()
                    apiService.searchPirateBay(baseQuery).mapNotNull { mapPbTorrent(it) }
                } catch (e: Exception) { emptyList() }
            }

            results.addAll(eztvTask.await())
            results.addAll(globalTask.await())
            
            results.toList()
                .distinctBy { it.hash?.lowercase() }
                .sortedWith(compareByDescending<Torrent> { it.seeds ?: 0 }.thenByDescending { it.peers ?: 0 })
        }
    }

    private fun mapPbTorrent(pb: PirateBayTorrent): Torrent? {
        if (pb.info_hash.isNullOrBlank() || pb.info_hash == "0000000000000000000000000000000000000000") return null
        return Torrent(
            url = "magnet:?xt=urn:btih:${pb.info_hash}&dn=${java.net.URLEncoder.encode(pb.name ?: "torrent", "UTF-8")}$TRACKERS",
            hash = pb.info_hash,
            quality = extractQualityFromName(pb.name),
            type = "Global",
            size = formatSize(pb.size),
            seeds = pb.seeders?.toIntOrNull() ?: 0,
            peers = pb.leechers?.toIntOrNull() ?: 0
        )
    }

    private fun mapTmdbToMovie(tmdb: TmdbSeries): Movie {
        val posterFull = if (tmdb.posterPath != null) TMDB_IMAGE_BASE + tmdb.posterPath else null
        val genresNames = tmdb.genreIds?.map { mapTmdbIdToName(it) }
        return Movie(
            id = tmdb.id,
            title = tmdb.name,
            imdbCode = null, 
            posterPath = posterFull,
            mediumPosterPath = posterFull,
            year = tmdb.firstAirDate?.take(4)?.toIntOrNull(),
            ytTrailerCode = null,
            genres = genresNames,
            torrents = null
        )
    }

    private fun mapEztvTorrent(it: EztvTorrent): Torrent {
        val magnet = if (it.magnetUrl?.contains("tr=") == false) "${it.magnetUrl}$TRACKERS" else it.magnetUrl
        return Torrent(
            url = magnet,
            hash = extractHashFromMagnet(it.magnetUrl),
            quality = if (it.season != "0" || it.episode != "0") "S${it.season}E${it.episode}" else "HD",
            type = "TV",
            size = it.size?.toString(),
            seeds = it.seeds,
            peers = it.peers
        )
    }

    private fun extractHashFromMagnet(magnet: String?): String? {
        return try { magnet?.substringAfter("btih:")?.substringBefore("&") } catch (e: Exception) { null }
    }

    private fun extractQualityFromName(name: String?): String {
        if (name == null) return "HD"
        val n = name.lowercase()
        return when {
            n.contains("2160p") || n.contains("4k") -> "2160p"
            n.contains("1080p") -> "1080p"
            n.contains("720p") -> "720p"
            else -> "HD"
        }
    }

    private fun formatSize(sizeStr: String?): String {
        val size = sizeStr?.toLongOrNull() ?: return "N/A"
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    fun goToNextPage() { _currentPage.value += 1; executeSearch() }
    fun goToPreviousPage() { if (_currentPage.value > 1) { _currentPage.value -= 1; executeSearch() } }
}

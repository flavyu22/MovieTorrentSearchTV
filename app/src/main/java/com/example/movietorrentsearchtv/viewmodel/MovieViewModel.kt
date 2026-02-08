package com.example.movietorrentsearchtv.viewmodel

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.movietorrentsearchtv.api.RetrofitClient
import com.example.movietorrentsearchtv.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // Stari persistente pentru UI
    val selectedGenre = mutableStateOf("All")
    val selectedYear = mutableStateOf("All")
    val selectedQuality = mutableStateOf("All")
    val searchQuery = mutableStateOf("")
    val lastClickedMovieId = mutableStateOf<Long?>(null)

    private val PAGE_LIMIT_SERIES = 14
    private val PAGE_LIMIT_MOVIES = 49
    
    private val TMDB_API_KEY = "5bc758ad2ae90c67d2361f3d63024357"
    private val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w342"
    
    private val apiService by lazy { RetrofitClient.getInstance(application) }

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
        viewModelScope.launch {
            try {
                _isLoading.value = true
                withContext(Dispatchers.IO) {
                    val genreParam = if (selectedGenre.value == "All") null else selectedGenre.value
                    val yearParam = if (selectedYear.value == "All") null else selectedYear.value
                    val qualityParam = if (selectedQuality.value == "All") null else selectedQuality.value

                    if (_isSeriesMode.value) {
                        val tmdbGenreId = mapGenreToTmdbId(genreParam)
                        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        
                        val response = if (searchQuery.value.isNotEmpty()) {
                            apiService.searchTmdbSeries(TMDB_API_KEY, searchQuery.value, yearParam).results ?: emptyList()
                        } else {
                            apiService.discoverTmdbSeries(
                                apiKey = TMDB_API_KEY,
                                page = _currentPage.value,
                                year = yearParam,
                                genre = tmdbGenreId,
                                dateLte = today,
                                sortBy = "first_air_date.desc"
                            ).results ?: emptyList()
                        }
                        val mapped = response.map { mapTmdbToMovie(it) }.distinctBy { it.id }
                        withContext(Dispatchers.Main) { _movies.value = mapped.take(PAGE_LIMIT_SERIES) }
                    } else {
                        val ytsQuery = if (yearParam != null) {
                            if (searchQuery.value.isEmpty()) yearParam else "${searchQuery.value} $yearParam"
                        } else searchQuery.value

                        val response = try {
                            apiService.searchMovies(
                                query = ytsQuery ?: "",
                                genre = genreParam?.lowercase(),
                                quality = qualityParam,
                                limit = PAGE_LIMIT_MOVIES,
                                page = _currentPage.value,
                                sortBy = "year",
                                orderBy = "desc"
                            )
                        } catch (e: Exception) {
                            null
                        }
                        
                        val movieList = response?.data?.movies ?: emptyList()
                        withContext(Dispatchers.Main) { _movies.value = movieList.distinctBy { it.id } }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _movies.value = emptyList() }
            } finally {
                _isLoading.value = false
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
            10762 -> "Kids"
            9648 -> "Mystery"
            10763 -> "News"
            10764 -> "Reality"
            10765 -> "Sci-Fi & Fantasy"
            10766 -> "Soap"
            10767 -> "Talk"
            10768 -> "War & Politics"
            37 -> "Western"
            else -> "Other"
        }
    }

    suspend fun fetchTorrentsForMovie(movie: Movie): List<Torrent> {
        return withContext(Dispatchers.IO) {
            val results = Collections.synchronizedList(mutableListOf<Torrent>())
            
            // Transformam link-urile YTS in MAGNET pentru a evita descarcarea fisierelor .torrent
            movie.torrents?.forEach {
                val magnetUrl = if (!it.hash.isNullOrBlank()) {
                    "magnet:?xt=urn:btih:${it.hash}&dn=${java.net.URLEncoder.encode(movie.title ?: "movie", "UTF-8")}"
                } else it.url
                results.add(it.copy(url = magnetUrl))
            }

            val eztvTask = async {
                if (movie.imdbCode != null) {
                    try {
                        val numericId = movie.imdbCode.replace("tt", "")
                        apiService.getTorrentsByImdb(numericId).torrents?.map { mapEztvTorrent(it) } ?: emptyList()
                    } catch (e: Exception) { emptyList() }
                } else emptyList()
            }

            val globalTask = async {
                try {
                    val baseQuery = movie.imdbCode ?: movie.title ?: ""
                    if (baseQuery.isBlank()) return@async emptyList<Torrent>()
                    val pbResponse = apiService.searchPirateBay(baseQuery)
                    val pbExtra = if (movie.title != null) apiService.searchPirateBay("${movie.title} grantorrent") else emptyList()
                    (pbResponse + pbExtra).mapNotNull { mapPbTorrent(it) }
                } catch (e: Exception) { emptyList() }
            }

            results.addAll(eztvTask.await())
            results.addAll(globalTask.await())
            
            results.toList()
                .distinctBy { it.hash }
                .filter { torrent ->
                    val qualityParam = if (selectedQuality.value == "All") null else selectedQuality.value
                    if (qualityParam != null) {
                        val isSeriesFormat = torrent.quality?.contains(Regex("S\\d+E\\d+")) ?: false
                        if (isSeriesFormat) true 
                        else torrent.quality?.contains(qualityParam, ignoreCase = true) == true
                    } else true
                }
                .sortedWith(compareByDescending<Torrent> { it.seeds ?: 0 }.thenByDescending { it.peers ?: 0 })
        }
    }

    private fun mapPbTorrent(pb: PirateBayTorrent): Torrent? {
        if (pb.info_hash.isNullOrBlank() || pb.info_hash == "0000000000000000000000000000000000000000") return null
        return Torrent(
            url = "magnet:?xt=urn:btih:${pb.info_hash}&dn=${java.net.URLEncoder.encode(pb.name ?: "torrent", "UTF-8")}",
            hash = pb.info_hash,
            quality = extractQualityFromName(pb.name),
            type = if (pb.name?.contains("grantorrent", ignoreCase = true) == true) "GranTorrent" else "Global",
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
        return Torrent(
            url = it.magnetUrl,
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

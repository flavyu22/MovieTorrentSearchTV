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

    private var currentGenre: String? = null
    private var currentYear: String? = null
    private var currentQuality: String? = null
    private var currentQuery: String = ""
    private val PAGE_LIMIT = 48
    
    private val apiService = RetrofitClient.getInstance(application)

    fun searchMovies(query: String, genre: String? = null, year: String? = null, quality: String? = null, page: Int = 1, isSeries: Boolean = _isSeriesMode.value) {
        currentQuery = query
        currentGenre = if (genre == "All") null else genre
        currentYear = if (year == "All") null else year
        currentQuality = if (quality == "All") null else quality
        _isSeriesMode.value = isSeries
        _currentPage.value = page
        
        executeSearch()
    }

    fun loadPopularMovies(genre: String? = null, year: String? = null, quality: String? = null, page: Int = 1, isSeries: Boolean = _isSeriesMode.value) {
        currentQuery = ""
        currentGenre = if (genre == "All") null else genre
        currentYear = if (year == "All") null else year
        currentQuality = if (quality == "All") null else quality
        _isSeriesMode.value = isSeries
        _currentPage.value = page
        
        executeSearch()
    }

    private fun executeSearch() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                withContext(Dispatchers.IO) {
                    if (_isSeriesMode.value) {
                        // Logic for Series (TVMaze)
                        if (currentQuery.isNotEmpty()) {
                            // Search mode
                            val response = apiService.searchTvMaze(currentQuery)
                            var mapped = response.map { mapTvMazeToMovie(it.show) }
                            
                            // Apply filters locally for better accuracy
                            if (currentGenre != null) {
                                mapped = mapped.filter { it.genres?.contains(currentGenre) == true }
                            }
                            if (currentYear != null) {
                                mapped = mapped.filter { it.year?.toString() == currentYear }
                            }
                            
                            withContext(Dispatchers.Main) { _movies.value = mapped }
                        } else {
                            // Browse mode - TVMaze index
                            // We use current page to navigate the index
                            val response = apiService.getPopularTvMaze(page = _currentPage.value - 1)
                            var mapped = response.map { mapTvMazeToMovie(it) }
                            
                            // Apply filters
                            if (currentGenre != null) {
                                mapped = mapped.filter { it.genres?.contains(currentGenre) == true }
                            }
                            if (currentYear != null) {
                                mapped = mapped.filter { it.year?.toString() == currentYear }
                            }
                            
                            withContext(Dispatchers.Main) { _movies.value = mapped }
                        }
                    } else {
                        // Logic for Movies (YTS)
                        // For YTS, we combine query and year into query_term as it works best
                        val combinedQuery = if (currentYear != null) {
                            if (currentQuery.isEmpty()) currentYear!! else "${currentQuery} ${currentYear}"
                        } else currentQuery

                        val response = apiService.searchMovies(
                            query = combinedQuery,
                            genre = currentGenre,
                            quality = currentQuality,
                            limit = PAGE_LIMIT,
                            page = _currentPage.value
                        )
                        withContext(Dispatchers.Main) {
                            _movies.value = response.data.movies ?: emptyList()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _movies.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun fetchTorrentsForMovie(movie: Movie): List<Torrent> {
        return withContext(Dispatchers.IO) {
            val results = Collections.synchronizedList(mutableListOf<Torrent>())
            
            val eztvByImdb = async {
                if (movie.imdbCode != null) {
                    try {
                        val numericId = movie.imdbCode.replace("tt", "")
                        val response = apiService.getTorrentsByImdb(numericId)
                        response.torrents?.map { mapEztvTorrent(it) } ?: emptyList()
                    } catch (e: Exception) { emptyList() }
                } else emptyList()
            }

            val pirateBaySearch = async {
                try {
                    // Include quality in Pirate Bay search if selected
                    val qSuffix = if (currentQuality != null) " ${currentQuality}" else ""
                    val searchQuery = (movie.imdbCode ?: movie.title ?: "") + qSuffix
                    val pbResponse = apiService.searchPirateBay(searchQuery)
                    pbResponse.mapNotNull { pb ->
                        if (pb.info_hash != null && pb.info_hash != "0000000000000000000000000000000000000000") {
                            Torrent(
                                url = "magnet:?xt=urn:btih:${pb.info_hash}&dn=${java.net.URLEncoder.encode(pb.name ?: "torrent", "UTF-8")}",
                                hash = pb.info_hash,
                                quality = extractQualityFromName(pb.name),
                                type = "Global",
                                size = formatSize(pb.size),
                                seeds = pb.seeders?.toIntOrNull() ?: 0,
                                peers = pb.leechers?.toIntOrNull() ?: 0
                            )
                        } else null
                    }
                } catch (e: Exception) { emptyList() }
            }

            results.addAll(eztvByImdb.await())
            results.addAll(pirateBaySearch.await())
            
            // Filter by requested quality if not "All"
            var finalResults = results.toList()
            if (currentQuality != null && currentQuality != "All") {
                finalResults = finalResults.filter { it.quality?.contains(currentQuality!!, ignoreCase = true) == true }
            }

            finalResults.sortedByDescending { it.seeds }.distinctBy { it.hash }
        }
    }

    private fun mapEztvTorrent(it: EztvTorrent): Torrent {
        return Torrent(
            url = it.magnetUrl,
            hash = extractHashFromMagnet(it.magnetUrl),
            quality = if (it.season != "0" && it.episode != "0") "S${it.season}E${it.episode}" else "HD",
            type = "TV",
            size = it.size?.toString(),
            seeds = it.seeds,
            peers = it.peers
        )
    }

    private fun extractQualityFromName(name: String?): String {
        if (name == null) return "HD"
        return when {
            name.contains("2160p", true) || name.contains("4k", true) -> "2160p"
            name.contains("1080p", true) -> "1080p"
            name.contains("720p", true) -> "720p"
            name.contains("S[0-9]+E[0-9]+".toRegex()) -> "Serial"
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

    private fun mapTvMazeToMovie(show: TvMazeShow): Movie {
        return Movie(
            id = show.id,
            title = show.name ?: "Unknown Title",
            imdbCode = show.externals?.imdb,
            posterPath = show.image?.medium,
            mediumPosterPath = show.image?.original ?: show.image?.medium,
            year = show.premiered?.take(4)?.toIntOrNull(),
            ytTrailerCode = null,
            genres = show.genres,
            torrents = null
        )
    }

    private fun extractHashFromMagnet(magnet: String?): String? {
        return try {
            magnet?.substringAfter("btih:")?.substringBefore("&")
        } catch (e: Exception) {
            null
        }
    }

    fun goToNextPage() {
        val nextPage = _currentPage.value + 1
        _currentPage.value = nextPage
        executeSearch()
    }

    fun goToPreviousPage() {
        if (_currentPage.value > 1) {
            val prevPage = _currentPage.value - 1
            _currentPage.value = prevPage
            executeSearch()
        }
    }
}

package io.github.flavyu22.movietorrentsearchtv.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class MovieJsonParsingTest {
    private val gson = Gson()

    @Test
    fun parsesYtsTrailerAndCanonicalDetailsUrl() {
        val movie = gson.fromJson(
            """{
                "id": 42,
                "title": "Example",
                "imdb_code": "tt1234567",
                "small_cover_image": "https://yts.mx/small.jpg",
                "medium_cover_image": "https://yts.mx/medium.jpg",
                "year": 2024,
                "yt_trailer_code": "dQw4w9WgXcQ",
                "genres": ["Drama"],
                "torrents": [],
                "url": "https://yts.mx/movies/example-2024"
            }""".trimIndent(),
            Movie::class.java,
        )

        assertEquals("dQw4w9WgXcQ", movie.ytTrailerCode)
        assertEquals("https://yts.mx/movies/example-2024", movie.url)
    }

    @Test
    fun parsesEztvCanonicalLinks() {
        val torrent = gson.fromJson(
            """{
                "id": 1,
                "torrent_url": "https://eztv.re/ep/1/example/",
                "episode_url": "https://eztv.re/ep/1/example/"
            }""".trimIndent(),
            EztvTorrent::class.java,
        )

        assertEquals("https://eztv.re/ep/1/example/", torrent.torrentUrl)
        assertEquals("https://eztv.re/ep/1/example/", torrent.episodeUrl)
    }
}

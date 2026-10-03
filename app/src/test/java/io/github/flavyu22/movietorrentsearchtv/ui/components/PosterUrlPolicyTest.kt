package io.github.flavyu22.movietorrentsearchtv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PosterUrlPolicyTest {

    @Test
    fun acceptsOnlyKnownHttpsPosterHosts() {
        assertEquals(
            "https://image.tmdb.org/t/p/w500/poster.jpg",
            validatedPosterUrl("https://image.tmdb.org/t/p/w500/poster.jpg"),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w500/poster.jpg",
            validatedPosterUrl("http://image.tmdb.org/t/p/w500/poster.jpg"),
        )
        assertEquals(
            "https://yts.gg/assets/images/movies/example.jpg",
            validatedPosterUrl("https://yts.gg/assets/images/movies/example.jpg"),
        )
    }

    @Test
    fun rejectsLanCredentialsAndLookalikeHosts() {
        listOf(
            "https://127.0.0.1/poster.jpg",
            "https://192.168.1.10/poster.jpg",
            "https://user:pass@image.tmdb.org/poster.jpg",
            "https://image.tmdb.org:8443/poster.jpg",
            "https://image.tmdb.org.evil.example/poster.jpg",
            "https://evilyts.mx/poster.jpg",
        ).forEach { candidate ->
            assertNull(candidate, validatedPosterUrl(candidate))
        }
    }
    @Test
    fun fallsBackToSecondValidPosterCandidate() {
        assertEquals(
            "https://image.tmdb.org/t/p/w500/poster.jpg",
            preferredPosterUrl(
                "",
                null,
                "https://image.tmdb.org/t/p/w500/poster.jpg",
            ),
        )
        assertEquals(
            "https://yts.gg/assets/images/movies/example.jpg",
            preferredPosterUrl(
                "http://unsafe.example/poster.jpg",
                "https://yts.gg/assets/images/movies/example.jpg",
                null
            ),
        )
    }

}

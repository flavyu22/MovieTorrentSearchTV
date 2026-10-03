package io.github.flavyu22.movietorrentsearchtv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteUrlPolicyTest {
    // Hosts verified live as of 2026-08.
    private val allowedHosts = setOf("yts.gg", "yts.bz")

    @Test
    fun acceptsAllowlistedHttpsHostAndSubdomain() {
        assertEquals(
            "https://yts.gg/assets/poster.jpg",
            RemoteUrlPolicy.allowlistedHttps(
                "https://yts.gg/assets/poster.jpg",
                allowedHosts,
            ),
        )
    }

    @Test
    fun rejectsAllowlistedHttpInsteadOfSilentlyUpgrading() {
        assertNull(RemoteUrlPolicy.allowlistedHttps("http://yts.gg/poster.jpg", allowedHosts))
    }

    @Test
    fun rejectsLookalikeLocalCredentialAndCustomPortUrls() {
        listOf(
            "https://evilyts.gg/poster.jpg",
            "https://localhost/poster.jpg",
            "https://127.0.0.1/poster.jpg",
            "https://user:password@yts.gg/poster.jpg",
            "https://yts.gg:8443/poster.jpg",
            "http://yts.gg:8080/poster.jpg",
        ).forEach { url ->
            assertNull(url, RemoteUrlPolicy.allowlistedHttps(url, allowedHosts))
        }
    }

    @Test
    fun validatesYouTubeIdentifiersWithoutAcceptingUrlsOrMarkup() {
        assertEquals("dQw4w9WgXcQ", RemoteUrlPolicy.youtubeVideoId("dQw4w9WgXcQ"))
        assertNull(RemoteUrlPolicy.youtubeVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertNull(RemoteUrlPolicy.youtubeVideoId("<script>"))
    }
}

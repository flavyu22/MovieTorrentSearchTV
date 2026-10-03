package io.github.flavyu22.movietorrentsearchtv.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebViewUrlPolicyTest {
    @Test
    fun acceptsOnlyNormalHttpsUrls() {
        assertEquals(
            "https://example.org/path?q=movie",
            validateHttpsUrl(" https://example.org/path?q=movie "),
        )
        assertEquals(
            "https://example.org:443/path",
            validateHttpsUrl("https://example.org:443/path"),
        )
    }

    @Test
    fun rejectsInsecurePrivilegedAndUnexpectedUrls() {
        listOf(
            "http://example.org",
            "https://user:secret@example.org/path",
            "https://example.org:8443/path",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "https://example.org/path\u0000ignored",
            "https://127.0.0.1/source",
            "https://192.168.1.10/source",
            "https://[::1]/source",
            "https://router.local/source",
            "https://intranet/source",
            "not a url",
        ).forEach { candidate ->
            assertNull(candidate, validateHttpsUrl(candidate))
        }
    }
}

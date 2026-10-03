package io.github.flavyu22.movietorrentsearchtv.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorrserverEndpointTest {
    @Test
    fun normalizesLocalHttpAndPublicHttpsEndpoints() {
        assertEquals("http://127.0.0.1:8090", TorrserverEndpoint.normalize("127.0.0.1", allowPrivateCleartext = true))
        assertEquals(
            "http://192.168.1.20:9000",
            TorrserverEndpoint.normalize("http://192.168.1.20:9000/", allowPrivateCleartext = true),
        )
        assertEquals(
            "https://server.example.com",
            TorrserverEndpoint.normalize("HTTPS://Server.Example.Com", allowPrivateCleartext = false),
        )
        assertEquals(
            "https://server.example.com:8443",
            TorrserverEndpoint.normalize("https://server.example.com:8443", allowPrivateCleartext = false),
        )
    }

    @Test
    fun rejectsPublicCleartextAndAmbiguousAuthorities() {
        listOf(
            "http://example.com:8090",
            "http://172.32.0.1:8090",
            "http://192.168.01.2:8090",
            "http://user:password@192.168.1.2:8090",
            "http://192.168.1.2:8090/api",
            "http://192.168.1.2:8090?redirect=https://example.com",
            "ftp://192.168.1.2:8090",
            "",
        ).forEach { candidate ->
            assertNull(candidate, TorrserverEndpoint.normalize(candidate, allowPrivateCleartext = true))
        }
    }

    @Test
    fun rejectsCleartextLocalHostnameToAvoidDnsRebinding() {
        assertNull(
            TorrserverEndpoint.normalize(
                "http://torrserver.local:8090",
                allowPrivateCleartext = true,
            ),
        )
    }

    @Test
    fun playPolicyRejectsAllCleartextIncludingPrivateLan() {
        assertNull(
            TorrserverEndpoint.normalize(
                "http://192.168.1.20:8090",
                allowPrivateCleartext = false,
            ),
        )
        assertEquals(
            "https://192.168.1.20:8090",
            TorrserverEndpoint.normalize(
                "https://192.168.1.20:8090",
                allowPrivateCleartext = false,
            ),
        )
    }

    @Test
    fun extractsCanonicalHostAndPort() {
        assertEquals(
            "192.168.1.20" to 8090,
            TorrserverEndpoint.hostAndPort("http://192.168.1.20:8090", allowPrivateCleartext = true),
        )
        assertEquals(
            "server.example.com" to 443,
            TorrserverEndpoint.hostAndPort("https://server.example.com", allowPrivateCleartext = false),
        )
    }
}

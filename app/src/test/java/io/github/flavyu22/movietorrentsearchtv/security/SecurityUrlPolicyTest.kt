package io.github.flavyu22.movietorrentsearchtv.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecurityUrlPolicyTest {
    private val hexHash = "0123456789abcdef0123456789abcdef01234567"
    private val base32Hash = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    @Test
    fun acceptsCanonicalOpaqueMagnetLinks() {
        val encoded =
            "magnet:?xt=urn%3Abtih%3A$hexHash&dn=Movie+Name&" +
                "tr=udp%3A%2F%2Ftracker.example.org%3A1337%2Fannounce"
        assertEquals(encoded, MagnetLinkValidator.validate("  $encoded  "))

        val base32 = "MAGNET:?xt=urn:btih:$base32Hash"
        assertEquals(base32, MagnetLinkValidator.validate(base32))
    }

    @Test
    fun rejectsMalformedOrAmbiguousMagnetLinks() {
        listOf(
            "magnet://?xt=urn:btih:$hexHash",
            "magnet:payload?xt=urn:btih:$hexHash",
            "magnet:?xt=urn:btih:$hexHash#fragment",
            "magnet:?xt=urn:btih:$hexHash&xt=urn:btih:$hexHash",
            "magnet:?XT=urn:btih:$base32Hash&xt=urn:btih:$hexHash",
            "magnet:?xt=urn:btih:$hexHash&%78t=urn:btih:$hexHash",
            "magnet:?xt=urn:btih:too-short",
            "magnet:?xt=urn:btih:$hexHash&dn=raw space",
            "magnet:?xt=urn:btih:$hexHash&dn=%",
            "magnet:?xt=urn:btih:$hexHash&dn=%C3%28",
            "magnet:?xt=urn:btih:$hexHash&dn=%0Ahidden",
            "magnet:?xt=urn:btih:$hexHash&",
        ).forEach { candidate ->
            assertNull(candidate, MagnetLinkValidator.validate(candidate))
        }
    }

    @Test
    fun enforcesMagnetComplexityLimits() {
        val tooManyTrackers = buildString {
            append("magnet:?xt=urn:btih:$hexHash")
            repeat(33) { append("&tr=udp%3A%2F%2Ftracker$it.example.org") }
        }
        assertNull(MagnetLinkValidator.validate(tooManyTrackers))

        val tooManyParts = buildString {
            append("magnet:?xt=urn:btih:$hexHash")
            repeat(64) { append("&dn$it=value") }
        }
        assertNull(MagnetLinkValidator.validate(tooManyParts))
    }

    @Test
    fun acceptsAndCanonicalizesStrictManifestUrls() {
        val path =
            "/OxigenForFlower/MovieTorrentSearchTV-master/main/update.json"
        assertEquals(
            "https://raw.githubusercontent.com$path",
            UpdateManifestUrlPolicy.validate("HTTPS://RAW.GITHUBUSERCONTENT.COM:443$path"),
        )
    }

    @Test
    fun rejectsUnsafeManifestUrls() {
        listOf(
            "http://raw.githubusercontent.com/owner/repo/main/update.json",
            "https://user:secret@raw.githubusercontent.com/owner/repo/main/update.json",
            "https://raw.githubusercontent.com:8443/owner/repo/main/update.json",
            "https://raw.githubusercontent.com/owner/repo/main/update.json?channel=beta",
            "https://raw.githubusercontent.com/owner/repo/main/update.json#fragment",
            "https://raw.githubusercontent.com/owner/repo/main/%75pdate.json",
            "https://raw.githubusercontent.com/owner/repo/../update.json",
            "https://localhost/update.json",
            "https://127.0.0.1/update.json",
            "https://example.com/update.JSON",
        ).forEach { candidate ->
            assertNull(candidate, UpdateManifestUrlPolicy.validate(candidate))
        }
    }

    @Test
    fun acceptsOnlyRepositoryReleaseApks() {
        val releasePath =
            "/OxigenForFlower/MovieTorrentSearchTV-master/releases/download/v1.8/app-release.APK"
        assertEquals(
            "https://github.com$releasePath",
            UpdateUrlPolicy.validate(
                "HTTPS://GITHUB.COM:443$releasePath",
                "OxigenForFlower/MovieTorrentSearchTV-master",
            ),
        )
    }

    @Test
    fun rejectsApkUrlAuthorityAndPathBypasses() {
        listOf(
            "http://github.com/OxigenForFlower/MovieTorrentSearchTV-master/releases/download/v1/app.apk",
            "https://github.com.evil.example/OxigenForFlower/MovieTorrentSearchTV-master/releases/download/v1/app.apk",
            "https://user@github.com/OxigenForFlower/MovieTorrentSearchTV-master/releases/download/v1/app.apk",
            "https://github.com:8443/OxigenForFlower/MovieTorrentSearchTV-master/releases/download/v1/app.apk",
            "https://github.com/OxigenForFlower/MovieTorrentSearchTV-master/releases/download/v1/app.apk?raw=1",
            "https://github.com/OxigenForFlower/MovieTorrentSearchTV-master/releases/download/v1/app.apk#fragment",
            "https://github.com/OxigenForFlower/MovieTorrentSearchTV-master/releases/download/v1/%61pp.apk",
            "https://github.com/OxigenForFlower/MovieTorrentSearchTV-master/releases/download/../app.apk",
            "https://github.com/OxigenForFlower/MovieTorrentSearchTV-master/releases/download/v1/sub/app.apk",
            "https://github.com/another/repository/releases/download/v1/app.apk",
        ).forEach { candidate ->
            assertNull(
                candidate,
                UpdateUrlPolicy.validate(
                    candidate,
                    "OxigenForFlower/MovieTorrentSearchTV-master",
                ),
            )
        }
        assertNull(
            UpdateUrlPolicy.validate(
                "https://github.com/owner/repository/releases/download/v1/app.apk",
                "../repository",
            ),
        )
    }
    @Test
    fun acceptsOnlyHttpsGitHubDownloadHosts() {
        assertEquals(
            "https://github.com/owner/repository/releases/download/v1/app.apk",
            UpdateDownloadUrlPolicy.validate(
                "https://github.com/owner/repository/releases/download/v1/app.apk",
            ),
        )
        assertEquals(
            "https://release-assets.githubusercontent.com/github-production-release-asset/file.apk?token=signed",
            UpdateDownloadUrlPolicy.validate(
                "https://release-assets.githubusercontent.com/github-production-release-asset/file.apk?token=signed",
            ),
        )
    }

    @Test
    fun rejectsUnsafeDownloadRedirectHosts() {
        listOf(
            "http://github.com/owner/repository/releases/download/v1/app.apk",
            "https://github.com.evil.example/app.apk",
            "https://user:secret@github.com/app.apk",
            "https://release-assets.githubusercontent.com:8443/app.apk",
            "https://release-assets.githubusercontent.com/app.apk#fragment",
            "https://objects.githubusercontent.com.evil.example/app.apk",
        ).forEach { candidate ->
            assertNull(candidate, UpdateDownloadUrlPolicy.validate(candidate))
        }
    }

}

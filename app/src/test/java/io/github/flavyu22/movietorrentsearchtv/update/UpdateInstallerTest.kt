package io.github.flavyu22.movietorrentsearchtv.update

import io.github.flavyu22.movietorrentsearchtv.security.UpdateDownloadUrlPolicy
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the verified self-updater's download/verify pipeline. The Android
 * framework is not involved: the transport runs against a loopback mockwebserver3
 * instance and the install-launch half stays out of scope here.
 *
 * The object-level hooks ([UpdateInstaller.urlValidator] / [UpdateInstaller.client])
 * are production defaults that every case replaces and [tearDown] always restores.
 */
class UpdateInstallerTest {

    private lateinit var server: MockWebServer
    private lateinit var directory: File

    /** Redirects must never be auto-followed; revalidation is manual per hop. */
    private val testClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        directory = Files.createTempDirectory("update-installer-test").toFile()
    }

    @After
    fun tearDown() {
        server.close()
        UpdateInstaller.urlValidator = ORIGINAL_VALIDATOR
        UpdateInstaller.client = ORIGINAL_CLIENT
    }

    /** Allows only the literal APK path on the running mock server host/port. */
    private fun installLoopbackValidator() {
        val port = server.port
        UpdateInstaller.urlValidator = { url ->
            runCatching {
                val parsed = URL(url)
                if (parsed.protocol == "http" && parsed.port == port && parsed.path == APK_PATH) url else null
            }.getOrNull()
        }
        UpdateInstaller.client = testClient
    }

    private fun apkUrl(): String = server.url(APK_PATH).toString()

    private fun okBody(payload: ByteArray): MockResponse =
        MockResponse.Builder().code(200).body(Buffer().write(payload)).build()

    private fun redirect(location: String): MockResponse =
        MockResponse.Builder().code(302).addHeader("Location", location).build()

    @Test
    fun rejectsMalformedMetadataBeforeAnyNetworkTraffic() = runBlocking {
        installLoopbackValidator()
        val payload = ByteArray(64) { it.toByte() }

        assertEquals(
            UpdateInstaller.DownloadOutcome.InvalidMetadata,
            UpdateInstaller.downloadAndVerify(apkUrl(), "not-a-sha", payload.size.toLong(), directory),
        )
        assertEquals(
            UpdateInstaller.DownloadOutcome.InvalidMetadata,
            UpdateInstaller.downloadAndVerify(apkUrl(), sha256(payload), 0L, directory),
        )
        assertEquals(
            UpdateInstaller.DownloadOutcome.InvalidMetadata,
            UpdateInstaller.downloadAndVerify(
                apkUrl(), sha256(payload), MAX_APK_BYTES + 1, directory,
            ),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun verifiedPayloadIsRenamedIntoPlaceWithExactBytes() = runBlocking {
        installLoopbackValidator()
        val payload = ByteArray(4096) { (it % 251).toByte() }
        server.enqueue(okBody(payload))

        val outcome = UpdateInstaller.downloadAndVerify(
            apkUrl(), sha256(payload), payload.size.toLong(), directory,
        )

        val file = (outcome as UpdateInstaller.DownloadOutcome.Success).file
        assertTrue(file.isFile)
        assertEquals(payload.size.toLong(), file.length())
        assertTrue(file.readBytes().contentEquals(payload))
        assertFalse(File(directory, "app-update.apk.part").exists())
    }

    @Test
    fun wrongSha256IsRejectedAndPartialFileIsRemoved() = runBlocking {
        installLoopbackValidator()
        val payload = ByteArray(1024) { (it * 7).toByte() }
        server.enqueue(okBody(payload))

        val wrong = sha256(payload).let { hash ->
            val flipped = if (hash[0] != '0') "0" else "1"
            flipped + hash.substring(1)
        }

        assertEquals(
            UpdateInstaller.DownloadOutcome.IntegrityFailure,
            UpdateInstaller.downloadAndVerify(apkUrl(), wrong, payload.size.toLong(), directory),
        )
        assertFalse(File(directory, "app-update.apk.part").exists())
        assertFalse(File(directory, "app-update.apk").exists())
    }

    @Test
    fun declaredSizeMismatchIsRejectedWithoutWritingTheArtifact() = runBlocking {
        installLoopbackValidator()
        val payload = ByteArray(512) { it.toByte() }
        server.enqueue(okBody(payload))

        assertEquals(
            UpdateInstaller.DownloadOutcome.IntegrityFailure,
            UpdateInstaller.downloadAndVerify(
                apkUrl(), sha256(payload), payload.size.toLong() + 1, directory,
            ),
        )
        assertFalse(File(directory, "app-update.apk.part").exists())
    }

    @Test
    fun redirectTargetsAreRevalidatedAgainstTheAllowList() = runBlocking {
        installLoopbackValidator()
        server.enqueue(redirect(server.url("/outside-policy").toString()))
        server.enqueue(okBody(ByteArray(32)))

        assertEquals(
            UpdateInstaller.DownloadOutcome.DownloadFailed,
            UpdateInstaller.downloadAndVerify(
                apkUrl(), sha256(ByteArray(32)), 32L, directory,
            ),
        )
        // Only the initial request happened; the rejected hop never downloads.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun allowListedRedirectIsFollowedAndPayloadStillVerified() = runBlocking {
        installLoopbackValidator()
        val payload = ByteArray(256) { it.toByte() }
        server.enqueue(redirect(server.url(APK_PATH).toString()))
        server.enqueue(okBody(payload))

        val outcome = UpdateInstaller.downloadAndVerify(
            apkUrl(), sha256(payload), payload.size.toLong(), directory,
        )

        assertTrue(outcome is UpdateInstaller.DownloadOutcome.Success)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun excessiveRedirectChainsAbort() = runBlocking {
        installLoopbackValidator()
        repeat(REDIRECT_LIMIT + 2) { index ->
            server.enqueue(redirect(server.url("/hop-$index").toString()))
        }

        assertEquals(
            UpdateInstaller.DownloadOutcome.DownloadFailed,
            UpdateInstaller.downloadAndVerify(
                apkUrl(), sha256(ByteArray(8)), 8L, directory,
            ),
        )
    }

    @Test
    fun httpFailureYieldsDownloadFailed() = runBlocking {
        installLoopbackValidator()
        server.enqueue(MockResponse.Builder().code(500).build())

        assertEquals(
            UpdateInstaller.DownloadOutcome.DownloadFailed,
            UpdateInstaller.downloadAndVerify(
                apkUrl(), sha256(ByteArray(8)), 8L, directory,
            ),
        )
    }

    @Test
    fun untrustedInitialUrlIsRejectedByTheProductionPolicy() = runBlocking {
        // The default production validator stays installed: a plain loopback HTTP
        // URL outside the GitHub release policy must not reach the transport.
        assertEquals(
            UpdateInstaller.DownloadOutcome.InvalidMetadata,
            UpdateInstaller.downloadAndVerify(
                "http://localhost/app-update.apk", sha256(ByteArray(8)), 8L, directory,
            ),
        )
        assertEquals(UpdateDownloadUrlPolicy::validate, ORIGINAL_VALIDATOR)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val APK_PATH = "/app-update.apk"
        private const val REDIRECT_LIMIT = 5
        private const val MAX_APK_BYTES = 250L * 1024L * 1024L

        // Captured at class-load time, before any test hook replacement.
        private val ORIGINAL_VALIDATOR: (String) -> String? = UpdateInstaller.urlValidator
        private val ORIGINAL_CLIENT = UpdateInstaller.client
    }
}

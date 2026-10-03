package io.github.flavyu22.movietorrentsearchtv.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.flavyu22.movietorrentsearchtv.security.UpdateDownloadUrlPolicy
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume

object UpdateInstaller {
    private const val MAX_APK_BYTES = 250L * 1024L * 1024L
    private const val MAX_REDIRECTS = 5
    private val SHA256 = Regex("[a-fA-F0-9]{64}")

    sealed interface Result {
        data object InstallerLaunched : Result
        data object InvalidMetadata : Result
        data object DownloadFailed : Result
        data object IntegrityFailure : Result
        data object LaunchFailed : Result
    }

    /** Outcome of the download/verify half — JVM-unit-testable without the Android framework. */
    internal sealed interface DownloadOutcome {
        data class Success(val file: File) : DownloadOutcome
        data object InvalidMetadata : DownloadOutcome
        data object DownloadFailed : DownloadOutcome
        data object IntegrityFailure : DownloadOutcome
    }

    // Overridable for JVM unit tests (MockWebServer plus a loopback allow-list); the
    // production defaults stay the trusted GitHub release policy and the hardened
    // client built below. Tests must restore both hooks after each case.
    internal var urlValidator: (String) -> String? = UpdateDownloadUrlPolicy::validate
    internal var client: OkHttpClient = buildClient()

    suspend fun downloadVerifyAndLaunch(
        context: Context,
        apkUrl: String,
        expectedSha256: String,
        expectedSizeBytes: Long,
    ): Result = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates")
        when (val outcome = downloadAndVerify(apkUrl, expectedSha256, expectedSizeBytes, directory)) {
            is DownloadOutcome.Success -> launchInstaller(context, outcome.file)
            is DownloadOutcome.InvalidMetadata -> Result.InvalidMetadata
            is DownloadOutcome.DownloadFailed -> Result.DownloadFailed
            is DownloadOutcome.IntegrityFailure -> Result.IntegrityFailure
        }
    }

    /**
     * Downloads [apkUrl] into [directory], revalidating every redirect against the
     * active [urlValidator] and enforcing the exact expected size and SHA-256 before
     * the artifact is considered valid. Pure file/network logic — no Android
     * framework — so [UpdateInstallerTest] exercises it directly on the JVM.
     */
    internal suspend fun downloadAndVerify(
        apkUrl: String,
        expectedSha256: String,
        expectedSizeBytes: Long,
        directory: File,
    ): DownloadOutcome {
        if (!SHA256.matches(expectedSha256) || expectedSizeBytes !in 1..MAX_APK_BYTES) {
            return DownloadOutcome.InvalidMetadata
        }
        directory.mkdirs()
        val partial = File(directory, "app-update.apk.part")
        val verified = File(directory, "app-update.apk")
        partial.delete()
        verified.delete()

        val initialUrl = urlValidator(apkUrl)?.toHttpUrlOrNull()
            ?: return DownloadOutcome.InvalidMetadata
        val response = executeFollowingSafeRedirects(initialUrl)
            ?: return DownloadOutcome.DownloadFailed
        val digest = MessageDigest.getInstance("SHA-256")
        val downloadContext = currentCoroutineContext()
        val downloaded = try {
            response.use { value ->
                if (!value.isSuccessful) return DownloadOutcome.DownloadFailed
                val body = value.body
                val declared = body.contentLength()
                if (declared > MAX_APK_BYTES || (declared >= 0 && declared != expectedSizeBytes)) {
                    return DownloadOutcome.IntegrityFailure
                }
                var total = 0L
                body.byteStream().buffered().use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            downloadContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_APK_BYTES || total > expectedSizeBytes) {
                                throw IOException("Update exceeded expected size")
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                downloadContext.ensureActive()
                total
            }
        } catch (cancelled: CancellationException) {
            partial.delete()
            throw cancelled
        } catch (_: IOException) {
            partial.delete()
            return DownloadOutcome.DownloadFailed
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        if (downloaded != expectedSizeBytes ||
            !MessageDigest.isEqual(
                actualHash.lowercase(Locale.ROOT).toByteArray(),
                expectedSha256.lowercase(Locale.ROOT).toByteArray(),
            )
        ) {
            partial.delete()
            return DownloadOutcome.IntegrityFailure
        }
        if (!partial.renameTo(verified)) {
            partial.delete()
            return DownloadOutcome.DownloadFailed
        }
        return DownloadOutcome.Success(verified)
    }

    private suspend fun launchInstaller(context: Context, verified: File): Result {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            verified,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return withContext(Dispatchers.Main) {
            runCatching {
                context.startActivity(intent)
                Result.InstallerLaunched
            }.getOrElse { Result.LaunchFailed }
        }
    }

    private suspend fun executeFollowingSafeRedirects(initialUrl: HttpUrl): Response? {
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val response = execute(Request.Builder().url(currentUrl).get().build())
                ?: return null
            if (response.code !in 300..399) return response

            val location = response.header("Location")
            val nextUrl = location?.let(response.request.url::resolve)
            response.close()
            if (redirectCount >= MAX_REDIRECTS || nextUrl == null) return null
            currentUrl = urlValidator(nextUrl.toString())
                ?.toHttpUrlOrNull()
                ?: return null
        }
        return null
    }

    private suspend fun execute(request: Request): Response? = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, cancelledResponse, _ ->
                    cancelledResponse.close()
                }
            }
        })
    }

    private fun buildClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val url = chain.request().url
            if (urlValidator(url.toString()) == null) {
                throw IOException("Update transport host is not trusted")
            }
            chain.proceed(chain.request())
        }
        .build()
}

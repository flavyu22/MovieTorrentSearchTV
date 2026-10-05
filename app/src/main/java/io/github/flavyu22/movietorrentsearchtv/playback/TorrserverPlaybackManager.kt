package io.github.flavyu22.movietorrentsearchtv.playback

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import io.github.flavyu22.movietorrentsearchtv.model.AppStrings
import io.github.flavyu22.movietorrentsearchtv.config.ExternalPackages
import io.github.flavyu22.movietorrentsearchtv.repository.TorrserverEndpoint
import io.github.flavyu22.movietorrentsearchtv.util.readUpTo
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ─── Rezultat playback ─────────────────────────────────────────────────────────
sealed class PlaybackResult {
    data class Stream(val url: String, val title: String) : PlaybackResult()
    data class Fallback(val magnetUrl: String, val title: String) : PlaybackResult()
    data class Error(val message: String, val magnetUrl: String, val title: String) : PlaybackResult()
}

// ─── Manager separat de Composable — testabil, curat ──────────────────────────
class TorrserverPlaybackManager(
    private val baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
) {
    private val normalizedBaseUrl = TorrserverEndpoint.normalize(baseUrl).orEmpty()

    // Extensii media suportate de Torrserver
    private val mediaExtensions = setOf(
        "mp4", "mkv", "avi", "ts", "mov", "m4v",
        "wmv", "flv", "webm", "mpg", "mpeg", "m2ts"
    )

    /**
     * Extrage info-hash dintr-un magnet URL.
     * Suportă: btih:HASH, xt=urn:btih:HASH (Hex 40 sau Base32 32 chars).
     */
    fun extractInfoHash(magnetUrl: String): String? {
        if (magnetUrl.length !in 1..MAX_LINK_LENGTH) return null
        if (magnetUrl.startsWith("magnet:?", ignoreCase = true)) {
            if ('#' in magnetUrl) return null
            val rawQuery = magnetUrl.substringAfter('?', "").substringBefore('#')
            var exactTopic: String? = null
            var exactTopicCount = 0
            for (parameter in rawQuery.split('&')) {
                val name = decodeComponent(parameter.substringBefore('=')) ?: continue
                if (!name.equals("xt", ignoreCase = true)) continue
                exactTopicCount++
                if (exactTopicCount > 1) return null
                val value = decodeComponent(parameter.substringAfter('=', ""))?.trim().orEmpty()
                if (!value.startsWith(BTIH_PREFIX, ignoreCase = true)) return null
                exactTopic = normalizeInfoHash(value.substring(BTIH_PREFIX.length)) ?: return null
            }
            return exactTopic.takeIf { exactTopicCount == 1 }
        }
        return DIRECT_BTIH.find(magnetUrl)?.groupValues?.getOrNull(1)?.let(::normalizeInfoHash)
    }

    /**
     * Adaugă magnetul în Torrserver și construiește URL stream.
     * Rulează pe Dispatchers.IO — nu bloca Main thread.
     *
     * @return [PlaybackResult] — stream URL dacă reușit, fallback altfel
     */
    suspend fun resolveStream(
        link: String,
        title: String,
        fallbackInfoHash: String? = null,
    ): PlaybackResult = try {
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { resolveWithinBudget(link, title, fallbackInfoHash) }
        } ?: PlaybackResult.Fallback(link, title)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PlaybackResult.Error("", link, title)
    }

    private suspend fun resolveWithinBudget(
        link: String,
        title: String,
        fallbackInfoHash: String?
    ): PlaybackResult {
        val fallback = PlaybackResult.Fallback(link, title)
        if (normalizedBaseUrl.isBlank() || link.length !in 1..MAX_LINK_LENGTH) return fallback
        val suppliedHash = normalizeInfoHash(fallbackInfoHash) ?: extractInfoHash(link)
        val addBody = JsonObject().apply {
            addProperty("action", "add")
            addProperty("link", link)
            addProperty("title", title.take(MAX_TITLE_LENGTH))
            addProperty("save_to_db", false)
        }.toString()
        val addRequest = jsonPost("$normalizedBaseUrl/torrents", addBody)
        val addResponse = try {
            executeBounded(addRequest)
        } catch (_: IOException) {
            return fallback
        }
        if (addResponse.code !in 200..299) return fallback

        val serverHash = try {
            JsonParser.parseString(addResponse.body).asJsonObject
                .stringOrNull("hash")
                .let(::normalizeInfoHash)
        } catch (_: RuntimeException) {
            null
        }
        val infoHash = serverHash ?: suppliedHash ?: return fallback
        val getBody = JsonObject().apply {
            addProperty("action", "get")
            addProperty("hash", infoHash)
        }.toString()
        var consecutiveFailures = 0

        for (iteration in 0 until MAX_METADATA_POLLS) {
            val response = try {
                executeBounded(jsonPost("$normalizedBaseUrl/torrents", getBody))
            } catch (_: IOException) {
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) return fallback
                delay(POLL_INTERVAL_MS)
                continue
            }

            if (response.code !in 200..299) {
                if (response.code in TERMINAL_HTTP_CODES) return fallback
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) return fallback
            } else {
                val selectedFile = try {
                    selectLargestMediaFile(JsonParser.parseString(response.body).asJsonObject).also {
                        consecutiveFailures = 0
                    }
                } catch (_: RuntimeException) {
                    consecutiveFailures++
                    null
                }
                if (selectedFile != null) {
                    val encodedName = URLEncoder.encode(selectedFile.name, Charsets.UTF_8.name())
                        .replace("+", "%20")
                    val streamUrl = "$normalizedBaseUrl/stream/$encodedName" +
                        "?link=$infoHash&index=${selectedFile.id}&play"
                    if (streamUrl.length > MAX_STREAM_URL_LENGTH) return fallback
                    return PlaybackResult.Stream(streamUrl, title)
                }
            }

            if (iteration + 1 < MAX_METADATA_POLLS) delay(POLL_INTERVAL_MS)
        }
        // Never guess index=0: archives often start with a sample, subtitle or image.
        return fallback
    }

    private fun selectLargestMediaFile(json: JsonObject): SelectedFile? {
        val files = json.getAsJsonArray("file_stats") ?: return null
        var selected: SelectedFile? = null
        for (index in 0 until minOf(files.size(), MAX_FILE_STATS)) {
            val element = files[index]
            if (!element.isJsonObject) continue
            val file = element.asJsonObject
            val id = file.intOrNull("id")?.takeIf { it >= 0 } ?: index
            val rawPath = file.stringOrNull("path") ?: continue
            if (rawPath.length !in 1..MAX_FILE_PATH_LENGTH || rawPath.any(Char::isISOControl)) {
                continue
            }
            val path = rawPath.trim()
            val size = file.longOrNull("length") ?: 0L
            val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (extension !in mediaExtensions || size <= MIN_MEDIA_FILE_BYTES) continue
            if (selected == null || size > selected.size) {
                val name = path.substringAfterLast('/').substringAfterLast('\\')
                if (name.isNotBlank() && name.length <= MAX_FILE_NAME_LENGTH) {
                    selected = SelectedFile(id, name, size)
                }
            }
        }
        return selected
    }

    private fun JsonObject.stringOrNull(name: String): String? = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asString
    }.getOrNull()

    private fun JsonObject.intOrNull(name: String): Int? = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asInt
    }.getOrNull()

    private fun JsonObject.longOrNull(name: String): Long? = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asLong
    }.getOrNull()

    private fun jsonPost(url: String, body: String): Request = Request.Builder()
        .url(url)
        .post(body.toRequestBody(JSON_MEDIA_TYPE))
        .build()

    private suspend fun executeBounded(request: Request): HttpPayload =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            val body = it.body
                            val length = body.contentLength()
                            if (length > MAX_RESPONSE_BYTES) throw IOException("TorrServer response too large")
                            val bytes = body.source().readUpTo(MAX_RESPONSE_BYTES + 1L)
                            if (bytes.size.toLong() > MAX_RESPONSE_BYTES) {
                                throw IOException("TorrServer response too large")
                            }
                            HttpPayload(it.code, bytes.toString(Charsets.UTF_8))
                        }
                    }
                    if (!continuation.isCancelled) {
                        result.fold(
                            onSuccess = { continuation.resume(it) },
                            onFailure = { continuation.resumeWithException(it) }
                        )
                    }
                }
            })
        }

    private data class HttpPayload(val code: Int, val body: String)
    private data class SelectedFile(val id: Int, val name: String, val size: Long)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaTypeOrNull()
        val INFO_HASH = Regex("""(?i)(?:[a-f0-9]{40}|[a-z2-7]{32})""")
        val DIRECT_BTIH = Regex("""(?i)btih:([a-f0-9]{40}|[a-z2-7]{32})(?:[&#]|$)""")
        val TERMINAL_HTTP_CODES = setOf(400, 401, 403, 405, 410, 422)
        const val BTIH_PREFIX = "urn:btih:"
        const val RESOLVE_TIMEOUT_MS = 65_000L
        const val POLL_INTERVAL_MS = 1_000L
        const val MAX_METADATA_POLLS = 45
        const val MAX_CONSECUTIVE_FAILURES = 4
        const val MAX_TITLE_LENGTH = 100
        const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
        const val MAX_LINK_LENGTH = 4_096
        const val MAX_STREAM_URL_LENGTH = 4_096
        const val MAX_FILE_STATS = 1_000
        const val MAX_FILE_PATH_LENGTH = 1_024
        const val MAX_FILE_NAME_LENGTH = 255
        const val MIN_MEDIA_FILE_BYTES = 50L * 1024L * 1024L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        fun normalizeInfoHash(value: String?): String? = value
            ?.trim()
            ?.takeIf(INFO_HASH::matches)
            ?.lowercase(Locale.ROOT)

        fun decodeComponent(value: String): String? = runCatching {
            URLDecoder.decode(value, Charsets.UTF_8.name())
        }.getOrNull()
    }
}

// ─── Intent helpers — separat de logica Torrserver ────────────────────────────
object PlaybackIntentHelper {
    private const val TAG = "PlaybackIntentHelper"
    private val nextPlayerChooserRequestCode = AtomicInteger(1_001)

    /**
     * Deschide interfața aplicației TorrServe.
     * Dacă se furnizează un magnetUrl, încearcă să-l trimită direct către aplicație.
     */
    fun openTorrserver(context: Context, magnetUrl: String? = null) {
        val packages = ExternalPackages.torrServePackages
        for (pkg in packages) {
            try {
                val intent = if (magnetUrl != null) {
                    Intent(Intent.ACTION_VIEW, magnetUrl.toUri()).apply {
                        setPackage(pkg)
                    }
                } else {
                    context.packageManager.getLaunchIntentForPackage(pkg)
                }
                
                intent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                    return
                }
            } catch (failure: Exception) {
                Log.d(TAG, "External application $pkg unavailable", failure)
            }
        }
    }

    /** Uses the previously selected player, or remembers the first choice from the system chooser. */
    fun playStream(context: Context, streamUrl: String, title: String, strings: AppStrings) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(streamUrl.toUri(), "video/*")
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra("title", title)
            putExtra("name", title)
            putExtra("from_start", true)
            putExtra("position", 0L)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        playVideo(context, intent, strings)
    }

    /**
     * Fallback: deschide magnetul direct (BitTorrent client) sau un URL video.
     * Modificat: Prioritizează TorrServe pentru magnet-uri pentru a evita browserul.
     */
    fun playFallback(
        context: Context,
        url: String,
        title: String,
        isMagnet: Boolean,
        strings: AppStrings,
    ) {
        try {
            val uri    = url.toUri()
            val intent = Intent(Intent.ACTION_VIEW).apply {
                if (isMagnet) data = uri
                else {
                    setDataAndType(uri, "video/*")
                    addCategory(Intent.CATEGORY_DEFAULT)
                    putExtra("from_start", true)
                }
                putExtra("title", title)
                putExtra("name", title)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            if (!isMagnet) {
                playVideo(context, intent, strings)
                return
            }

            // 1. Check for preferred handler (user-selected previously)
            if (launchPreferredApp(context, intent, PreferredPlayerStore.TYPE_MAGNET)) return

            // 2. Show chooser and remember choice. 
            // The chooser will include all installed apps that handle magnets, including TorrServe and TorrServe Matrix.
            context.startActivity(createAppChooser(context, intent, strings.playWith, PreferredPlayerStore.TYPE_MAGNET))
        } catch (_: ActivityNotFoundException) {
            val msg = if (isMagnet) strings.installBitTorrent else strings.installPlayer
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(context, strings.actionUnavailableTitle, Toast.LENGTH_SHORT).show()
        }
    }

    private fun playVideo(context: Context, intent: Intent, strings: AppStrings) {
        // 1. Încearcă player-ul salvat anterior
        if (launchPreferredApp(context, intent, PreferredPlayerStore.TYPE_VIDEO)) return

        // 2. Auto-detectează un player instalat cunoscut
        ExternalPackages.findInstalledVideoPlayer(context, intent)?.let { detected ->
            PreferredPlayerStore.save(context, detected, PreferredPlayerStore.TYPE_VIDEO)
            if (launchPreferredApp(context, intent, PreferredPlayerStore.TYPE_VIDEO)) return
        }

        // 3. Nicio preferință și niciun player cunoscut — arată chooser-ul
        try {
            context.startActivity(createAppChooser(context, intent, strings.playWith, PreferredPlayerStore.TYPE_VIDEO))
        } catch (failure: ActivityNotFoundException) {
            Log.w(TAG, "No external video player is available", failure)
            Toast.makeText(context, strings.installPlayer, Toast.LENGTH_LONG).show()
        } catch (failure: Exception) {
            Log.w(TAG, "Unable to launch an external player", failure)
            Toast.makeText(
                context,
                strings.playerError.format(strings.actionUnavailableTitle),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun launchPreferredApp(context: Context, intent: Intent, type: String): Boolean {
        val preferredComponent = PreferredPlayerStore.get(context, type) ?: return false
        if (preferredComponent.packageName == context.packageName) {
            PreferredPlayerStore.clear(context, type)
            return false
        }
        
        val preferredIntent = if (preferredComponent.className.isEmpty()) {
            // Package-level preference (e.g. auto-detected TorrServe)
            Intent(intent).setPackage(preferredComponent.packageName)
        } else {
            Intent(intent).setComponent(preferredComponent)
        }
        
        return try {
            context.startActivity(preferredIntent)
            Log.d(TAG, "Launched preferred app ($type): ${preferredComponent.flattenToShortString()}")
            true
        } catch (failure: Exception) {
            Log.w(TAG, "Preferred app failed to start, clearing memory.", failure)
            PreferredPlayerStore.clear(context, type)
            false
        }
    }

    private fun createAppChooser(context: Context, intent: Intent, title: String, type: String): Intent {
        val callbackIntent = Intent(context, PlayerSelectionReceiver::class.java).apply {
            action = PreferredPlayerStore.SELECTION_CALLBACK_ACTION
            putExtra(PreferredPlayerStore.EXTRA_SELECTION_TYPE, type)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        val callback = PendingIntent.getBroadcast(
            context,
            nextPlayerChooserRequestCode.getAndIncrement(),
            callbackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_MUTABLE,
        )
        return Intent.createChooser(intent, title, callback.intentSender).apply {
            // This app accepts incoming magnets; never send its own fallback back to itself.
            putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(
                ComponentName(context, io.github.flavyu22.movietorrentsearchtv.MainActivity::class.java),
            ))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

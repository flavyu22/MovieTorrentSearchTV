package io.github.flavyu22.movietorrentsearchtv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.DeDupeConcurrentRequestStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.allowRgb565
import coil3.request.bitmapConfig
import coil3.request.crossfade
import coil3.size.Precision
import io.github.flavyu22.movietorrentsearchtv.di.NetworkManager
import kotlinx.coroutines.Dispatchers
import okhttp3.Dispatcher
import okio.Path.Companion.toOkioPath

class MovieTorrentApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
    }

    @OptIn(ExperimentalCoilApi::class)
    override fun newImageLoader(context: Context): ImageLoader {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val isLowMemoryDevice = activityManager?.isLowRamDevice == true ||
            (activityManager?.memoryClass ?: DEFAULT_MEMORY_CLASS_MB) <= LOW_MEMORY_CLASS_MB
        val heapBytes = (activityManager?.memoryClass ?: DEFAULT_MEMORY_CLASS_MB).toLong() * MEBIBYTE
        val memoryCacheBytes = if (isLowMemoryDevice) {
            (heapBytes * LOW_MEMORY_CACHE_FRACTION).toLong()
                .coerceIn(MIN_MEMORY_CACHE_BYTES, LOW_MEMORY_CACHE_MAX_BYTES)
        } else {
            (heapBytes * MEMORY_CACHE_FRACTION).toLong()
                .coerceIn(MIN_MEMORY_CACHE_BYTES, MEMORY_CACHE_MAX_BYTES)
        }

        // Keep poster traffic separate from catalogue/torrent calls. Limiting image concurrency
        // prevents a newly visible grid page from scheduling enough downloads and decodes to make
        // D-pad focus frames miss their deadline.
        val imageDispatcher = Dispatcher().apply {
            maxRequests = IMAGE_FETCH_PARALLELISM
            maxRequestsPerHost = IMAGE_FETCH_PARALLELISM
        }
        val imageNetworkClient = NetworkManager.getOkHttpClient(context)
            .newBuilder()
            .dispatcher(imageDispatcher)
            // Coil owns the encoded-image disk cache. Avoid writing every poster a second time to
            // OkHttp's general response cache while retaining the shared connection pool.
            .cache(null)
            .build()

        return ImageLoader.Builder(context.applicationContext)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(memoryCacheBytes)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { imageNetworkClient },
                        concurrentRequestStrategy = ::DeDupeConcurrentRequestStrategy,
                    ),
                )
            }
            .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(IMAGE_FETCH_PARALLELISM))
            .decoderCoroutineContext(
                Dispatchers.IO.limitedParallelism(
                    if (isLowMemoryDevice) LOW_MEMORY_DECODE_PARALLELISM else DECODE_PARALLELISM,
                ),
            )
            .precision(Precision.INEXACT)
            .allowRgb565(isLowMemoryDevice)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            // Crossfading every card adds overlapping draw work while a grid is being populated.
            // Detail screens can still opt in on their individual request.
            .crossfade(false)
            .apply {
                // Hardware bitmaps are fastest on normal devices. On genuinely constrained TVs,
                // RGB_565 halves poster memory and reduces the chance of GC pauses during scroll.
                if (isLowMemoryDevice) bitmapConfig(Bitmap.Config.RGB_565)
            }
            .build()
    }

    private companion object {
        const val DEFAULT_MEMORY_CLASS_MB = 256
        const val LOW_MEMORY_CLASS_MB = 128
        const val MEBIBYTE = 1024L * 1024L
        const val LOW_MEMORY_CACHE_FRACTION = 0.08
        const val MEMORY_CACHE_FRACTION = 0.12
        const val MIN_MEMORY_CACHE_BYTES = 8L * MEBIBYTE
        const val LOW_MEMORY_CACHE_MAX_BYTES = 16L * MEBIBYTE
        const val MEMORY_CACHE_MAX_BYTES = 48L * MEBIBYTE
        const val DISK_CACHE_BYTES = 96L * MEBIBYTE
        const val IMAGE_FETCH_PARALLELISM = 4
        const val LOW_MEMORY_DECODE_PARALLELISM = 1
        const val DECODE_PARALLELISM = 2
    }
}

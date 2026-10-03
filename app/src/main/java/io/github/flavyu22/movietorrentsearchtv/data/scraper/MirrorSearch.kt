package io.github.flavyu22.movietorrentsearchtv.data.scraper

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/** A slow/dead primary must not consume the caller's entire shared timeout. */
internal suspend fun <T> firstSuccessfulMirror(fetchers: List<suspend () -> T>): T = coroutineScope {
    require(fetchers.isNotEmpty()) { "No mirrors configured" }
    val pending = fetchers.map { fetch ->
        async {
            try {
                Result.success(fetch())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure<T>(failure)
            }
        }
    }.toMutableList()
    var lastFailure: Throwable? = null
    try {
        while (pending.isNotEmpty()) {
            val (completed, outcome) = select<Pair<Deferred<Result<T>>, Result<T>>> {
                pending.forEach { candidate -> candidate.onAwait { candidate to it } }
            }
            pending.remove(completed)
            if (outcome.isSuccess) return@coroutineScope outcome.getOrThrow()
            lastFailure = outcome.exceptionOrNull()
        }
        throw IOException("All mirrors failed", lastFailure)
    } finally {
        pending.forEach { it.cancel() }
    }
}

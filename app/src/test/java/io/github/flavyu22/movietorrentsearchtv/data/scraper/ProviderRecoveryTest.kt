package io.github.flavyu22.movietorrentsearchtv.data.scraper

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ProviderRecoveryTest {
    @Test fun slowPrimaryDoesNotBlockWorkingMirrorAndIsCancelled() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val value = withTimeout(2_000) {
            firstSuccessfulMirror(listOf(
                suspend {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled = true }
                },
                suspend { started.await(); "healthy" },
            ))
        }
        assertEquals("healthy", value)
        assertTrue(cancelled)
    }

    @Test fun failedMirrorDoesNotDiscardLaterSuccess() = runBlocking {
        val failed = CompletableDeferred<Unit>()
        val value = firstSuccessfulMirror(listOf(
            suspend { failed.complete(Unit); throw IOException("offline") },
            suspend { failed.await(); "healthy" },
        ))
        assertEquals("healthy", value)
    }

    @Test fun validEmptyResultIsASuccess() = runBlocking {
        assertTrue(firstSuccessfulMirror(listOf(suspend { emptyList<String>() })).isEmpty())
    }

    @Test fun everyFailureRemainsAnError() = runBlocking {
        try {
            firstSuccessfulMirror(listOf(suspend { throw IOException("offline") }))
            fail("Expected IOException")
        } catch (expected: IOException) {
            assertNotNull(expected.cause)
        }
    }

    @Test fun malformedPayloadIsNotAHealthyEmptyIndex() {
        val client = OkHttpClient()
        val parsers = listOf<(String) -> Any>(
            TorrentsCsvScraper(client)::parseTorrentsCsvResponse,
            SolidTorrentsScraper(client)::parseSolidResponse,
            EztvScraper(client)::parseEztvResponse,
        )
        parsers.forEach { parse ->
            try { parse("{}"); fail("Missing result field must fail") }
            catch (expected: IOException) { /* Correct: exposed to source-error UI. */ }
        }
        assertTrue(TorrentsCsvScraper(client).parseTorrentsCsvResponse("{\"torrents\":[]}").isEmpty())
        assertTrue(SolidTorrentsScraper(client).parseSolidResponse("{\"success\":true,\"results\":[]}").isEmpty())
        assertTrue(EztvScraper(client).parseEztvResponse("{\"torrents_count\":0}").isEmpty())
    }
}

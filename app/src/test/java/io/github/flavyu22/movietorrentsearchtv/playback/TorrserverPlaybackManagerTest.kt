package io.github.flavyu22.movietorrentsearchtv.playback

import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrserverPlaybackManagerTest {
    @Test
    fun addsEphemerallyAndStreamsOnlyAConfirmedMediaFile() = runBlocking {
        val hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        TestHttpServer(
            listOf(
                200 to """{"hash":"$hash"}""",
                200 to """{
                    "file_stats":[
                        {"id":0,"path":"sample.mkv","length":1024},
                        {"id":7,"path":"Feature Film.mkv","length":734003200}
                    ]
                }""".trimIndent()
            )
        ).use { server ->
            val manager = TorrserverPlaybackManager(server.baseUrl, OkHttpClient.Builder().build())

            val result = manager.resolveStream(
                "magnet:?xt=urn:btih:$hash",
                "A \"quoted\" title"
            )

            assertTrue("result=$result paths=${server.paths}", result is PlaybackResult.Stream)
            val stream = result as PlaybackResult.Stream
            assertTrue(stream.url.contains("/stream/Feature%20Film.mkv"))
            assertTrue(stream.url.contains("index=7"))
            assertFalse(stream.url.contains("index=0"))
            assertEquals(listOf("/torrents", "/torrents"), server.paths.toList())

            val addJson = JsonParser.parseString(server.bodies.first()).asJsonObject
            assertFalse(addJson.get("save_to_db").asBoolean)
            assertEquals("A \"quoted\" title", addJson.get("title").asString)
        }
    }

    @Test
    fun returnsFallbackInsteadOfGuessingFileZero() = runBlocking {
        val hash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        TestHttpServer(
            listOf(
                200 to """{"hash":"$hash"}""",
                400 to """{"error":"metadata unavailable"}"""
            )
        ).use { server ->
            val result = TorrserverPlaybackManager(
                server.baseUrl,
                OkHttpClient.Builder().build()
            )
                .resolveStream("magnet:?xt=urn:btih:$hash", "Example")

            assertTrue(result is PlaybackResult.Fallback)
            assertFalse(server.paths.any { it == "/settings" })
        }
    }

    @Test
    fun parsesEncodedXtWithoutTrustingEncodedParametersInsideDisplayName() {
        val realHash = "cccccccccccccccccccccccccccccccccccccccc"
        val fakeHash = "dddddddddddddddddddddddddddddddddddddddd"
        val manager = TorrserverPlaybackManager("http://127.0.0.1:8090")

        assertEquals(
            realHash,
            manager.extractInfoHash(
                "magnet:?dn=Name%26xt%3Durn%3Abtih%3A$fakeHash" +
                "&xt=urn%3Abtih%3A$realHash"
            )
        )
        assertNull(manager.extractInfoHash("magnet:?dn=btih:$fakeHash"))
        assertNull(
            manager.extractInfoHash(
                "magnet:?XT=urn:btih:$fakeHash&xt=urn:btih:$realHash"
            )
        )
    }

    private class TestHttpServer(
        private val responses: List<Pair<Int, String>>
    ) : AutoCloseable {
        private val socket = ServerSocket(0)
        val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val bodies: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val baseUrl = "http://127.0.0.1:${socket.localPort}"
        private val worker = thread(isDaemon = true, name = "torrserver-test") {
            responses.forEach { (status, responseBody) ->
                val connection = socket.accept()
                connection.use { client ->
                    val input = BufferedInputStream(client.getInputStream())
                    val requestLine = readAsciiLine(input)
                    paths += requestLine.split(' ').getOrElse(1) { "" }
                    var contentLength = 0
                    while (true) {
                        val header = readAsciiLine(input)
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = header.substringAfter(':').trim().toInt()
                        }
                    }
                    val requestBody = ByteArray(contentLength)
                    var offset = 0
                    while (offset < requestBody.size) {
                        val read = input.read(requestBody, offset, requestBody.size - offset)
                        if (read < 0) break
                        offset += read
                    }
                    bodies += requestBody.toString(StandardCharsets.UTF_8)

                    val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
                    val reason = if (status in 200..299) "OK" else "Error"
                    client.getOutputStream().apply {
                        write(
                            "HTTP/1.1 $status $reason\r\n".toByteArray(StandardCharsets.US_ASCII)
                        )
                        write("Content-Type: application/json\r\n".toByteArray(StandardCharsets.US_ASCII))
                        write("Content-Length: ${bytes.size}\r\n".toByteArray(StandardCharsets.US_ASCII))
                        write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                        write(bytes)
                        flush()
                    }
                }
            }
        }

        override fun close() {
            socket.close()
            worker.join(1_000)
        }

        private fun readAsciiLine(input: BufferedInputStream): String {
            val bytes = ArrayList<Byte>()
            while (true) {
                val value = input.read()
                if (value < 0 || value == '\n'.code) break
                if (value != '\r'.code) bytes += value.toByte()
            }
            return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
        }
    }
}

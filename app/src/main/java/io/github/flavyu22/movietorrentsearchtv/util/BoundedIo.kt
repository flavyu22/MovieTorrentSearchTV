package io.github.flavyu22.movietorrentsearchtv.util

import okio.Buffer
import okio.BufferedSource

/** Reads no more than [maxByteCount], without requiring the source to contain that many bytes. */
internal fun BufferedSource.readUpTo(maxByteCount: Long): ByteArray {
    require(maxByteCount >= 0L) { "maxByteCount must be non-negative" }
    val sink = Buffer()
    while (sink.size < maxByteCount) {
        val remaining = maxByteCount - sink.size
        val read = read(sink, minOf(8_192L, remaining))
        if (read <= 0L) break
    }
    return sink.readByteArray()
}

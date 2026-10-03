package io.github.flavyu22.movietorrentsearchtv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedTorrentTest {
    @Test
    fun parsesHumanReadableSizesWithBinaryMultipliers() {
        assertEquals(1_610_612_736L, UnifiedTorrent.parseSizeToBytes("1.5 GiB"))
        assertEquals(1_610_612_736L, UnifiedTorrent.parseSizeToBytes("1,5 GB"))
        assertEquals(734_003_200L, UnifiedTorrent.parseSizeToBytes("700 MB"))
        assertEquals(0L, UnifiedTorrent.parseSizeToBytes("unknown"))
    }

    @Test
    fun ranksRealReleaseQualityAboveCameraCopies() {
        assertTrue(
            UnifiedTorrent.qualityScoreFor("2160p HEVC HDR10") >
                UnifiedTorrent.qualityScoreFor("1080p x264"),
        )
        assertEquals(-100, UnifiedTorrent.qualityScoreFor("1080p HDCAM"))
        assertEquals(0, UnifiedTorrent.qualityScoreFor(null))
    }

    @Test
    fun parsesEpochAndStrictUtcDates() {
        assertEquals(1_700_000_000_000L, UnifiedTorrent.parseUploadDateToMillis("1700000000"))
        assertEquals(1_735_776_000_000L, UnifiedTorrent.parseUploadDateToMillis("2025-01-02"))
        assertEquals(0L, UnifiedTorrent.parseUploadDateToMillis("2025-02-31"))
    }

    @Test
    fun naturalOrderingPrefersQualityThenSeeds() {
        val low = torrent(hash = "low", quality = "720p", seeds = 5_000)
        val highFewSeeds = torrent(hash = "high-a", quality = "2160p", seeds = 10)
        val highManySeeds = torrent(hash = "high-b", quality = "2160p", seeds = 100)

        assertEquals(
            listOf(highManySeeds, highFewSeeds, low),
            listOf(low, highFewSeeds, highManySeeds).sorted(),
        )
    }

    private fun torrent(hash: String, quality: String, seeds: Int) = UnifiedTorrent(
        infoHash = hash,
        title = hash,
        magnetUrl = "magnet:?xt=urn:btih:$hash",
        size = "1 GB",
        seeds = seeds,
        peers = 0,
        quality = quality,
        source = "test",
    )
}

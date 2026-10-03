package io.github.flavyu22.movietorrentsearchtv.data.scraper

import io.github.flavyu22.movietorrentsearchtv.model.TorrentSource
import io.github.flavyu22.movietorrentsearchtv.model.UnifiedTorrent

interface TorrentScraper {
    val source: TorrentSource
    suspend fun search(query: String, category: TorrentSource.Category = TorrentSource.Category.ALL): List<UnifiedTorrent>
    suspend fun searchByImdb(imdbId: String): List<UnifiedTorrent>
}


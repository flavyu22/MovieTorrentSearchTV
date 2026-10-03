package io.github.flavyu22.movietorrentsearchtv.config

/** One Kotlin source of truth for public service endpoints and host allow-lists. */
object RemoteHosts {
    const val YTS_API_BASE_URL = "https://yts.gg/api/v2/"
    const val TMDB_API_BASE_URL = "https://api.themoviedb.org/3/"

    /** Secondary multilingual metadata source; keyless public API. */
    const val WIKIDATA_API_URL = "https://www.wikidata.org/w/api.php"
    const val TMDB_POSTER_BASE_URL = "https://image.tmdb.org/t/p/w500/"
    const val PIRATE_BAY_SITE_URL = "https://thepiratebay.org"
    const val PIRATE_BAY_API_URL = "https://apibay.org"
    const val EZTV_SITE_URL = "https://eztvx.to"
    const val EZTV_API_URL = "$EZTV_SITE_URL/api/get-torrents"

    val ytsMirrorBaseUrls: List<String> = listOf(
        // Verified live 2026-08 (each answers /api/v2/list_movies.json with status=ok).
        // Removed as dead: yts.mx / ytx.bz (NXDOMAIN), yts.pm (404), yts.lu and
        // www12.yts-official.to (serve an HTML shell instead of the JSON API).
        "https://yts.gg",
        "https://yts.lt",
        "https://movies-api.accel.li",
        "https://yts.bz",
        "https://yts.ag",
    )

    val ytsHostSuffixes: Set<String> = setOf(
        "yts.gg", "yts.bz", "yts.lt", "yts.ag", "accel.li",
    )

    val eztvLinkHostSuffixes: Set<String> = setOf("eztvx.to", "eztv.re", "zoink.ch")

    val catalogueImageHostSuffixes: Set<String> = ytsHostSuffixes + setOf(
        "image.tmdb.org",
        // EZTV serves episode screenshots from these hosts.
        "ezimg.ch", "eztv.re", "eztvx.to", "zoink.ch",
        "solidtorrents.eu",
    )
}

package io.github.flavyu22.movietorrentsearchtv.config

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

object ExternalPackages {
    val torrServePackages: List<String> = listOf(
        "ru.alexey.mancode.torrserve.matrix",
        "ru.alexey.mancode.torrserve",
    )

    /**
     * Lista de playere video cunoscute, sortate dupa prioritate.
     * Cand aplicatia detecteaza automat un player instalat,
     * il foloseste direct fara a mai cere utilizatorului sa aleaga.
     */
    private val knownVideoPlayers: List<VideoPlayerInfo> = listOf(
        VideoPlayerInfo("OxigenPlayer", "com.oxigenplayer.app"),
        VideoPlayerInfo("OxigenPlayer", "com.oxigen.player"),
        VideoPlayerInfo("OxigenPlayer", "com.oxygen.player"),
        VideoPlayerInfo("OxigenPlayer", "com.oxigenplayer"),
        VideoPlayerInfo("OxigenPlayer", "com.oxigen.videoplayer"),
        VideoPlayerInfo("VLC", "org.videolan.vlc"),
        VideoPlayerInfo("MX Player", "com.mxtech.videoplayer.ad"),
        VideoPlayerInfo("MX Player Pro", "com.mxtech.videoplayer.pro"),
        VideoPlayerInfo("Nova Video Player", "org.courville.nova"),
        VideoPlayerInfo("nPlayer", "com.newin.nplayer.pro"),
        VideoPlayerInfo("MPV", "is.xyz.mpv"),
        VideoPlayerInfo("Just (Video) Player", "com.brouken.player"),
        VideoPlayerInfo("Kodi", "org.xbmc.kodi"),
        VideoPlayerInfo("KMPlayer", "com.kmplayer"),
        VideoPlayerInfo("BsPlayer", "com.bsplayer.bspandroid.free"),
        VideoPlayerInfo("BsPlayer Pro", "com.bsplayer.bspandroid.full"),
        VideoPlayerInfo("XPlayer", "video.player.videoplayer"),
        VideoPlayerInfo("Next Player", "dev.anilbeesetti.nextplayer"),
    )

    data class VideoPlayerInfo(val name: String, val packageName: String)

    /**
     * Returneaza ComponentName-ul primului player video cunoscut
     * care este instalat pe dispozitiv, sau null daca niciunul nu e gasit.
     */
    fun findInstalledVideoPlayer(context: Context, videoIntent: Intent): ComponentName? {
        val pm = context.packageManager

        val activities: List<android.content.pm.ResolveInfo> = try {
            pm.queryIntentActivities(videoIntent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (_: Exception) {
            emptyList()
        }

        val videoCapablePackages = activities.map { it.activityInfo.packageName }.toSet()

        for (player in knownVideoPlayers) {
            if (player.packageName in videoCapablePackages) {
                for (resolveInfo in activities) {
                    if (resolveInfo.activityInfo.packageName == player.packageName) {
                        val component = ComponentName(
                            player.packageName,
                            resolveInfo.activityInfo.name,
                        )
                        Log.d("ExternalPackages", "Auto-detected: ${player.name}")
                        return component
                    }
                }
            }
        }
        return null
    }
}

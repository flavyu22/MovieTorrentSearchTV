package io.github.flavyu22.movietorrentsearchtv.playback

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import androidx.core.content.edit

/** Persists the exact video-player or magnet handler activity selected from the Android chooser. */
object PreferredPlayerStore {
    const val SELECTION_CALLBACK_ACTION =
        "io.github.flavyu22.movietorrentsearchtv.action.APP_SELECTED"
    const val EXTRA_SELECTION_TYPE = "selection_type"
    const val TYPE_VIDEO = "video"
    const val TYPE_MAGNET = "magnet"

    private const val PREFERENCES_FILE = "playback_preferences"
    private const val PREFERRED_VIDEO_KEY = "preferred_video_player_component"
    private const val PREFERRED_MAGNET_KEY = "preferred_magnet_handler_component"

    fun get(context: Context, type: String): ComponentName? {
        val key = if (type == TYPE_MAGNET) PREFERRED_MAGNET_KEY else PREFERRED_VIDEO_KEY
        val flattened = preferences(context).getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
        return ComponentName.unflattenFromString(flattened) ?: run {
            clear(context, type)
            null
        }
    }

    fun save(context: Context, component: ComponentName, type: String) {
        val key = if (type == TYPE_MAGNET) PREFERRED_MAGNET_KEY else PREFERRED_VIDEO_KEY
        preferences(context).edit {
            putString(key, component.flattenToString())
            // commit() (not apply()) is deliberate: this runs from a BroadcastReceiver, and an
            // in-flight apply() write can be lost if the process is killed right after onReceive
            // returns, which would silently drop the user's player selection.
            commit()
        }
    }

    fun clear(context: Context, type: String? = null) {
        preferences(context).edit {
            if (type == null) {
                remove(PREFERRED_VIDEO_KEY)
                remove(PREFERRED_MAGNET_KEY)
            } else {
                val key = if (type == TYPE_MAGNET) PREFERRED_MAGNET_KEY else PREFERRED_VIDEO_KEY
                remove(key)
            }
        }
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
}

/** Receives the system chooser result even if the app process is recreated meanwhile. */
class PlayerSelectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PreferredPlayerStore.SELECTION_CALLBACK_ACTION) return
        
        val type = intent.getStringExtra(PreferredPlayerStore.EXTRA_SELECTION_TYPE) ?: PreferredPlayerStore.TYPE_VIDEO
        val selectedComponent = IntentCompat.getParcelableExtra(
            intent,
            Intent.EXTRA_CHOSEN_COMPONENT,
            ComponentName::class.java,
        )

        if (selectedComponent != null && selectedComponent.packageName != context.packageName &&
            type in setOf(PreferredPlayerStore.TYPE_VIDEO, PreferredPlayerStore.TYPE_MAGNET)
        ) {
            PreferredPlayerStore.save(context, selectedComponent, type)
        }
    }
}

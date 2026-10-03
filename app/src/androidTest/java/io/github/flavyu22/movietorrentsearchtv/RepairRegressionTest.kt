package io.github.flavyu22.movietorrentsearchtv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.flavyu22.movietorrentsearchtv.playback.PlayerSelectionReceiver
import io.github.flavyu22.movietorrentsearchtv.playback.PreferredPlayerStore
import io.github.flavyu22.movietorrentsearchtv.viewmodel.MovieViewModel
import io.github.flavyu22.movietorrentsearchtv.viewmodel.TorrserverViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a disposable emulator/test profile: exercises real Android preferences and lifecycle. */
@RunWith(AndroidJUnit4::class)
class RepairRegressionTest {
    @Test fun changingAndDisablingServerUpdatesExistingPlaybackViewModel() {
        val app = ApplicationProvider.getApplicationContext<MovieTorrentApplication>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val oldAddress = settings.getString("torrserver_address", null)
            val oldRevision = settings.getLong("torrserver_selection_revision", 0L)
            // Avoid discovery/network requests during this deterministic settings test.
            settings.edit().putString("torrserver_address", "https://first.example.com").commit()
            val store = ViewModelStore()
            try {
                val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                        MovieViewModel::class.java -> MovieViewModel(app, SavedStateHandle()) as T
                        TorrserverViewModel::class.java -> TorrserverViewModel(app) as T
                        else -> error("Unexpected ViewModel")
                    }
                })
                val movies = provider[MovieViewModel::class.java]
                val servers = provider[TorrserverViewModel::class.java]
                servers.savePrimaryAddress("https://second.example.com")
                assertEquals(servers.config.value.primaryAddress, movies.torrserverAddress.value)
                servers.disableTorrserver()
                assertEquals("", movies.torrserverAddress.value)
                assertEquals(oldRevision + 2L, settings.getLong("torrserver_selection_revision", 0L))
            } finally {
                store.clear()
                settings.edit().putString("torrserver_address", oldAddress)
                    .putLong("torrserver_selection_revision", oldRevision).commit()
            }
        }
    }

    @Test fun chooserCannotRememberThisApplicationAsItsOwnMagnetHandler() {
        val app = ApplicationProvider.getApplicationContext<MovieTorrentApplication>()
        val oldChoice = PreferredPlayerStore.get(app, PreferredPlayerStore.TYPE_MAGNET)
        try {
            PreferredPlayerStore.clear(app, PreferredPlayerStore.TYPE_MAGNET)
            val callback = Intent(PreferredPlayerStore.SELECTION_CALLBACK_ACTION)
                .putExtra(PreferredPlayerStore.EXTRA_SELECTION_TYPE, PreferredPlayerStore.TYPE_MAGNET)
                .putExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName(app, MainActivity::class.java))
            PlayerSelectionReceiver().onReceive(app, callback)
            assertNull(PreferredPlayerStore.get(app, PreferredPlayerStore.TYPE_MAGNET))
        } finally {
            if (oldChoice != null) PreferredPlayerStore.save(app, oldChoice, PreferredPlayerStore.TYPE_MAGNET)
            else PreferredPlayerStore.clear(app, PreferredPlayerStore.TYPE_MAGNET)
        }
    }
}

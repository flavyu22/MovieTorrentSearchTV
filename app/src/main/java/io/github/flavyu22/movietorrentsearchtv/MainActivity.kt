package io.github.flavyu22.movietorrentsearchtv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import io.github.flavyu22.movietorrentsearchtv.security.SecurityUtils
import io.github.flavyu22.movietorrentsearchtv.ui.app.MovieTorrentApp
import io.github.flavyu22.movietorrentsearchtv.ui.app.rememberMovieTorrentAppState
import io.github.flavyu22.movietorrentsearchtv.ui.locals.LocalAppLanguage
import io.github.flavyu22.movietorrentsearchtv.ui.locals.LocalTranslationStrings
import io.github.flavyu22.movietorrentsearchtv.ui.theme.MovieTorrentSearchTVTheme
import io.github.flavyu22.movietorrentsearchtv.viewmodel.AppViewModel
import io.github.flavyu22.movietorrentsearchtv.viewmodel.AppViewModelFactory
import kotlinx.coroutines.launch

/**
 * MainActivity — Versiune Optimizată Ultra Profesional
 */
class MainActivity : ComponentActivity() {

    // ViewModel-ul este creat o singură dată și supraviețuiește schimbărilor de configurare.
    private val appViewModel: AppViewModel by viewModels {
        AppViewModelFactory(SecurityUtils.getAuthPreferences(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. Splash screen API – elimină flash-ul alb. Trebuie apelat ÎNAINTE de super.onCreate.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // 2. Edge-to-edge – esențial pe Android TV pentru a folosi full screen.
        enableEdgeToEdge()

        // 2a. Dezactivăm sunetele de sistem (click/beep) la navigarea cu D-pad
        // peste postere — cerință UX: navigarea TV trebuie să fie complet silențioasă.
        window.decorView.isSoundEffectsEnabled = false

        // 2b. Full immersive mode – ascunde automat bara de status (sus)
        // și bara de navigare (jos) pe telefoane și tablete.
        // Barele revin temporar la swipe și se ascund automat din nou.
        enableImmersiveMode()

        // 3. Ține splash-ul afișat până când limba/strings-urile sunt încărcate.
        splash.setKeepOnScreenCondition { !appViewModel.isReady }

        // 4. Procesăm intent-ul DOAR o dată, în afara compoziției.
        if (savedInstanceState == null) handleIntent(intent)

        // 5. Observe UI events (like auto-exit after playback).
        // NOTE: collected in a plain lifecycleScope (NOT repeatOnLifecycle) on purpose:
        // while the external player shows the movie this activity is STOPPED, so a
        // lifecycle-gated collector would be cancelled and would never receive the
        // two-minute ExitApp event. The coroutine below stays alive until destroy,
        // which lets the app close itself in the background and free resources.
        lifecycleScope.launch {
            appViewModel.uiEvents.collect { event ->
                if (event is AppViewModel.UiEvent.ExitApp) {
                    finishAffinity()
                }
            }
        }

        setContent {
            // 2c. Sunetul de „click” la navigarea cu D-pad este redat chiar de
            // AndroidComposeView (view-ul Compose), NU de decorView — de aceea
            // dezactivarea din onCreate nu era suficientă. LocalView în compoziție
            // este exact AndroidComposeView, deci dezactivăm sunetul direct pe el.
            val composeView = LocalView.current
            SideEffect { composeView.isSoundEffectsEnabled = false }

            val appState by appViewModel.appState.collectAsStateWithLifecycle(
                minActiveState = Lifecycle.State.STARTED
            )

            CompositionLocalProvider(
                LocalAppLanguage provides appState.languageCode,
                LocalTranslationStrings provides appState.strings,
            ) {
                MovieTorrentSearchTVTheme(dynamicColor = false) {
                    val navController = rememberNavController()
                    
                    // Activity lifecycle owns process cleanup; do not kill the process manually.
                    val onExit = remember { 
                        { 
                            finishAffinity()
                        } 
                    }
                    
                    val appStateHolder = rememberMovieTorrentAppState(
                        navController = navController,
                        onExitApp = onExit
                    )

                    MovieTorrentApp(
                        modifier = Modifier.fillMaxSize(),
                        appState = appStateHolder,
                        appUiState = appState,
                        viewModel = appViewModel
                    )
                }
            }
        }
    }

    // 6. Tratează magnet-urile primite când Activity-ul există deja (singleTask / singleTop).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri: Uri = intent.data ?: return
        if (uri.scheme.equals("magnet", ignoreCase = true)) {
            appViewModel.setPendingMagnet(uri.toString())
        }
    }

    /**
     * Ascunde bara de status (sus) și bara de navigare (jos) în mod
     * „immersive sticky”. Barele apar temporar când utilizatorul face swipe
     * de la margine și se ascund automat după câteva secunde.
     */
    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Reaplicăm ascunderea barelor când fereastra primește / pierde focusul
        // (de exemplu după un dialog, notificare, etc.). Inset-urile raportează
        // vizibilitatea barelor fără listener-urile deprecated systemUiVisibility.
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            if (insets.isVisible(WindowInsetsCompat.Type.systemBars())) {
                // Barele au devenit vizibile – le ascundem din nou după un scurt delay
                window.decorView.postDelayed({
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                }, 2000L)
            }
            insets
        }
    }
}

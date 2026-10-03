package io.github.flavyu22.movietorrentsearchtv.navigation

import androidx.compose.animation.core.EaseInOutQuart
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.flavyu22.movietorrentsearchtv.ui.locals.LocalAppLanguage
import io.github.flavyu22.movietorrentsearchtv.ui.screens.MovieDetailsScreen
import io.github.flavyu22.movietorrentsearchtv.ui.screens.MovieSearchApp
import io.github.flavyu22.movietorrentsearchtv.ui.screens.WebViewScreen
import io.github.flavyu22.movietorrentsearchtv.viewmodel.AppViewModel
import io.github.flavyu22.movietorrentsearchtv.viewmodel.MovieViewModel

// ─── Rute definite ca constante ───────────────────────────────────────────────
// Evită string-uri magice răspândite prin cod.
// Construirea rutelor cu argumente se face EXCLUSIV prin funcțiile din Routes.
object Routes {
    const val HOME = "home"
    const val DETAILS = "details/{movieId}"
    const val BROWSER = "browser/{movieId}"

    fun details(movieId: Long) = "details/$movieId"

    /**
     * NU mai pasăm URL-ul în ruta de navigare (URLEncoder fragil, caractere speciale scapă).
     * În schimb pasăm movieId — WebViewScreen preia URL-ul din ViewModel.
     */
    fun browser(movieId: Long) = "browser/$movieId"
}

// ─── AppNavGraph ───────────────────────────────────────────────────────────────
// Responsabilitate unică: definirea grafului de navigare
@Composable
fun AppNavGraph(
    navController: NavHostController,
    appViewModel: AppViewModel,
    movieViewModel: MovieViewModel,
    onLogoutRequest: () -> Unit,
) {
    val currentLanguageCode = LocalAppLanguage.current

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        // ── Ultra-Fluid Transitions (TV-optimized) ───────────────────────────
        enterTransition = {
            fadeIn(animationSpec = tween(250, easing = EaseInOutQuart)) +
            scaleIn(initialScale = 0.95f, animationSpec = tween(250, easing = EaseInOutQuart))
        },
        exitTransition = {
            fadeOut(animationSpec = tween(200, easing = EaseInOutQuart)) +
            scaleOut(targetScale = 1.05f, animationSpec = tween(200, easing = EaseInOutQuart))
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(250, easing = EaseInOutQuart)) +
            scaleIn(initialScale = 1.05f, animationSpec = tween(250, easing = EaseInOutQuart))
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(200, easing = EaseInOutQuart)) +
            scaleOut(targetScale = 0.95f, animationSpec = tween(200, easing = EaseInOutQuart))
        }
    ) {

        // ── Home ──────────────────────────────────────────────────────────────
        composable(Routes.HOME) {
            MovieSearchApp(
                viewModel = movieViewModel,
                appViewModel = appViewModel,
                currentLanguageCode = currentLanguageCode,
                onLanguageChange = { appViewModel.setLanguage(it) },
                onLogoutRequest = onLogoutRequest,
                onMovieClick = { movie ->
                    movieViewModel.selectMovie(movie)
                    navController.navigate(Routes.details(movie.id))
                },
                onHistoryRequest = {
                    movieViewModel.loadHistoryMovies()
                },
                onFavoritesRequest = {
                    movieViewModel.loadFavoritesMovies()
                },
            )
        }

        // ── Detalii film ──────────────────────────────────────────────────────
        composable(
            route = Routes.DETAILS,
            arguments = listOf(
                navArgument("movieId") { type = NavType.LongType }
            ),
        ) { backStackEntry ->
            val movieId = backStackEntry.arguments?.getLong("movieId") ?: return@composable
            val movie = movieViewModel.findMovie(movieId)
            if (movie == null) {
                androidx.compose.runtime.LaunchedEffect(movieId) {
                    navController.popBackStack()
                }
                return@composable
            }

            MovieDetailsScreen(
                movie = movie,
                viewModel = movieViewModel,
                langCode = currentLanguageCode,
                onBack = { navController.popBackStack() },
                onTriggerPlayback = {
                    appViewModel.onPlaybackTriggered()
                },
                onOpenBrowser = { url ->
                    movieViewModel.setPendingUrl(url)
                    navController.navigate(Routes.browser(movieId))
                },
            )
        }

        // ── Browser (WebView) ─────────────────────────────────────────────────
        // URL-ul nu mai trece prin rută — e citit din ViewModel
        composable(
            route = Routes.BROWSER,
            arguments = listOf(
                navArgument("movieId") { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            // Consuming ViewModel state and navigating are side effects. Keeping the resolved
            // value saveable also prevents an Activity recreation from consuming it twice.
            var resolvedUrl by rememberSaveable(backStackEntry.id) {
                mutableStateOf<String?>(null)
            }
            var resolutionComplete by rememberSaveable(backStackEntry.id) {
                mutableStateOf(false)
            }

            LaunchedEffect(backStackEntry.id, resolutionComplete) {
                if (!resolutionComplete) {
                    resolvedUrl = movieViewModel.consumePendingUrl()
                    resolutionComplete = true
                }
                if (resolutionComplete && resolvedUrl == null) {
                    navController.popBackStack()
                }
            }

            resolvedUrl?.let { url ->
                WebViewScreen(
                    url = url,
                    onBack = { navController.popBackStack() },
                    onMagnetPlayed = { appViewModel.onPlaybackTriggered() },
                )
            }
        }
    }
}

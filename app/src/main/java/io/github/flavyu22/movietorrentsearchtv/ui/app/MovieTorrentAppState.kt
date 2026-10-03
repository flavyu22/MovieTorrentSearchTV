package io.github.flavyu22.movietorrentsearchtv.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController

/**
 * Clasă @Stable care deține starea UI pură (nu business logic).
 * Separația permite ca schimbările aici să nu invalideze ViewModel-ul.
 */
@Stable
class MovieTorrentAppState(
    val navController: NavHostController,
    private val onExitApp: () -> Unit
) {
    // Dialog state — UI pur, nu are nevoie de ViewModel
    var showExitDialog by mutableStateOf(false)
    
    var showLogoutDialog by mutableStateOf(false)

    // Expunem setteri controlați
    fun showExit() { showExitDialog = true }
    fun dismissExit() { showExitDialog = false }
    fun showLogout() { showLogoutDialog = true }
    fun dismissLogout() { showLogoutDialog = false }
    
    fun exitApp() = onExitApp()
}

@Composable
fun rememberMovieTorrentAppState(
    navController: NavHostController,
    onExitApp: () -> Unit
): MovieTorrentAppState = remember(navController) {
    MovieTorrentAppState(navController, onExitApp)
}

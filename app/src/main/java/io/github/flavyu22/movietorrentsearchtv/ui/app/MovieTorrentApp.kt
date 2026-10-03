package io.github.flavyu22.movietorrentsearchtv.ui.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import io.github.flavyu22.movietorrentsearchtv.navigation.AppNavGraph
import io.github.flavyu22.movietorrentsearchtv.playback.PlaybackIntentHelper
import io.github.flavyu22.movietorrentsearchtv.playback.PlaybackResult
import io.github.flavyu22.movietorrentsearchtv.playback.TorrserverPlaybackManager
import io.github.flavyu22.movietorrentsearchtv.ui.dialogs.ExitDialog
import io.github.flavyu22.movietorrentsearchtv.ui.dialogs.LogoutDialog
import io.github.flavyu22.movietorrentsearchtv.ui.dialogs.UpdateDialog
import io.github.flavyu22.movietorrentsearchtv.ui.screens.LoginScreen
import io.github.flavyu22.movietorrentsearchtv.ui.theme.AlmostBlack
import io.github.flavyu22.movietorrentsearchtv.viewmodel.AppViewModel
import io.github.flavyu22.movietorrentsearchtv.viewmodel.MovieViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
fun MovieTorrentApp(
    modifier: Modifier = Modifier,
    appState: MovieTorrentAppState,
    appUiState: AppViewModel.AppUiState,
    viewModel: AppViewModel,
    onPlaybackForeground: () -> Unit = {},
    onPlaybackBackground: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val shouldShowApp = appUiState.isLoggedIn
    val movieViewModel: MovieViewModel = viewModel()
    val playbackScope = rememberCoroutineScope()
    var magnetPlaybackJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    onPlaybackForeground()
                    viewModel.onAppForegrounded()
                }
                Lifecycle.Event.ON_STOP -> {
                    // Lifecycle-aware collection pauses while stopped, so cancel the
                    // in-flight resolver directly instead of waiting for recomposition.
                    magnetPlaybackJob?.cancel()
                    magnetPlaybackJob = null
                    onPlaybackBackground()
                    viewModel.onAppBackgrounded()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(shouldShowApp) {
        onDispose {
            if (shouldShowApp) {
                magnetPlaybackJob?.cancel()
                magnetPlaybackJob = null
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = AlmostBlack) {
        Box(modifier = modifier) {
            AnimatedVisibility(
                visible = !shouldShowApp,
                enter = fadeIn(animationSpec = tween(250)),
                exit = fadeOut(animationSpec = tween(200)),
            ) {
                LoginScreen(
                    s = appUiState.strings,
                    onSubmit = viewModel::submitCredential,
                    savedUsername = appUiState.savedUsername,
                    isSetupMode = !appUiState.isCredentialConfigured,
                    authState = appUiState.authState,
                    onInputChanged = viewModel::clearAuthFeedback,
                    onResetCredential = viewModel::resetCorruptCredential,
                )
            }

            AnimatedVisibility(
                visible = shouldShowApp,
                enter = fadeIn(animationSpec = tween(250)),
                exit = fadeOut(animationSpec = tween(200)),
            ) {
                AppNavGraph(
                    navController = appState.navController,
                    appViewModel = viewModel,
                    movieViewModel = movieViewModel,
                ) { appState.showLogout() }
            }

            if (shouldShowApp) {
                val torrserverAddress by
                    movieViewModel.torrserverAddress.collectAsStateWithLifecycle()
                val request = appUiState.magnetPlaybackRequest

                // A key change cancels the previous resolution. The manager bridges
                // coroutine cancellation to OkHttp Call.cancel(), so only one external
                // magnet job can reach a player launch.
                LaunchedEffect(request?.id) {
                    magnetPlaybackJob?.cancel()
                    request ?: return@LaunchedEffect
                    magnetPlaybackJob = playbackScope.launch {
                        val runningJob = currentCoroutineContext()[Job]
                        try {
                            val result = if (torrserverAddress.isBlank()) {
                                PlaybackResult.Fallback(
                                    request.magnet,
                                    appUiState.strings.magnetSources,
                                )
                            } else {
                                try {
                                    TorrserverPlaybackManager(torrserverAddress)
                                        .resolveStream(
                                            request.magnet,
                                            appUiState.strings.magnetSources,
                                        )
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (_: Exception) {
                                    PlaybackResult.Error(
                                        "",
                                        request.magnet,
                                        appUiState.strings.magnetSources,
                                    )
                                }
                            }

                            currentCoroutineContext().ensureActive()
                            viewModel.onPlaybackTriggered()
                            when (result) {
                                is PlaybackResult.Stream -> PlaybackIntentHelper.playStream(
                                    context,
                                    result.url,
                                    result.title,
                                    appUiState.strings,
                                )
                                is PlaybackResult.Fallback -> PlaybackIntentHelper.playFallback(
                                    context,
                                    result.magnetUrl,
                                    result.title,
                                    true,
                                    appUiState.strings,
                                )
                                is PlaybackResult.Error -> PlaybackIntentHelper.playFallback(
                                    context,
                                    result.magnetUrl,
                                    result.title,
                                    true,
                                    appUiState.strings,
                                )
                            }
                        } finally {
                            viewModel.completeMagnetPlayback(request.id)
                            if (magnetPlaybackJob == runningJob) magnetPlaybackJob = null
                        }
                    }
                }
            }
        }

        if (shouldShowApp && appUiState.pendingMagnet != null) {
            AlertDialog(
                onDismissRequest = viewModel::dismissPendingMagnet,
                title = { Text(appUiState.strings.magnetConfirmTitle) },
                text = { Text(appUiState.strings.magnetConfirmMessage) },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmPendingMagnet) {
                        Text(appUiState.strings.yes)
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissPendingMagnet) {
                        Text(appUiState.strings.no)
                    }
                },
            )
        }

        if (appState.showExitDialog) {
            ExitDialog(
                s = appUiState.strings,
                onConfirm = appState::exitApp,
                onDismiss = appState::dismissExit,
            )
        }

        if (appState.showLogoutDialog) {
            LogoutDialog(
                s = appUiState.strings,
                onConfirm = {
                    viewModel.logout()
                    appState.dismissLogout()
                },
                onDismiss = appState::dismissLogout,
            )
        }

        val updateDialogsAllowed = shouldShowApp &&
            appUiState.pendingMagnet == null &&
            !appState.showExitDialog &&
            !appState.showLogoutDialog
        val availableUpdate = appUiState.updateState as? AppViewModel.UpdateState.Available
        AnimatedVisibility(visible = updateDialogsAllowed && availableUpdate != null) {
            availableUpdate?.let {
                UpdateDialog(
                    s = appUiState.strings,
                    onConfirm = viewModel::installAvailableUpdate,
                    onDismiss = viewModel::dismissUpdate,
                )
            }
        }

        val updateDownloadingVisible = updateDialogsAllowed &&
            appUiState.updateState is AppViewModel.UpdateState.Downloading
        if (updateDownloadingVisible) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(appUiState.strings.updateTitle) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(appUiState.strings.checkingUpdates)
                    }
                },
                confirmButton = {},
            )
        }

        val updateErrorVisible = updateDialogsAllowed &&
            appUiState.updateState is AppViewModel.UpdateState.Error
        if (updateErrorVisible) {
            AlertDialog(
                onDismissRequest = viewModel::dismissUpdate,
                title = { Text(appUiState.strings.updateTitle) },
                text = { Text(appUiState.strings.updateOperationFailed) },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissUpdate) {
                        Text(appUiState.strings.cancel)
                    }
                },
            )
        }

        val updateDialogVisible = (updateDialogsAllowed && availableUpdate != null) ||
            updateDownloadingVisible || updateErrorVisible
        val currentBackStackEntry by appState.navController.currentBackStackEntryAsState()
        val isAtNavigationRoot = currentBackStackEntry == null ||
            currentBackStackEntry?.destination?.route ==
                io.github.flavyu22.movietorrentsearchtv.navigation.Routes.HOME
        BackHandler(
            enabled = !shouldShowApp ||
                appUiState.pendingMagnet != null ||
                appState.showExitDialog ||
                appState.showLogoutDialog ||
                updateDialogVisible ||
                isAtNavigationRoot,
        ) {
            when {
                shouldShowApp && appUiState.pendingMagnet != null ->
                    viewModel.dismissPendingMagnet()
                appState.showExitDialog -> appState.dismissExit()
                appState.showLogoutDialog -> appState.dismissLogout()
                updateDialogVisible -> viewModel.dismissUpdate()
                !shouldShowApp -> appState.exitApp()
                isAtNavigationRoot -> appState.showExit()
            }
        }
    }
}

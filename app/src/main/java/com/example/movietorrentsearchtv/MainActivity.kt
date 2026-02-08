package com.example.movietorrentsearchtv

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.movietorrentsearchtv.model.Movie
import com.example.movietorrentsearchtv.ui.theme.*
import com.example.movietorrentsearchtv.viewmodel.MovieViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.roundToInt

val SoftBlue = Color(0xFF42A5F5)

// Liste statice pentru a evita recalcularea la fiecare recompozitie
val GENRES_LIST = listOf("All", "Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy", "History", "Horror", "Music", "Mystery", "Romance", "Sci-Fi", "Thriller", "War", "Western")
val YEARS_LIST = listOf("All") + (2026 downTo 1950).map { it.toString() }
val QUALITIES_LIST = listOf("All", "2160p", "1080p", "720p")

data class AppStrings(
    val yes: String, val no: String, val exitTitle: String, val exitMessage: String,
    val genres: String, val years: String, val quality: String, val searchPlaceholder: String,
    val movies: String, val series: String, val changeLanguage: String, val update: String,
    val checkingUpdates: String, val about: String, val aboutTitle: String, val aboutText: String,
    val prev: String, val next: String, val page: String, val back: String,
    val magnetSources: String, val noSources: String, val voiceLang: String,
    val langName: String, val developer: String
)

val Translations = mapOf(
    "RO" to AppStrings(
        yes = "Da", no = "Nu", exitTitle = "Ieșire?", exitMessage = "Vrei să închizi aplicația?",
        genres = "GENURI", years = "ANI", quality = "CALITATE", searchPlaceholder = "Caută...",
        movies = "Filme", series = "Seriale", changeLanguage = "Schimbă Limba", update = "Update",
        checkingUpdates = "Căutare actualizări...", about = "Despre", aboutTitle = "Despre Aplicație",
        aboutText = "MovieTorrentSearchTV v1.5\nCăutare Filme și Seriale.\nOptimizat special pentru Android TV.",
        prev = "Prev.", next = "Urm.", page = "Pagina", back = "Înapoi",
        magnetSources = "Surse Magnet:", noSources = "Nicio sursă găsită.", voiceLang = "ro-RO",
        langName = "Română", developer = "Dezvoltator: OxigenForFlower"
    ),
    "EN" to AppStrings(
        yes = "Yes", no = "No", exitTitle = "Exit?", exitMessage = "Do you want to close the app?",
        genres = "GENRES", years = "YEARS", quality = "QUALITY", searchPlaceholder = "Search...",
        movies = "Movies", series = "Series", changeLanguage = "Change Language", update = "Update",
        checkingUpdates = "Checking for updates...", about = "About", aboutTitle = "About App",
        aboutText = "MovieTorrentSearchTV v1.5\nSearch Movies and Series.\nOptimized for Android TV.",
        prev = "Prev.", next = "Next", page = "Page", back = "Back",
        magnetSources = "Magnet Sources:", noSources = "No sources found.", voiceLang = "en-US",
        langName = "English", developer = "Developer: OxigenForFlower"
    ),
    "RU" to AppStrings(
        yes = "Да", no = "Нет", exitTitle = "Выход?", exitMessage = "Вы хотите закрыть приложение?",
        genres = "ЖАНРЫ", years = "ГОДЫ", quality = "КАЧЕСТВО", searchPlaceholder = "Поиск...",
        movies = "Фильмы", series = "Сериалы", changeLanguage = "Сменить язык", update = "Обновить",
        checkingUpdates = "Проверка обновлений...", about = "О приложении", aboutTitle = "О приложении",
        aboutText = "MovieTorrentSearchTV v1.5\nПоиск фильмов и сериалов.\nОптимизировано для Android TV.",
        prev = "Пред.", next = "След.", page = "Страница", back = "Назад",
        magnetSources = "Магнит-ссылки:", noSources = "Источники не найдены.", voiceLang = "ru-RU",
        langName = "Русский", developer = "Разработчик: OxigenForFlower"
    ),
    "ES" to AppStrings(
        yes = "Sí", no = "No", exitTitle = "¿Salir?", exitMessage = "¿Quieres cerrar la application?",
        genres = "GÉNEROS", years = "AÑOS", quality = "CALIDAD", searchPlaceholder = "Buscar...",
        movies = "Películas", series = "Series", changeLanguage = "Cambiar Idioma", update = "Actualizar",
        checkingUpdates = "Buscando actualizaciones...", about = "Acerca de", aboutTitle = "Acerca de la Application",
        aboutText = "MovieTorrentSearchTV v1.5\nBúsqueda de Películas y Series.\nOptimizado para Android TV.",
        prev = "Ant.", next = "Sig.", page = "Página", back = "Volver",
        magnetSources = "Fuentes Magnet:", noSources = "No se encontraron fuentes.", voiceLang = "es-ES",
        langName = "Español", developer = "Desarrollador: OxigenForFlower"
    ),
    "FR" to AppStrings(
        yes = "Oui", no = "Non", exitTitle = "Quitter ?", exitMessage = "Voulez-vous fermer l'application ?",
        genres = "GENRES", years = "ANNÉES", quality = "QUALITÉ", searchPlaceholder = "Rechercher...",
        movies = "Films", series = "Séries", changeLanguage = "Changer de Langue", update = "Mettre à jour",
        checkingUpdates = "Recherche de mises à jour...", about = "À propos", aboutTitle = "À propos de l'application",
        aboutText = "MovieTorrentSearchTV v1.5\nRecherche de films et de series.\nOptimisé pentru Android TV.",
        prev = "Préc.", next = "Suiv.", page = "Page", back = "Retour",
        magnetSources = "Sources Magnet :", noSources = "Aucune source trouvée.", voiceLang = "fr-FR",
        langName = "Français", developer = "Développeur : OxigenForFlower"
    ),
    "DE" to AppStrings(
        yes = "Ja", no = "Nein", exitTitle = "Beenden?", exitMessage = "Möchten Sie die App schließen?",
        genres = "GENRES", years = "JAHRE", quality = "QUALITÄT", searchPlaceholder = "Suchen...",
        movies = "Filme", series = "Serien", changeLanguage = "Sprache ändern", update = "Update",
        checkingUpdates = "Suche nach Updates...", about = "Über", aboutTitle = "Über die App",
        aboutText = "MovieTorrentSearchTV v1.5\nSuche nach Filmen und Serien.\nOptimiert für Android TV.",
        prev = "Zurück", next = "Weiter", page = "Seite", back = "Zurück",
        magnetSources = "Magnet-Quellen:", noSources = "Keine Quellen gefunden.", voiceLang = "de-DE",
        langName = "Deutsch", developer = "Entwickler: OxigenForFlower"
    ),
    "IT" to AppStrings(
        yes = "Sì", no = "No", exitTitle = "Esci?", exitMessage = "Vuoi chiudere l'applicazione?",
        genres = "GENERI", years = "ANNI", quality = "QUALITÀ", searchPlaceholder = "Cerca...",
        movies = "Film", series = "Serie TV", changeLanguage = "Cambia Lingua", update = "Aggiorna",
        checkingUpdates = "Ricerca aggiornamenti...", about = "Info", aboutTitle = "Info sull'app",
        aboutText = "MovieTorrentSearchTV v1.5\nRicerca Film e Serie TV.\nOttimizzato pentru Android TV.",
        prev = "Prec.", next = "Succ.", page = "Pagina", back = "Indietro",
        magnetSources = "Fonti Magnet:", noSources = "Nessuna fonte trovata.", voiceLang = "it-IT",
        langName = "Italiano", developer = "Sviluppatore: OxigenForFlower"
    )
)

class MainActivity : ComponentActivity() {
    private var playbackTimerJob: Job? = null
    private var isWaitingForPlayback = false

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var currentLanguageCode by remember { mutableStateOf("EN") }
            val s = remember(currentLanguageCode) { Translations[currentLanguageCode] ?: Translations["EN"]!! }

            MovieTorrentSearchTVTheme(dynamicColor = false) { // Dezactivam dynamic color pentru viteza
                CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                    Surface(modifier = Modifier.fillMaxSize(), color = AlmostBlack) {
                        val navController = rememberNavController()
                        val viewModel: MovieViewModel = viewModel()
                        var showExitDialog by remember { mutableStateOf(false) }

                        BackHandler {
                            if (navController.previousBackStackEntry == null) showExitDialog = true else navController.popBackStack()
                        }

                        if (showExitDialog) {
                            ExitDialog(s, onConfirm = { closeAppCompletely() }, onDismiss = { showExitDialog = false })
                        }

                        NavHost(navController = navController, startDestination = "home") {
                            composable("home") {
                                MovieSearchApp(viewModel, currentLanguageCode, onLanguageChange = { currentLanguageCode = it }) { movie ->
                                    viewModel.lastClickedMovieId.value = movie.id
                                    navController.navigate("details/${movie.id}")
                                }
                            }
                            composable("details/{movieId}", arguments = listOf(navArgument("movieId") { type = NavType.LongType })) { backStackEntry ->
                                val movieId = backStackEntry.arguments?.getLong("movieId") ?: 0L
                                val movie = viewModel.movies.value.find { it.id == movieId }
                                movie?.let {
                                    MovieDetailsScreen(it, currentLanguageCode, onBack = { navController.popBackStack() }, onTriggerPlayback = { isWaitingForPlayback = true }) { url ->
                                        navController.navigate("browser/${URLEncoder.encode(url, "UTF-8")}")
                                    }
                                }
                            }
                            composable("browser/{url}", arguments = listOf(navArgument("url") { type = NavType.StringType })) { backStackEntry ->
                                WebViewScreen(URLDecoder.decode(backStackEntry.arguments?.getString("url") ?: "", "UTF-8")) { navController.popBackStack() }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); playbackTimerJob?.cancel(); isWaitingForPlayback = false }
    override fun onStop() { super.onStop(); if (isWaitingForPlayback) { playbackTimerJob = lifecycleScope.launch { delay(60000); closeAppCompletely() } } }
    private fun closeAppCompletely() { finishAndRemoveTask(); android.os.Process.killProcess(android.os.Process.myPid()) }
}

@Composable
fun ExitDialog(s: AppStrings, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { NetflixDialogButton(s.yes, onClick = onConfirm) },
        dismissButton = { NetflixDialogButton(s.no, onClick = onDismiss) },
        title = { Text(s.exitTitle, color = Color.White) },
        text = { Text(s.exitMessage, color = Grey) },
        containerColor = Color(0xFF1E1E1E)
    )
}

@Composable
fun AboutDialog(s: AppStrings, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { NetflixDialogButton(s.back, onClick = onDismiss) },
        title = { Text(s.aboutTitle, color = Color.White) },
        text = { 
            Column {
                Text(s.aboutText, color = Grey)
                Spacer(Modifier.height(16.dp))
                Text(s.developer, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
        containerColor = Color(0xFF1E1E1E)
    )
}

@Composable
fun NetflixDialogButton(text: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Button(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isFocused) Color.White else Color(0xFF333333)
        ),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(text, color = if (isFocused) Color.Black else Color.White)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieSearchApp(viewModel: MovieViewModel, langCode: String, onLanguageChange: (String) -> Unit, onMovieClick: (Movie) -> Unit) {
    val s = Translations[langCode] ?: Translations["EN"]!!
    val menuFocusRequester = remember { FocusRequester() }
    var showAboutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (viewModel.movies.value.isEmpty()) { viewModel.loadPopularMovies() }
        delay(10) // Redus delay-ul pentru focalizare mai rapida
        menuFocusRequester.requestFocus()
    }

    if (showAboutDialog) {
        AboutDialog(s, onDismiss = { showAboutDialog = false })
    }

    Column(modifier = Modifier.fillMaxSize().background(AlmostBlack).padding(horizontal = 24.dp, vertical = 16.dp)) {
        HeaderRow(viewModel, s, langCode, onLanguageChange, menuFocusRequester, onShowAbout = { showAboutDialog = true })
        Spacer(Modifier.height(8.dp))
        FilterRow(viewModel)
        Spacer(Modifier.height(8.dp))
        
        Box(modifier = Modifier.fillMaxSize()) {
            if (viewModel.isLoading.value) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SoftBlue, strokeWidth = 4.dp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(7),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(items = viewModel.movies.value, key = { it.id }) { movie -> 
                        NetflixMovieItem(movie, onMovieClick)
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { PaginationRow(viewModel, s) }
                }
            }
        }
    }
}

@Composable
fun NetflixMovieItem(movie: Movie, onMovieClick: (Movie) -> Unit) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    
    // Optimizarea ImageRequest: pre-calculat si salvat in memorie
    val imageRequest = remember(movie.id) {
        ImageRequest.Builder(context)
            .data(movie.mediumPosterPath ?: movie.posterPath)
            .size(200, 300) // Dimensiune mica fixata pentru RAM mic pe TV
            .crossfade(true)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    val scale by animateFloatAsState(targetValue = if (isFocused) 1.12f else 1f, animationSpec = tween(150), label = "scale")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A1A1A))
            .border(2.dp, if (isFocused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null) { onMovieClick(movie) }
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f/3f),
            contentScale = ContentScale.Crop
        )
        Box(modifier = Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
            if (isFocused) {
                Text(movie.title ?: "", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun HeaderRow(viewModel: MovieViewModel, s: AppStrings, langCode: String, onLanguageChange: (String) -> Unit, focusRequester: FocusRequester, onShowAbout: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    var showLangMenu by remember { mutableStateOf(false) }
    var showGenreMenu by remember { mutableStateOf(false) }
    var showYearMenu by remember { mutableStateOf(false) }
    var showQualityMenu by remember { mutableStateOf(false) }

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0)
            if (!spokenText.isNullOrEmpty()) viewModel.searchMovies(spokenText, viewModel.selectedGenre.value, viewModel.selectedYear.value, viewModel.selectedQuality.value)
        }
    }

    Row(modifier = Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            val menuInteractionSource = remember { MutableInteractionSource() }
            val isMenuFocused by menuInteractionSource.collectIsFocusedAsState()
            
            IconButton(
                onClick = { showMenu = true; showLangMenu = false }, 
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .size(48.dp)
                    .background(
                        color = if (isMenuFocused) Color.White else Color.Transparent,
                        shape = CircleShape
                    ),
                interactionSource = menuInteractionSource
            ) {
                Icon(
                    Icons.Default.MoreVert, 
                    null, 
                    tint = if (isMenuFocused) Color.Black else Color.White
                )
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(Color(0xFF1E1E1E))) {
                if (!showLangMenu) {
                    DropdownMenuItem(text = { Text(s.movies, color = Color.White) }, onClick = { showMenu = false; viewModel.loadPopularMovies(isSeries = false) }, leadingIcon = { Icon(Icons.Default.Movie, null, tint = SoftBlue) })
                    DropdownMenuItem(text = { Text(s.series, color = Color.White) }, onClick = { showMenu = false; viewModel.loadPopularMovies(isSeries = true) }, leadingIcon = { Icon(Icons.Default.Tv, null, tint = SoftBlue) })
                    Divider(color = Color.DarkGray)
                    DropdownMenuItem(text = { Text(s.changeLanguage, color = Color.White) }, onClick = { showLangMenu = true }, leadingIcon = { Icon(Icons.Default.Language, null, tint = SoftBlue) })
                    DropdownMenuItem(text = { Text(s.about, color = Color.White) }, onClick = { showMenu = false; onShowAbout() }, leadingIcon = { Icon(Icons.Default.Info, null, tint = SoftBlue) })
                } else {
                    DropdownMenuItem(text = { Text(s.back, color = Grey) }, onClick = { showLangMenu = false }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Grey) })
                    Translations.forEach { (code, trans) ->
                        DropdownMenuItem(text = { Text(trans.langName, color = if (langCode == code) SoftBlue else Color.White) }, onClick = { showMenu = false; showLangMenu = false; onLanguageChange(code) })
                    }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        
        // GENRES
        Box {
            NetflixHeaderButton(s.genres, viewModel.selectedGenre.value != "All") { showGenreMenu = true }
            DropdownMenu(expanded = showGenreMenu, onDismissRequest = { showGenreMenu = false }, modifier = Modifier.heightIn(max = 400.dp).background(Color(0xFF1E1E1E))) {
                GENRES_LIST.forEach { genre ->
                    DropdownMenuItem(text = { Text(genre, color = if (viewModel.selectedGenre.value == genre) SoftBlue else Color.White) }, onClick = { showGenreMenu = false; viewModel.searchMovies(viewModel.searchQuery.value, genre, viewModel.selectedYear.value, viewModel.selectedQuality.value) })
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        
        // YEARS
        Box {
            NetflixHeaderButton(s.years, viewModel.selectedYear.value != "All") { showYearMenu = true }
            DropdownMenu(expanded = showYearMenu, onDismissRequest = { showYearMenu = false }, modifier = Modifier.heightIn(max = 400.dp).background(Color(0xFF1E1E1E))) {
                YEARS_LIST.forEach { year ->
                    DropdownMenuItem(text = { Text(year, color = if (viewModel.selectedYear.value == year) SoftBlue else Color.White) }, onClick = { showYearMenu = false; viewModel.searchMovies(viewModel.searchQuery.value, viewModel.selectedGenre.value, year, viewModel.selectedQuality.value) })
                }
            }
        }
        Spacer(Modifier.width(8.dp))

        // QUALITY
        Box {
            NetflixHeaderButton(s.quality, viewModel.selectedQuality.value != "All") { showQualityMenu = true }
            DropdownMenu(expanded = showQualityMenu, onDismissRequest = { showQualityMenu = false }, modifier = Modifier.background(Color(0xFF1E1E1E))) {
                QUALITIES_LIST.forEach { quality ->
                    DropdownMenuItem(text = { Text(quality, color = if (viewModel.selectedQuality.value == quality) SoftBlue else Color.White) }, onClick = { showQualityMenu = false; viewModel.searchMovies(viewModel.searchQuery.value, viewModel.selectedGenre.value, viewModel.selectedYear.value, quality) })
                }
            }
        }
        
        Spacer(Modifier.weight(1f))
        
        val micInteractionSource = remember { MutableInteractionSource() }
        val isMicFocused by micInteractionSource.collectIsFocusedAsState()
        IconButton(
            onClick = { 
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { 
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, s.voiceLang)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, s.searchPlaceholder)
                }
                voiceLauncher.launch(intent)
            },
            modifier = Modifier
                .background(
                    color = if (isMicFocused) Color.White else Color.Transparent,
                    shape = CircleShape
                ),
            interactionSource = micInteractionSource
        ) { 
            Icon(
                Icons.Default.Mic, 
                null, 
                tint = if (isMicFocused) Color.Black else Color.White
            ) 
        }
        
        OutlinedTextField(
            value = viewModel.searchQuery.value,
            onValueChange = { viewModel.searchQuery.value = it },
            placeholder = { Text(s.searchPlaceholder, color = Grey, fontSize = 14.sp) },
            modifier = Modifier.width(300.dp).height(50.dp),
            shape = RoundedCornerShape(25.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                focusedContainerColor = Color(0xFF333333),
                unfocusedContainerColor = Color.Transparent,
                focusedBorderColor = Color.White
            ),
            singleLine = true,
            keyboardActions = KeyboardActions(onSearch = { viewModel.searchMovies(viewModel.searchQuery.value, viewModel.selectedGenre.value, viewModel.selectedYear.value, viewModel.selectedQuality.value) })
        )
    }
}

@Composable
fun FilterRow(viewModel: MovieViewModel) {
    if (viewModel.selectedGenre.value != "All" || viewModel.selectedYear.value != "All" || viewModel.selectedQuality.value != "All") {
        Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (viewModel.selectedGenre.value != "All") {
                AssistChip(onClick = {}, label = { Text(viewModel.selectedGenre.value, color = Color.White) }, colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFF333333)))
                Spacer(Modifier.width(8.dp))
            }
            if (viewModel.selectedYear.value != "All") {
                AssistChip(onClick = {}, label = { Text(viewModel.selectedYear.value, color = Color.White) }, colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFF333333)))
                Spacer(Modifier.width(8.dp))
            }
            if (viewModel.selectedQuality.value != "All") {
                AssistChip(onClick = {}, label = { Text(viewModel.selectedQuality.value, color = Color.White) }, colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFF333333)))
            }
        }
    }
}

@Composable
fun MovieDetailsScreen(movie: Movie, langCode: String, onBack: () -> Unit, onTriggerPlayback: () -> Unit, onOpenBrowser: (String) -> Unit) {
    val viewModel: MovieViewModel = viewModel()
    val s = Translations[langCode] ?: Translations["EN"]!!
    val context = LocalContext.current
    var torrents by remember { mutableStateOf<List<com.example.movietorrentsearchtv.model.Torrent>?>(null) }
    val firstFocus = remember { FocusRequester() }
    
    LaunchedEffect(movie.id) { torrents = viewModel.fetchTorrentsForMovie(movie) }
    LaunchedEffect(torrents) { if (!torrents.isNullOrEmpty()) { delay(100); firstFocus.requestFocus() } }

    Column(modifier = Modifier.fillMaxSize().background(AlmostBlack)) {
        // Antet fix pentru detalii (pentru ca butonul Back sa nu se miste)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 32.dp, top = 32.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NetflixHeaderButton(s.back, false, Icons.AutoMirrored.Filled.ArrowBack, onBack)
        }

        // Continut derulabil
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(movie.mediumPosterPath ?: movie.posterPath)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .width(220.dp)
                    .aspectRatio(2f/3f)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(48.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(movie.title ?: "", style = MaterialTheme.typography.headlineLarge, color = Color.White, fontWeight = FontWeight.Bold)
                Text("${movie.year} | ${movie.genres?.joinToString(", ")}", color = Grey, fontSize = 18.sp)
                Spacer(Modifier.height(32.dp))
                if (torrents == null) {
                    CircularProgressIndicator(color = SoftBlue)
                } else {
                    torrents?.forEachIndexed { index, torrent ->
                        val interactionSource = remember { MutableInteractionSource() }
                        val isFocused by interactionSource.collectIsFocusedAsState()
                        Button(
                            onClick = { 
                                onTriggerPlayback()
                                val uri = Uri.parse(torrent.url ?: "")
                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                if (uri.scheme == "magnet") {
                                    intent.addCategory(Intent.CATEGORY_BROWSABLE)
                                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    onOpenBrowser(torrent.url ?: "")
                                }
                            },
                            interactionSource = interactionSource,
                            modifier = Modifier.fillMaxWidth().then(if(index==0) Modifier.focusRequester(firstFocus) else Modifier),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isFocused) Color.White else Color(0xFF262626)
                            ),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "${torrent.quality} | ${torrent.size} | Seeds: ${torrent.seeds}", 
                                color = if (isFocused) Color.Black else Color.White
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

@Composable
fun NetflixHeaderButton(text: String, active: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Button(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isFocused) Color.White else if (active) Color(0xFF444444) else Color.Transparent
        ),
        shape = RoundedCornerShape(4.dp)
    ) {
        if (icon != null) {
            Icon(
                icon,
                null,
                modifier = Modifier.size(18.dp),
                tint = if (isFocused) Color.Black else Color.White
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            color = if (isFocused) Color.Black else Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun PaginationRow(viewModel: MovieViewModel, s: AppStrings) {
    Row(modifier = Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
        NetflixPaginationButton(s.prev, enabled = viewModel.currentPage.value > 1) { viewModel.goToPreviousPage() }
        Spacer(Modifier.width(24.dp))
        Text("${s.page} ${viewModel.currentPage.value}", color = Color.White)
        Spacer(Modifier.width(24.dp))
        NetflixPaginationButton(s.next) { viewModel.goToNextPage() }
    }
}

@Composable
fun NetflixPaginationButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isFocused) Color.White else Color(0xFF333333)
        ),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(text, color = if (isFocused) Color.Black else if (enabled) Color.White else Color.Gray)
    }
}

@Composable
fun WebViewScreen(url: String, onBack: () -> Unit) {
    var cursorX by remember { mutableStateOf(500f) }
    var cursorY by remember { mutableStateOf(500f) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    val focusRequester = remember { FocusRequester() }
    Box(modifier = Modifier.fillMaxSize().focusRequester(focusRequester).focusable().onKeyEvent { if (it.type == KeyEventType.KeyDown) { when (it.nativeKeyEvent.keyCode) { KeyEvent.KEYCODE_DPAD_UP -> { cursorY -= 40f; true }; KeyEvent.KEYCODE_DPAD_DOWN -> { cursorY += 40f; true }; KeyEvent.KEYCODE_DPAD_LEFT -> { cursorX -= 40f; true }; KeyEvent.KEYCODE_DPAD_RIGHT -> { cursorX += 40f; true }; KeyEvent.KEYCODE_DPAD_CENTER -> { webViewInstance?.let { view -> val time = SystemClock.uptimeMillis(); view.dispatchTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)); view.dispatchTouchEvent(MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, cursorX, cursorY, 0)) }; true }; else -> false } } else false }) { AndroidView(factory = { ctx -> WebView(ctx).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.userAgentString = "Mozilla/5.0"; webViewClient = WebViewClient(); webViewInstance = this; loadUrl(url) } }, modifier = Modifier.fillMaxSize()); Box(Modifier.offset { IntOffset(cursorX.roundToInt(), cursorY.roundToInt()) }.size(12.dp).background(Color.White, CircleShape).border(1.dp, Color.Black, CircleShape)); BackHandler { onBack() } }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

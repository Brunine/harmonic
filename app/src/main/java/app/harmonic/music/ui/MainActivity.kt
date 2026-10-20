package app.harmonic.music.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import app.harmonic.music.ui.components.neonBrush
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.harmonic.music.AppContainer
import app.harmonic.music.container
import app.harmonic.music.data.model.Track
import app.harmonic.music.ui.components.AddToPlaylistDialog
import app.harmonic.music.ui.components.MiniPlayer
import app.harmonic.music.ui.components.TrackMenuSheet
import app.harmonic.music.ui.screens.HomeScreen
import app.harmonic.music.ui.screens.LibraryScreen
import app.harmonic.music.ui.screens.LoginScreen
import app.harmonic.music.ui.screens.OnlinePlaylistScreen
import app.harmonic.music.ui.screens.PlayerScreen
import app.harmonic.music.ui.screens.PlaylistScreen
import app.harmonic.music.ui.screens.SearchScreen
import app.harmonic.music.ui.screens.SettingsScreen
import app.harmonic.music.ui.screens.JamScreen
import app.harmonic.music.ui.theme.HarmonicTheme
import app.harmonic.music.ui.theme.isDarkTheme
import app.harmonic.music.ui.theme.rememberAlbumSeed
import app.harmonic.music.data.prefs.ThemePalette
import app.harmonic.music.data.remote.CatalogRepository
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Text shared into the app ("Share → Harmonic" from YouTube or YouTube Music). */
    private val sharedText = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val settings by container.settings.state.collectAsStateWithLifecycle()
            val current by container.playerConnection.current.collectAsStateWithLifecycle()
            val albumSeed = rememberAlbumSeed(current?.thumbnailUrl, settings.palette == ThemePalette.ALBUM)
            val dark = isDarkTheme(settings.themeMode)
            LaunchedEffect(dark) {
                // Status / navigation bar icons follow the app theme, not just the system one.
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) {
                        SystemBarStyle.dark(transparent)
                    } else {
                        SystemBarStyle.light(transparent, transparent)
                    },
                    navigationBarStyle = if (dark) {
                        SystemBarStyle.dark(transparent)
                    } else {
                        SystemBarStyle.light(transparent, transparent)
                    },
                )
            }
            HarmonicTheme(settings.themeMode, settings.palette, settings.pureBlack, albumSeed) {
                HarmonicRoot(container, sharedText)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            sharedText.value = intent.getStringExtra(Intent.EXTRA_TEXT)
        }
    }

    override fun onStart() {
        super.onStart()
        container.playerConnection.connect()
    }

    override fun onStop() {
        super.onStop()
        container.playerConnection.disconnect()
    }
}

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

private val bottomTabs = listOf(
    BottomTab(Routes.HOME, "Home", Icons.Filled.Home),
    BottomTab(Routes.SEARCH, "Search", Icons.Filled.Search),
    BottomTab(Routes.LIBRARY, "Library", Icons.Filled.LibraryMusic),
    BottomTab(Routes.JAM, "Jam", Icons.Filled.Groups),
)

@Composable
fun HarmonicRoot(container: AppContainer, sharedText: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val downloads by container.downloads.states.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<Pair<Track, List<ExtraAction>>?>(null) }
    var addToPlaylist by remember { mutableStateOf<List<Track>?>(null) }

    val actions = remember {
        UiActions(
            openTrackMenu = { track, extras -> menuFor = track to extras },
            addToPlaylist = { addToPlaylist = it },
            showMessage = { msg ->
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(msg)
                }
            },
            navigate = { route -> nav.navigate(route) { launchSingleTop = true } },
        )
    }

    RequestNotificationPermission()

    LaunchedEffect(Unit) {
        container.playerConnection.messages.collect { actions.showMessage(it) }
    }

    // Handle links shared into the app: playlists open, single videos play right away.
    val shared by sharedText.collectAsStateWithLifecycle()
    LaunchedEffect(shared) {
        val text = shared ?: return@LaunchedEffect
        sharedText.value = null
        val url = extractUrl(text) ?: return@LaunchedEffect
        runCatching { container.catalog.resolveLink(url) }
            .onSuccess { target ->
                when (target) {
                    is CatalogRepository.LinkTarget.Song -> {
                        container.playerConnection.play(listOf(target.track))
                        if (!container.jam.isGuest) nav.navigate(Routes.PLAYER) { launchSingleTop = true }
                    }
                    is CatalogRepository.LinkTarget.Collection -> nav.navigate(Routes.online(target.url))
                    null -> actions.showMessage("Unsupported link")
                }
            }
            .onFailure { actions.showMessage("Couldn't open link: ${it.message}") }
    }

    CompositionLocalProvider(
        LocalContainer provides container,
        LocalUiActions provides actions,
        LocalDownloadStates provides downloads,
    ) {
        val jamState by container.jam.state.collectAsStateWithLifecycle()
        val backStack by nav.currentBackStackEntryAsState()
        val route = backStack?.destination?.route
        val showChrome = route != Routes.PLAYER && route != Routes.LOGIN

        Scaffold(
            // Each screen handles its own top inset; the root only reserves the bottom bar.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (showChrome) {
                    Column {
                        MiniPlayer(onOpen = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } })
                        Box(Modifier.fillMaxWidth().height(1.dp).background(neonBrush()))
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                            bottomTabs.forEach { tab ->
                                NavigationBarItem(
                                    selected = backStack?.destination?.route == tab.route,
                                    onClick = {
                                        nav.navigate(tab.route) {
                                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        if (tab.route == Routes.JAM && jamState.active) {
                                            BadgedBox(badge = { Badge() }) { Icon(tab.icon, null) }
                                        } else {
                                            Icon(tab.icon, null)
                                        }
                                    },
                                    label = { Text(tab.label.uppercase(), style = MaterialTheme.typography.labelSmall) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.tertiary,
                                        selectedTextColor = MaterialTheme.colorScheme.tertiary,
                                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                    ),
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = Routes.HOME,
                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            ) {
                composable(Routes.HOME) { HomeScreen() }
                composable(Routes.SEARCH) { SearchScreen() }
                composable(Routes.LIBRARY) { LibraryScreen() }
                composable(Routes.JAM) { JamScreen() }
                composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.PLAYER) { PlayerScreen(onClose = { nav.popBackStack() }) }
                composable(Routes.LOGIN) { LoginScreen(onDone = { nav.popBackStack() }) }
                composable(
                    Routes.PLAYLIST,
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { entry ->
                    PlaylistScreen(entry.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
                }
                composable(
                    Routes.ONLINE,
                    arguments = listOf(navArgument("url") { type = NavType.StringType; defaultValue = "" }),
                ) { entry ->
                    OnlinePlaylistScreen(entry.arguments?.getString("url").orEmpty(), onBack = { nav.popBackStack() })
                }
            }
        }

        menuFor?.let { (track, extras) ->
            TrackMenuSheet(track, extras, onDismiss = { menuFor = null })
        }
        addToPlaylist?.let { tracks ->
            AddToPlaylistDialog(tracks, onDismiss = { addToPlaylist = null })
        }
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

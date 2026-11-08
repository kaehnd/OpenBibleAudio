package com.openbibleaudio.openbibleaudio

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.openbibleaudio.core.model.Book
import com.openbibleaudio.openbibleaudio.playback.BookTitles
import com.openbibleaudio.openbibleaudio.ui.browse.BooksScreen
import com.openbibleaudio.openbibleaudio.ui.browse.ChaptersScreen
import com.openbibleaudio.openbibleaudio.ui.library.LibraryScreen
import com.openbibleaudio.openbibleaudio.ui.player.MiniPlayer
import com.openbibleaudio.openbibleaudio.ui.player.NowPlayingScreen
import com.openbibleaudio.openbibleaudio.ui.player.PlayerViewModel
import com.openbibleaudio.openbibleaudio.ui.settings.AboutScreen
import com.openbibleaudio.openbibleaudio.ui.settings.SettingsScreen
import com.openbibleaudio.openbibleaudio.ui.theme.OpenBibleAudioTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.Serializable

@Serializable object LibraryRoute
@Serializable data class BooksRoute(val versionId: String, val title: String)
@Serializable data class ChaptersRoute(val versionId: String, val book: String)
@Serializable object NowPlayingRoute
@Serializable object SettingsRoute
@Serializable object AboutRoute

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val player: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OpenBibleAudioTheme { App(player) } }
        if (savedInstanceState == null) handleSearch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSearch(intent)
    }

    private fun handleSearch(intent: Intent?) {
        if (intent?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        player.playFromSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(player: PlayerViewModel) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val playerState by player.state.collectAsStateWithLifecycle()
    val titles = BookTitles(LocalContext.current)
    val atRoot = destination?.hasRoute(LibraryRoute::class) != false
    val onNowPlaying = destination?.hasRoute(NowPlayingRoute::class) == true

    val title = when {
        destination?.hasRoute(BooksRoute::class) == true -> entry!!.toRoute<BooksRoute>().title
        destination?.hasRoute(ChaptersRoute::class) == true -> entry!!.toRoute<ChaptersRoute>().book.let { Book.fromUsfm(it)?.let(titles::book) ?: it }
        onNowPlaying -> stringResource(R.string.now_playing)
        destination?.hasRoute(SettingsRoute::class) == true -> stringResource(R.string.settings)
        destination?.hasRoute(AboutRoute::class) == true -> stringResource(R.string.about)
        else -> stringResource(R.string.library_title)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (!atRoot) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    if (atRoot) {
                        IconButton(onClick = { nav.navigate(SettingsRoute) }) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
                    }
                },
            )
        },
        bottomBar = {
            if (playerState.hasItem && !onNowPlaying) {
                Column { MiniPlayer(playerState, player) { nav.navigate(NowPlayingRoute) { launchSingleTop = true } } }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = LibraryRoute) {
            composable<LibraryRoute> {
                LibraryScreen(hiltViewModel(), padding) { versionId, abbreviation -> nav.navigate(BooksRoute(versionId, abbreviation)) }
            }
            composable<BooksRoute> { e ->
                val route = e.toRoute<BooksRoute>()
                BooksScreen(hiltViewModel(), route.versionId, padding) { book -> nav.navigate(ChaptersRoute(route.versionId, book.usfm)) }
            }
            composable<ChaptersRoute> { e ->
                val route = e.toRoute<ChaptersRoute>()
                val book = Book.fromUsfm(route.book) ?: return@composable
                ChaptersScreen(hiltViewModel(), route.versionId, book, padding) { ref ->
                    player.playChapter(route.versionId, ref)
                    nav.navigate(NowPlayingRoute) { launchSingleTop = true }
                }
            }
            composable<NowPlayingRoute> { NowPlayingScreen(playerState, player, padding) }
            composable<SettingsRoute> { SettingsScreen(hiltViewModel(), padding) { nav.navigate(AboutRoute) } }
            composable<AboutRoute> { AboutScreen(hiltViewModel(), padding) }
        }
    }
}

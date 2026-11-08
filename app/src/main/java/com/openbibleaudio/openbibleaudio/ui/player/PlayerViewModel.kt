package com.openbibleaudio.openbibleaudio.ui.player

import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.openbibleaudio.core.model.ChapterRef
import com.openbibleaudio.core.model.MediaId
import com.openbibleaudio.openbibleaudio.playback.PlaybackService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUiState(
    val connected: Boolean = false,
    val mediaId: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val error: String? = null,
) {
    val hasItem: Boolean get() = mediaId != null
}

/**
 * The phone UI is just another MediaController client of the playback session (AD-2,
 * spec 0002 §8). Activity-scoped so the mini-player and Now Playing share one controller.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(@ApplicationContext private val context: Context) : ViewModel() {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private var controller: MediaController? = null

    init {
        viewModelScope.launch {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val c = MediaController.Builder(context, token).buildAsync().await()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = update()
            })
            update()
            while (isActive) {
                delay(500)
                if (c.isPlaying) update()
            }
        }
    }

    private fun update() {
        val c = controller ?: return
        val item = c.currentMediaItem
        _state.value = PlayerUiState(
            connected = true,
            mediaId = item?.mediaId,
            title = item?.mediaMetadata?.title?.toString(),
            subtitle = item?.mediaMetadata?.artist?.toString(),
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0,
            speed = c.playbackParameters.speed,
            hasPrevious = item != null,
            hasNext = c.hasNextMediaItem(),
            error = c.playerError?.message,
        )
    }

    /** Sends only the chapter ID; the service builds the queue, exactly as for Auto (§7.5). */
    fun playChapter(versionId: String, ref: ChapterRef) {
        val c = controller ?: return
        c.setMediaItems(listOf(MediaItem.Builder().setMediaId(MediaId.Chapter(versionId, ref).format()).build()))
        c.prepare()
        c.play()
    }

    /** Voice search from MEDIA_PLAY_FROM_SEARCH: the service parses [query] (§7.7). */
    fun playFromSearch(query: String) {
        viewModelScope.launch {
            while (controller == null) delay(50)
            val c = controller ?: return@launch
            c.setMediaItems(listOf(MediaItem.Builder().setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).build()).build()))
            c.prepare()
            c.play()
        }
    }

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun seekBack() = controller?.seekBack()
    fun seekForward() = controller?.seekForward()
    fun previous() = controller?.seekToPrevious()
    fun next() = controller?.seekToNextMediaItem()
    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs)

    /** Cycles 0.75 → 1.0 → … → 2.0 → 0.75; the service saves it as `bibleSpeed` (§8). */
    fun cycleSpeed() {
        val c = controller ?: return
        val current = c.playbackParameters.speed
        val next = SPEEDS.firstOrNull { it > current + 0.01f } ?: SPEEDS.first()
        c.setPlaybackSpeed(next)
    }

    override fun onCleared() {
        controller?.release()
        controller = null
    }

    companion object {
        val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    }
}
